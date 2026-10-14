# Telemetry API (0.6.0)

Minecraft initiates every request over the configured backend HTTPS URL. All endpoints require the same minimum-32-character bearer token as synchronization, and the backend verifies that body `serverId` equals configured `SERVER_ID`. Authorization headers are never logged.

- `POST /api/v1/servers/heartbeat`: `serverId`, unique-per-process `serverInstanceId`, `startedAt`, `occurredAt`, `onlinePlayers`, and `maxPlayers`.
- `POST /api/v1/servers/player-events`: stable `eventId`, server/instance IDs, `PLAYER_JOINED` or `PLAYER_LEFT`, authoritative `minecraftAccountId`, display-only nickname, and `occurredAt`.
- `POST /api/v1/servers/shutdown`: server/instance IDs and `occurredAt`.

The default heartbeat interval is 20 seconds; the example Telegram freshness threshold is 60 seconds. Heartbeats are replaceable. Player events are atomically stored in `mctgauth/telemetry-events.json`, sent in order off the game thread, retried with bounded backoff, and de-duplicated by `eventId`. The default journal bound is 10,000 events.

Heartbeat acceptance is transactional. `200 accepted` means the `server_status` insert/update committed. Repeated heartbeats update the existing row for `serverId`; the same instance preserves `started_at`, a newer instance replaces instance metadata, and every applied heartbeat updates freshness/counts and clears `shutdown_at`. Stale heartbeats receive `409`; persistence failures receive `500`.

When a new instance appears, open sessions from the previous process close with `SERVER_LOST` at its last trustworthy heartbeat. Unprovable time between that heartbeat and the crash is not counted.
