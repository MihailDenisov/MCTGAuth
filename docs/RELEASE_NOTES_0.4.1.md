# MCTGAuth 0.4.1 release notes

[Русская версия](RELEASE_NOTES_0.4.1_RU.md)

## Highlights

- Complete Java 21 backend plus MCTGAuth Server and Client for Minecraft 1.21.1 / NeoForge 21.1.244.
- English/Russian Telegram UI with user menus, registration, problem/violation/add-account tickets, conversations, moderation, account search, account enable/disable, password changes, and audit history.
- Replay-resistant protocol V1 using client-side Argon2id derivation and nonce/context-bound HMAC-SHA256 proofs.
- Outbound authenticated server polling, atomic JSON cache, idempotent sync processing, quarantine before authentication, and cached-login tolerance during backend outages.
- Debian release toolkit with validated archives, SQLite online backups, atomic activation, health checks, automatic application rollback, explicit database restore, retention cleanup, and operation logging.

## Backend and Telegram

- `/menu` always opens the normal private user menu; `/adm` is the dedicated moderator entry point; `/cancel` clears the active workflow.
- Numeric Telegram IDs authorize moderators. Shared-group chat ID controls presentation context only.
- Account state changes reuse `minecraft_accounts.enabled`, are transactional and idempotent, create `ACCOUNT_DISABLED` / `ACCOUNT_ENABLED` events, and are audited.
- Telegram-visible timestamps use `Europe/Moscow`; stored timestamps and TTL calculations remain UTC.
- `/health` and `/version` expose non-secret release/schema metadata. Sync and remote-command APIs require a bearer token.

## MCTGAuth Server

- Stores verifier records and processed event/command IDs in `mctgauth/accounts.json` using temporary-file, backup, and atomic-replace persistence.
- Applies `ACCOUNT_CREATED`, `CREDENTIAL_CHANGED`, `ACCOUNT_ENABLED`, `ACCOUNT_DISABLED`, and `ACCOUNT_REMOVED` events.
- Can disconnect an online player after account disable when `disconnectPlayerWhenAccountDisabled=true`.
- Provides `/mcauth status`, `/mcauth sync`, and `/mcauth player <name>` to permission-level-2 operators.

## MCTGAuth Client

- Adds title-screen and Mods-menu settings entry points.
- Stores the Telegram-issued password in `.minecraft/config/mctgauth-client.json` as plaintext by explicit product design; protect the local machine and never share this file.
- Supports password editing/clearing, Show Password, and automatic authentication.
- Terminal authentication errors do not start an automatic reconnect loop.

## Configuration and migration

- Backend configuration is provided by process environment variables. `TELEGRAM_BOT_USERNAME` is required and must not include `@`.
- `AUTH_API_TOKEN` must contain at least 32 characters and must match the dedicated server secret.
- The server secret belongs only in server-controlled `config/mctgauth-server.toml` or `MCTGAUTH_API_TOKEN`; do not synchronize it with AutoModPack.
- Database migration `V004__telegram_workflow_fixes.sql` is applied automatically and brings the maximum schema version to 4. It makes violation offender nickname optional and adds persistent expiring moderator reply state.
- No manual database transformation is required. Back up SQLite with the supplied toolkit before updating.

## Upgrade

1. Read [deployment documentation](deployment/DEPLOYMENT.md) and back up the database.
2. Add any newly required environment variables, especially `TELEGRAM_BOT_USERNAME`, without replacing existing secrets.
3. Build `auth-backend-0.4.1.tar.gz`, `mctgauth-server-0.4.1.jar`, and `mctgauth-client-0.4.1.jar`.
4. Activate the backend archive through `update.sh`; do not manually copy database/WAL files.
5. Replace both mod JARs while the Minecraft server/client is stopped, then verify `/health`, `/mcauth status`, synchronization, and a test login.

## Known limitations

- `/mcauth resync <name>`, `/mcauth reload`, and the typed `RESYNC_PLAYER` execution path are not operational.
- The backend binds only to loopback. Production TLS requires a reverse proxy.
- This release has automated build/unit coverage; operators must still perform live Telegram and dedicated-server smoke tests in their own deployment.
