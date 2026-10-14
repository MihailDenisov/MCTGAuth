# Upgrade from MCTGAuth 0.5.0 to 0.6.0

[Русская версия](MIGRATION_0.5.0_TO_0.6.0_RU.md)

## Overview

0.6.0 is an in-place upgrade from 0.5.0. The authentication wire protocol remains version `1`; the Minecraft authentication-cache format remains schema `1`. The backend database migration advances the schema from `4` to `5` and adds telemetry/session data plus the Telegram bot-blocking state.

The normal upgrade preserves existing users, Minecraft accounts, credentials, tickets, and synchronized login behavior. Plan a backend restart and a Minecraft server restart. Install matching 0.6.0 server/client mods; clients must restart after their client JAR is replaced.

## 1. Back up and inspect the current installation

Create a verified SQLite backup and preserve both existing configuration files before changing the release:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
sudo cp -a /etc/minecraft-auth.env /etc/minecraft-auth.env.pre-0.6.0
sudo cp -a /etc/minecraft-auth-telegram.yaml /etc/minecraft-auth-telegram.yaml.pre-0.6.0
```

Before replacing the server mod, stop Minecraft and back up its authentication and telemetry files:

```bash
sudo systemctl stop minecraft.service
sudo tar -C <MINECRAFT_GAME_DIR> -czf /var/backups/minecraft-auth/mctgauth-pre-0.6.0.tar.gz mctgauth
```

Use the configured service name and absolute `MINECRAFT_GAME_DIR` when they differ.

## 2. Configure telemetry and Telegram status

The server mod's `config/mctgauth-server.toml` uses the existing backend credentials and server identity:

```toml
[backend]
baseUrl = "https://auth.example.com"
serverId = "survival-main"
apiToken = "<same minimum-32-character token as AUTH_API_TOKEN>"

[telemetry]
enabled = true
heartbeatIntervalSeconds = 20
maxQueuedPlayerEvents = 10000
```

`MCTGAUTH_API_TOKEN` may override `backend.apiToken`. Keep both the token and the config file secret. Production requires HTTPS; no inbound Minecraft port is needed. Telemetry is disabled if `telemetry.enabled=false`.

Add or merge the non-secret Telegram feature settings in `/etc/minecraft-auth-telegram.yaml`:

```yaml
serverStatus:
  enabled: true
  heartbeatTimeoutSeconds: 60
  showPlayerCount: true
  showPlayerList: true
  showSessionDuration: true
```

The timeout is bounded to 10–3600 seconds and defaults to 60 when omitted or invalid. Preserve existing `howToPlay`, map, modpack, and registration settings. Do not replace the environment file wholesale; retain `TELEGRAM_FEATURE_CONFIG_PATH` and existing secrets.

## 3. Install 0.6.0 and migrate the database

Upload the 0.6.0 backend archive, verify its published checksum, and run the existing updater:

```bash
sudo /opt/minecraft-auth-tools/update.sh /path/to/auth-backend-0.6.0.tar.gz
sudo /opt/minecraft-auth-tools/status.sh
```

At startup, migration `V005__telemetry_and_bot_blocking.sql` runs transactionally and is recorded in `schema_migrations`. It adds `users.bot_blocked`, `server_status`, `player_sessions`, and `telemetry_events`. Existing rows are retained; new telemetry history starts empty.

Install `mctgauth-server-0.6.0.jar` on the dedicated server and `mctgauth-client-0.6.0.jar` on clients. Do not copy the server JAR to clients or the client JAR to the dedicated server.

## 4. Telemetry and status behavior

The server sends authenticated outbound POST requests:

- `/api/v1/servers/heartbeat` every 20 seconds by default;
- `/api/v1/servers/player-events` for `PLAYER_JOINED` and `PLAYER_LEFT`;
- `/api/v1/servers/shutdown` during orderly shutdown when possible.

Each process generates a new `serverInstanceId`. Player events use a stable `eventId`, authoritative `minecraftAccountId`, and nickname snapshot. Events are captured from game callbacks and persisted atomically in `mctgauth/telemetry-events.json`; a single background worker sends them in order with bounded retry. The journal is capped at 10,000 events by default. A full server restart preserves unsent events; a full database reset also removes the journal.

The backend accepts only the configured `serverId` with the shared bearer token. A fresh heartbeat controls online state. Stale or shut-down servers are unavailable and do not expose stale counts or lists. A new instance closes old open sessions at the last trustworthy heartbeat with `SERVER_LOST`; time after that point is not estimated.

## 5. Telegram changes

The registered user menu now has **Server Information**. Its submenu contains How to Play, optional Download Modpack and Web Map, Server Status, and Back. My Account contains My Playtime; multiple owned accounts require account selection. Playtime is aggregated from timestamped sessions for rolling 24 hours, rolling 7 days, and all time, with overlapping intervals merged.

The admin home groups ticket views under **Requests**. Player Statistics is separate, permission checked, and paginated. Account administration can block or unblock bot access for the owning Telegram numeric ID. This moderation state prevents bot workflows but does not disable Minecraft accounts; account disabling remains an explicit separate action.

Issued passwords are rendered as Telegram HTML `<code>` for the password value only. Password generation and secret-handling rules are unchanged; passwords are not logged or written to audit data.

## 6. Verification and rollback

After deployment, check the loopback `/health` and `/version` endpoints, confirm schema version `5`, inspect service logs for migration/configuration errors, and verify a test heartbeat reaches the configured server ID. Confirm `/menu` and `/adm` in both locales and exercise status, player list, playtime, Requests, and block/unblock permissions with test accounts.

This migration is not backward runnable by a 0.5.0 binary: the schema is now `5` while 0.5.0 supports schema `4`. Application rollback must therefore follow the deployment guard; database rollback requires stopping services and restoring the verified pre-upgrade SQLite backup, then restoring matching 0.5.0 server/client artifacts. Never restore only one side.

## Full reset

The existing reset tool now also clears `server_status`, `player_sessions`, `telemetry_events`, and the server's `telemetry-events.json` journal while preserving schema migrations and required moderator configuration. A full reset deletes all playtime history and requires all users to register again; it is destructive and is not a normal upgrade step.
