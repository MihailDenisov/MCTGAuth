# MCTGAuth Troubleshooting

This guide is for MCTGAuth 0.5.0 with Java 21, Minecraft 1.21.1, and NeoForge 21.1.244 (or a compatible 21.1.x release). Collect diagnostics before changing configuration. Never paste passwords, verifier material, proofs, nonces, salts, API tokens, Telegram bot tokens, or the contents of a secret environment/configuration file into an issue or chat.

## Safe first checks

On the VPS, run commands as an operator and share only their exit status and redacted output:

```bash
java -version
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

The service health endpoint is loopback-only. If a direct check is needed, do not add headers containing tokens:

```bash
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/health
```

For a local Windows development checkout:

```powershell
java -version
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:verifyServerJar
```

Redact Telegram IDs, player UUIDs, database paths, hostnames, and any authorization header from shared logs unless they are required for a private incident. The application and deployment toolkit are designed to avoid logging secrets.

## Java or startup failure

The backend and VPS toolkit require Java 21. A different major version can prevent startup or produce misleading Gradle/runtime errors. Confirm that the `java` command used by systemd is also Java 21; do not rely only on a shell-local `JAVA_HOME`.

If `install.sh` reports a missing prerequisite, install a maintained Java 21 runtime plus `sqlite3`, `curl`, `tar`, `flock`, and systemd on Debian. Re-run `java -version` and the safe service checks above. Do not work around a failed prerequisite by removing validation.

## Backend service is down or unhealthy

Use `status.sh`, `health.sh`, and `logs.sh -n 200` first. Confirm that `/etc/minecraft-auth.env` exists, is owned by `root:root`, and has mode `0600`, without printing it:

```bash
sudo stat -c '%U:%G %a %n' /etc/minecraft-auth.env
sudo systemctl status minecraft-auth.service --no-pager
```

Check configuration keys without values if necessary:

```bash
sudo awk -F= '/^[A-Z][A-Z0-9_]*=/{print $1}' /etc/minecraft-auth.env
```

Required deployment values include `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` (without `@`), `TELEGRAM_ADMIN_CHAT_ID`, `MODERATOR_TELEGRAM_IDS`, `AUTH_API_TOKEN`, `AUTH_DATABASE_PATH`, and `HOW_TO_PLAY_URL`. `HTTP_BIND_ADDRESS` must remain loopback (`127.0.0.1` or `localhost`). `SERVER_ID` must match the server configuration.

After an upgrade from 0.2, an existing environment file is intentionally preserved and is not automatically completed. Add newly required keys using a protected editor, then restart and re-check health. Never replace a real token with a placeholder from an example file.

## Telegram commands or moderation do not work

Verify the bot username is configured without `@`; `/menu@ExampleBot` is accepted for this bot and commands addressed to another bot are ignored. Verify the administrator chat ID and moderator numeric Telegram IDs. Usernames are display metadata and do not grant authorization.

Check the backend logs for a redacted startup/configuration error and the bot's current locale/menu behavior. Do not troubleshoot by posting a token or by making a user a moderator directly in a message. The database is authoritative; Telegram messages are only views of ticket state.

## Minecraft server cannot synchronize

The server mod needs Java 21, the matching `mctgauth-server-0.5.0.jar`, and a dedicated-server-only `config/mctgauth-server.toml`. Check that `backend.baseUrl` is the HTTPS backend URL, `backend.serverId` equals backend `SERVER_ID`, and `backend.apiToken` is the same value as `AUTH_API_TOKEN`. The optional `MCTGAUTH_API_TOKEN` environment override takes priority over the TOML value. Compare tokens privately; never print either value.

No inbound port allocation is required for synchronization. The server makes outbound HTTPS requests. If the token is absent, synchronization is disabled but previously synchronized enabled accounts remain available from the local cache. Invalid enabled backend configuration must be corrected rather than bypassing TLS verification.

Run the server's normal diagnostic command and inspect only redacted MCTGAuth log lines. `verifyServerJar` can detect accidental dependency leakage before deployment:

```powershell
.\gradlew.bat :auth-neoforge-server:verifyServerJar
```

## Player cannot authenticate

Ensure the client and server mods are present and compatible, and that the account was approved and synchronized. Until authentication succeeds, quarantine intentionally blocks movement, interaction, inventory, item transfer, damage, chat, and commands.

The client stores the password as plaintext in `.minecraft/config/mctgauth-client.json`; protect that file and never include it in diagnostics. Open **MCTGAuth** on the title screen or **Mods → MCTGAuth Client → Config**, save the current password, and retry. After a backend password rotation, the stored password can be rejected and must be replaced.

The terminal reasons `ACCOUNT_NOT_SYNCHRONIZED`, `ACCOUNT_DISABLED`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING`, and `INTERNAL_AUTH_ERROR` stop the current authentication lifecycle. MCTGAuth does not call Minecraft connection APIs or schedule automatic reconnects. Return to the server or reconnect manually; a reconnect performed without an MCTGAuth `Starting reconnect` log entry came from outside the mod.

## AutoModPack or third-party reconnect risk

AutoModPack, reconnect utilities, launchers, and server wrappers can install, remove, replace, or independently restart mods and can initiate connections outside MCTGAuth. Disable or isolate such automation while reproducing a client/server mismatch. Confirm the exact jar versions and hashes privately, then test with only the intended MCTGAuth client/server/protocol artifacts and required dependencies. Do not assume an automatic reconnect proves that MCTGAuth authenticated successfully.

## Corrupt server JSON cache

The server cache is `<server>/mctgauth/accounts.json`; `accounts.json.bak` is the previous backup and `accounts.json.tmp` may be an interrupted write. Existing corrupt data is preserved and authentication fails closed. Stop the server before examining or restoring cache files. Do not delete `accounts.json`, hand-edit verifier fields, or copy a cache from another server/account set. Preserve the files for incident analysis and restore only a known-good backup after confirming the matching `server_id`, account ownership, and permissions. Backend synchronization can repopulate an approved cache when connectivity and credentials are fixed.

## SQLite, deployment, update, or rollback failure

Keep application rollback and database restoration separate. First collect:

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

`update.sh` validates the archive/schema, creates a SQLite backup, switches the `current` symlink atomically, starts the service, and checks `/health`. If health fails after switching, it retains the failed release and restores the previous release. A code rollback does not restore the database. A newer schema may be unreadable by an older release, so `rollback.sh` can refuse an incompatible target; do not use `--force-incompatible` without reviewing migrations and a verified backup.

For database damage, use the toolkit backup and restore workflow rather than copying WAL files:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
sudo /opt/minecraft-auth-tools/restore.sh /var/backups/minecraft-auth/<known-good-backup>.db
```

The restore script stops the service, validates the backup, keeps an emergency backup, restores ownership/permissions, restarts, and checks health. Never run cleanup while diagnosing a failed release; use `cleanup.sh --dry-run` first and preserve the active and previous releases.

## What to include in a report

Include the sanitized MCTGAuth version, Java major version, Minecraft/NeoForge versions, affected module (backend/client/server), exact redacted reason code, timestamp with timezone, and the exit status of the safe checks above. Include relevant stack-trace lines only after removing passwords, tokens, proofs, database contents, Telegram IDs, UUIDs, and filesystem paths that reveal private infrastructure.
