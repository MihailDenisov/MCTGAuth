# MCTGAuth 0.5.0

[Русская версия](RELEASE_NOTES_0.5.0_RU.md)

## Highlights

- Server operators can configure Telegram content and optional features in a non-secret UTF-8 YAML file.
- Account disable now has complete administrative audit context and reliable persistence-before-acknowledgement synchronization.
- A guarded full-reset operation can clear backend identity data and the Minecraft authentication cache together after verified backups.

## Telegram

- Localized `howToPlay.en` and `howToPlay.ru` content is loaded at backend startup.
- Optional server-map and modpack-download links appear only when enabled with valid HTTPS URLs.
- The optional registration subscription gate checks channel membership through Telegram `getChatMember` before creating wizard state.
- Non-members receive Subscribe and Check again actions. Temporary API errors remain retryable and fail closed for registration.
- Disabled features retain the prior menu behavior; stale callbacks are rejected safely.

## Administration

- Account activation and deactivation audit records include the before and after account state.
- Telegram numeric IDs remain the authorization identity; usernames remain display metadata.
- Full reset preserves schema migrations, moderator identities and permissions, and SQLite ID sequences while deleting operational identity, credential, ticket, sync, command, and audit data.

## Minecraft

- Sync events are acknowledged only after the account cache is persisted successfully.
- A committed `ACCOUNT_DISABLED` event disconnects the affected online player when `disconnectPlayerWhenAccountDisabled=true`.
- Existing synchronized accounts remain login-capable during backend or Telegram outages.
- Authentication wire protocol remains version `1`; 0.5.0 introduces no wire-format change.

## Operations

- `deploy/vps/reset-database.sh` provides count-only `--dry-run`, exact interactive confirmation, service coordination, SQLite and cache backups, post-reset integrity checks, and fail-safe service handling.
- `/etc/minecraft-auth-telegram.yaml` contains non-secret feature content; `/etc/minecraft-auth.env` points to it through `TELEGRAM_FEATURE_CONFIG_PATH`.
- The backend database remains schema `4`; no SQL or Minecraft cache migration is required.

## Migration

Follow [Upgrade from 0.4.x to 0.5.0](MIGRATION_0.4.x_TO_0.5.0.md). Normal upgrade preserves every account and credential. **FULL RESET is not an upgrade step**: it invalidates all prior player credentials and requires every player to register again.

## Downloads

- `mctgauth-client-0.5.0.jar` — install only on player clients.
- `mctgauth-server-0.5.0.jar` — install only on the dedicated server.
- `auth-backend-0.5.0.tar.gz` — upload to the VPS and install with `update.sh`.
- `SHA256SUMS.txt` — SHA-256 checksums for the three distributable artifacts.

## Checksums

Verify every downloaded artifact against the attached `SHA256SUMS.txt` before installation. The release archive embeds the exact Git commit in `release.properties`.
