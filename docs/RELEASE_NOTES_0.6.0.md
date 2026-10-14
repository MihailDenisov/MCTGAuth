# MCTGAuth 0.6.0

[Русская версия](RELEASE_NOTES_0.6.0_RU.md)

## Highlights

- Telegram user navigation now has Server Information, Server Status, Player List, and My Playtime.
- Dedicated-server telemetry records authenticated heartbeats and player sessions without inbound connections.
- Moderators get a grouped Requests menu, paginated Player Statistics, and independent Telegram bot blocking.
- Backend schema migration `V005` is schema version `5`; full reset includes telemetry state.

## Telegram UX

- Passwords issued by the bot render only the password value as Telegram HTML `<code>`; surrounding text remains normal and the plaintext is not logged.
- How to Play, optional Download Modpack, Web Map, and Server Status live under Server Information. Optional links remain configuration-controlled.
- Server Status uses a 60-second freshness timeout by default. Stale heartbeats show the server as unavailable and suppress stale player counts/lists.
- Player List shows active sessions and current session durations only when telemetry is fresh.
- My Playtime supports account selection and backend-calculated rolling 24-hour, rolling 7-day, and all-time totals, including the active session without double-counting overlaps.

## Administration and moderation

- Ticket views are grouped under Requests: new, assigned, in progress, registrations, violations, problems, statistics, and search.
- Player Statistics is a separate permission-controlled, paginated view with total time and online/last-seen state.
- Block User in Bot and Unblock User in Bot require confirmation, use numeric Telegram IDs, and write idempotent audit transitions. Blocking bot workflows does not disable Minecraft authentication accounts.
- Existing ticket assignment, replies, decisions, paging/search, account enable/disable, and remote-management actions remain separate workflows.

## Telemetry

- The server generates a new `serverInstanceId` per process and sends authenticated outbound POST requests to `/api/v1/servers/heartbeat`, `/api/v1/servers/player-events`, and `/api/v1/servers/shutdown`.
- Heartbeats default to every 20 seconds. Player events are journaled atomically in `mctgauth/telemetry-events.json`, sent in order on a background worker, retried with bounded backoff, and capped at 10,000 queued events by default.
- `PLAYER_JOINED` and `PLAYER_LEFT` use stable event IDs and authoritative Minecraft account IDs; nicknames are snapshots for display.
- A new server instance closes old sessions as `SERVER_LOST` at the last trustworthy heartbeat. Clean shutdown uses `SERVER_SHUTDOWN` when delivered.
- No telemetry HTTP request runs on the Minecraft tick, login, logout, or render thread.

## Database and operations

- `V005__telemetry_and_bot_blocking.sql` adds `users.bot_blocked`, `server_status`, `player_sessions`, and `telemetry_events`.
- The full reset clears telemetry tables and the local telemetry journal while retaining schema migrations and required moderator configuration.
- Existing synchronized accounts remain usable during backend outages; telemetry failure does not disable local authentication.

## Compatibility and configuration

- Authentication wire protocol: version `1` (unchanged).
- Minecraft authentication cache: schema `1` (unchanged).
- Server config: `[telemetry] enabled`, `heartbeatIntervalSeconds` (default `20`), and `maxQueuedPlayerEvents` (default `10000`).
- Telegram feature YAML: `serverStatus.enabled`, `heartbeatTimeoutSeconds` (default `60`), `showPlayerCount`, `showPlayerList`, and `showSessionDuration`.
- Telemetry reuses the existing server ID and minimum-32-character bearer token. HTTPS is required in production, and authorization headers are never logged.

See [the 0.5.0 to 0.6.0 migration guide](MIGRATION_0.5.0_TO_0.6.0.md) and the [Telemetry API](api/TELEMETRY_API.md). This document describes the implemented feature set; it is not a statement that a particular deployment or test run succeeded.
