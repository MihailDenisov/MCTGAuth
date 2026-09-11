# Remote Command Security

## Principle

Remote administration is a narrow capability channel, not a remote console.

## Mandatory controls

1. **Typed allowlist only.** Every command is represented by an enum/type plus a validated versioned payload.
2. **No arbitrary execution.** Never send Telegram text to a system shell, `Runtime.exec`, `ProcessBuilder`, RCON, or Minecraft command parsing/execution APIs.
3. **Authorization by numeric Telegram ID.** Usernames and group membership are display/context only.
4. **Per-command permissions.** Example permissions: `server.status`, `auth.sync.force`, `auth.player.resync`, `auth.player.enable`, `auth.player.disable`.
5. **Server targeting.** A server may retrieve only commands for its configured `server_id`.
6. **Authenticated HTTPS.** Reuse or extend the server-authentication mechanism used by the sync API. Consider request signing/timestamps if bearer-token risk is insufficient for deployment needs.
7. **Expiry.** Commands have a TTL and are rejected after expiry.
8. **Replay/idempotency controls.** Stable command IDs and local processed-ID tracking prevent accidental repeated execution.
9. **Audit.** Record creator, command type, target server, lifecycle transitions, timestamps, and safe result/error metadata.
10. **Redaction.** No bot token, API token, plaintext password, credential verifier, or reusable proof in payload/result/logs.
11. **Rate limits.** Rate-limit repeated administrative actions where flooding can affect server availability or generate excessive sync work.
12. **Fail closed.** Unknown command types/versions, malformed payloads, unauthorized actors, wrong server IDs, and expired commands are rejected.

## Role example

- `MODERATOR`: ticket operations only.
- `SENIOR_MODERATOR`: ticket operations plus selected account-management operations.
- `ADMIN`: force sync, resync player, enable/disable auth accounts, diagnostics.
- `OWNER`: moderator/permission administration and sensitive configuration operations.

Exact roles are optional; permissions should remain explicit even if roles are used as bundles.

## Phase 2 implementation

The backend currently enables `FORCE_AUTH_SYNC`, `AUTH_STATUS`, and `RESYNC_PLAYER` only. Payloads use exact version-1 schemas (`{}` or a positive `accountId`), and sensitive-looking fields are rejected. Default TTLs are five minutes for force-sync/status and ten minutes for player resync.

The API accepts requests only for configured `SERVER_ID` and requires the server bearer token. Duplicate start and identical completion calls are idempotent. Expired pending/running commands transition to `EXPIRED` and cannot start afterward.
