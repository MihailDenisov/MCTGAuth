# MCTGAuth backend 0.4.2

[Русская версия](README_RU.md)

Standalone Java 21 backend. It runs the Telegram bot through long polling and is authoritative for users, registration/support tickets, Minecraft accounts, Argon2id verifiers, audit history, synchronization events, and typed remote commands. Minecraft modules consume its HTTPS API; they do not use this SQLite database.

## Requirements and security

Java 21 (JDK/JRE), outbound HTTPS to `api.telegram.org`, a BotFather bot, a private administration group, and a writable SQLite parent directory are required. The process reads environment variables directly and does not load `.env` files. Keep secrets out of Git; production environment files should be `root:root`, mode `0600`. Plaintext Minecraft passwords are delivered only to the recipient and are never stored, logged, or synchronized. Numeric Telegram IDs authorize; usernames are display metadata.

## Configuration

`BackendConfig` validates required values and formats at startup.

| Variable | Required | Default/example | Secret? / behavior |
|---|---|---|---|
| `TELEGRAM_BOT_TOKEN` | Yes | `<BotFather token>` | Secret long-polling credential; never log/commit. |
| `TELEGRAM_BOT_USERNAME` | Yes | `ExampleBot` | No; 5–32 `[A-Za-z0-9_]`, without `@`; command addressing filter. |
| `TELEGRAM_ADMIN_CHAT_ID` | Yes | `-1000000000000` | No; non-zero numeric private admin-group ID (often negative). |
| `MODERATOR_TELEGRAM_IDS` | Yes | `123456789,987654321` | No; comma-separated positive numeric authorization IDs. |
| `AUTH_DATABASE_PATH` | No | `./data/auth-backend.db` | Sensitive path; service must write its parent. |
| `HOW_TO_PLAY_URL` | Yes | `https://t.me/example/1` | No; absolute HTTPS URL shown by the bot. |
| `HTTP_BIND_ADDRESS` | No | `127.0.0.1` | No; must resolve loopback; public bind rejected. |
| `HTTP_PORT` | No | `8080` | No; 1–65535. |
| `AUTH_API_TOKEN` | Yes | `<32+ random characters>` | Secret bearer token; 32–512 non-whitespace characters. |
| `SERVER_ID` | Yes | `survival-main` | No; stable 1–64 `[A-Za-z0-9._-]` target. |
| `ADMIN_LOCALE` | No | `en` or `ru` | No; shared admin-group language; default `en`. |
| `DEFAULT_USER_LOCALE` | No | `en` or `ru` | No; initial private-chat language; default `en`. |

Examples are placeholders. Set real values only in process environment or `/etc/minecraft-auth.env`; never print that file. `AUTH_API_TOKEN` must match server `backend.apiToken` or optional `MCTGAUTH_API_TOKEN`.

## Telegram behavior

Supported commands are `/start`, `/menu`, `/adm`, `/admin` (alias), `/register`, `/tickets`, `/account`, and `/cancel`; both `/command` and `/command@ConfiguredBotUsername` forms work, while commands addressed to another bot are ignored. `/start` gives a new user the language selector. In private chat `/menu` always opens the ordinary user menu, even for moderators; `/adm`/`/admin` require an enabled moderator ID. In the configured admin group `/menu`, `/adm`, and `/cancel` open/reset the inline admin panel and re-check authorization. Other groups are ignored.

Users register, view tickets/accounts, report problems or violations, request additional accounts, change passwords, and select English/Russian. Registration/add-account approval atomically creates the account, verifier, audit record, and sync event; an active registration is not duplicated. Moderators filter/search tickets, reply privately, approve/reject eligible tickets, transition support tickets, view statistics, inspect accounts, and queue `FORCE_AUTH_SYNC`, `AUTH_STATUS`, or `RESYNC_PLAYER`. Disable requires reason/confirmation; enable/disable is audited, notifies the owner, emits sync, and is idempotent. Telegram cards are projections; SQLite is authoritative. Pending moderator replies are private, assigned by numeric ID, and expire after 30 minutes.

Database/business timestamps are UTC `Instant`; Telegram renders them as `Europe/Moscow`, `dd.MM.yyyy HH:mm MSK` (or `МСК`).

## HTTP API

The embedded server is loopback-only. `/health` and `/version` need no bearer token but must not be exposed publicly. Every `/api/v1/server/*` route requires `Authorization: Bearer <AUTH_API_TOKEN>` and `server_id`. JSON request bodies are limited to 16 KiB.

| Method/path | Response and concept |
|---|---|
| `GET /health` | `200` when DB is healthy, otherwise `503`; returns status, database/Telegram state, version, commit, schemaVersion. |
| `GET /version` | `200`; returns application, version, commit, buildTime, schemaVersion only. |
| `GET /api/v1/server/events?server_id=<id>` | `200`; pending sync events for the configured server. |
| `POST /api/v1/server/events/<event-id>/ack?server_id=<id>` | `200 acknowledged` or `404 not_found`; repeat-safe acknowledgement. |
| `GET /api/v1/server/commands?server_id=<id>` | `200`; ordered, non-expired typed commands. |
| `POST /api/v1/server/commands/<command-id>/started?server_id=<id>` | `200 running` or `409 invalid_state`. |
| `POST /api/v1/server/commands/<command-id>/result?server_id=<id>` | JSON `status` `SUCCESS`/`FAILED`, structured `result`, optional safe `errorMessage`; `200` or `409 invalid_state`. |

Errors: unauthorized is `401` plus `WWW-Authenticate: Bearer`; unsupported method `405 method_not_allowed`; malformed ID/JSON/status/query `400 invalid_request`; unknown route/ID `404 not_found`; rejected workflow `403`/`409 request_rejected`. Responses are JSON, `Cache-Control: no-store`. Only configured `SERVER_ID` is accepted; event/command IDs, expiry, acknowledgements, and state transitions are idempotently checked. See [`docs/api/SYNC_API.md`](../docs/api/SYNC_API.md) and [`docs/api/REMOTE_COMMAND_API.md`](../docs/api/REMOTE_COMMAND_API.md). There is no arbitrary shell/RCON command field.

## SQLite, build, and run

On open, the backend creates the parent directory, enables foreign keys, a 5-second busy timeout, WAL journaling, and ordered migrations. Schema version is exposed by `/health` and `/version`; migration metadata is `src/main/resources/db/migration/migrations.txt`. UTC is stored in the database; only Telegram display uses Moscow time. Stop the service before backup/restore. Do not delete or hand-edit this authoritative database.

```powershell
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:installDist
.\gradlew.bat :auth-backend:deploymentArchive
```

Linux uses `./gradlew` with the same tasks. Distribution: `auth-backend/build/install/auth-backend/`; archive: `auth-backend/build/distributions/auth-backend-<version>.tar.gz`. Export the variables above and use `:auth-backend:run` or the generated launcher; do not put real secrets in command history.

## Managed deployment and operations

Use the canonical managed Debian toolkit: [`deploy/vps/README.md`](../deploy/vps/README.md) and [`docs/deployment/VPS_MANAGEMENT.md`](../docs/deployment/VPS_MANAGEMENT.md). It separates releases from `/var/lib/minecraft-auth` data and `/etc/minecraft-auth.env`, validates archive/schema, performs SQLite backups, health-checks updates, and guards rollback/restore. Do not replace `/opt/minecraft-auth` directly using the legacy procedure.

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

For Java, Telegram, synchronization, cache, deployment, rollback, and redaction procedures, see [`docs/troubleshooting/TROUBLESHOOTING.md`](../docs/troubleshooting/TROUBLESHOOTING.md).
