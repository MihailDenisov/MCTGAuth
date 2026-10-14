# MCTGAuth 0.6.2

[Russian version](RELEASE_NOTES_0.6.2_RU.md)

## Telegram UI fixes

- The **Back** button in **Server Information** now has the explicit stable callback `server_info:back` and always opens the normal user menu.
- A moderator who enters Server Information from the user menu also returns to the user menu; `/adm` remains the separate authorized administration entry point.
- The Server Information actions now consistently use emoji: 📖 How to Play, 📦 Download Modpack, 🗺 Web Map, 📡 Server Status, and ⬅️ Back.
- Optional modpack and map buttons remain hidden unless their existing feature configuration provides a valid enabled URL.
- English and Russian labels use the same locale-independent callback IDs.

## Included telemetry fix

- The completed dedicated-server heartbeat fix is included in this release: recurring execution catches individual task failures, retains its scheduled task for lifecycle control, runs on its dedicated daemon thread, starts after the server-ready event, and stops on genuine server shutdown.
- Heartbeat lifecycle diagnostics report safe state and exception types without logging bearer tokens or authorization headers.
- Backend heartbeat persistence now correctly recognizes the same `serverInstanceId`, updates `last_heartbeat_at` and player counts on every applied heartbeat, preserves `started_at` for the same instance, replaces metadata for a newer instance, and clears `shutdown_at` when liveness resumes.
- HTTP `200 accepted` is sent only after the heartbeat transaction commits. Stale heartbeats receive `409`, and persistence failures receive `500` with a safe server-side error type.

## Compatibility

- Database schema remains version `6`; this release adds no migration.
- Authentication wire protocol remains version `1`.
- Backend configuration, the Minecraft authentication cache, and client/server authentication remain compatible with 0.6.1.

See [the 0.6.1 to 0.6.2 upgrade guide](MIGRATION_0.6.1_TO_0.6.2.md).
