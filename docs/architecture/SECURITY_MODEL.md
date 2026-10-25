# Security Model (Draft)

Security decisions that must be resolved before production implementation:

1. Password verifier algorithm and parameters (prefer Argon2id; BCrypt is an acceptable fallback when configured correctly).
2. Replay-resistant client/server login protocol. Do not treat a reusable transmitted hash as a secure password proof.
3. MCTGAuth intentionally stores the player's authentication password in a dedicated client-local configuration file. This product decision is limited to the physical client: the password must not be logged, synchronized, sent to the backend, or transmitted over the Minecraft protocol. The UI masks it unless the player explicitly selects Show Password.
4. HTTPS-only backend synchronization and authenticated server requests.
5. Moderator authorization based on immutable Telegram numeric IDs, not usernames or group membership alone.
6. Rate limits for password rotation, ticket spam, login attempts, and administrative actions.
7. Secrets only from environment or dedicated secret-bearing configuration files with restrictive permissions; never Git. The Minecraft server token may live in its non-synchronized `config/mctgauth-server.toml` and may be overridden by `MCTGAUTH_API_TOKEN`.
8. Redaction: plaintext passwords, bot tokens, API tokens, and authentication proofs must never enter logs/audit records.
9. Minimize personal data replicated to Minecraft.
10. Define licensed/online-mode and offline-mode identity semantics explicitly before relying on UUIDs.
11. Remote administration is typed and allowlisted. Never forward arbitrary Telegram/backend text to shell execution, RCON, `Runtime.exec`, `ProcessBuilder`, or the Minecraft command dispatcher.
12. Remote commands require backend authorization, target-server validation, expiry/TTL, replay/duplicate protection, structured payload validation, result auditing, and per-command permission checks.
13. Button visibility is not authorization. The backend verifies the acting moderator's numeric Telegram ID and permission for every command request.

## Implemented Phase 2 controls

- The embedded HTTP listener rejects non-loopback bind addresses. Public TLS must terminate at a reverse proxy.
- Sync and remote-command endpoints require a bearer token of at least 32 characters and compare it in constant time where practical. Authorization headers are never logged.
- Remote commands use an enum allowlist, exact payload schemas, stable UUIDs, target-server validation, TTLs, idempotent lifecycle updates, permission checks, and audit records.
- Password generation uses `SecureRandom`; the backend and Minecraft server persist only Argon2id verifiers and credential versions. The generated plaintext is delivered to the Telegram user and may be persisted only by the dedicated MCTGAuth client configuration. It is never logged.
- Password changes enforce a ten-minute minimum interval and five changes per rolling 24 hours.
- Sync payloads exclude Telegram IDs and support/application personal data.
- VPS deployment packages contain only application launchers, libraries, and non-secret release metadata. Runtime secrets, SQLite data, logs, and backups remain outside immutable release directories.
- Management scripts use fixed absolute paths, validate archive entries and release names, reject symbolic links in releases, serialize state-changing operations with `flock`, and never evaluate the environment file as shell code.
- Updates take a verified SQLite online backup before activation and use an atomic `current` symlink switch. Application rollback never silently restores the database; schema compatibility is checked explicitly.
- Telegram chat location selects UI context only. Moderator authorization always uses the sender's enabled numeric Telegram ID and per-action permission; usernames remain display snapshots.
- Cross-chat moderator input actions are isolated by moderator ID, expire after 30 minutes, and are cleared by successful completion, `/cancel`, or `/menu`.

The Minecraft challenge-response protocol uses client-side Argon2id derivation and a nonce-bound HMAC proof. The dedicated client config is the only approved plaintext-password persistence location; backend and Minecraft server storage contain verifier material only.

## Device privacy and ban enforcement

- Hardware values are collected only by an activated MCTGAuth client. Enabled values are normalized and SHA-256 digested locally; raw values must never cross the protocol boundary, enter logs, audit data, fixtures, or the backend database.
- The backend re-tokenizes each client digest with HMAC-SHA-256 and an external `DEVICE_FINGERPRINT_SECRET` distinct from API/bot tokens. Rotating this secret intentionally breaks matching with historical observations and requires an operator migration/re-enrollment plan.
- Fingerprints are spoofable and probabilistic, not identity proof. GPU/MAC are weak, disabled by default, and never sufficient to resolve a device or drive linked enforcement.
- Matching only records many-to-many observations. A moderator must explicitly create every user, linked-user, or device ban. Linked expansion is one hop from devices directly observed for the target and never recursively traverses a linked user's other devices.
- Linked-ban exemptions do not override explicit device bans. Ban state is separate from ordinary account enablement so unban cannot re-enable a manually disabled account.
- The server caches opaque device IDs plus client digests only to enforce already-known device bans during backend outages. Unknown devices follow the explicit fail-open/fail-closed setting; the default preserves login for valid cached accounts.
