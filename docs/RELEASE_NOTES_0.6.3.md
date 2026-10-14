# MCTGAuth 0.6.3

[Russian version](RELEASE_NOTES_0.6.3_RU.md)

## Backend-only deployment release

MCTGAuth 0.6.3 was published as a backend-only build of the 0.6.2 source line. It retained the completed backend heartbeat persistence correction: repeated accepted heartbeats update the existing `server_status` row, advance `last_heartbeat_at` and player counts, preserve same-instance start time, replace newer-instance metadata, and clear shutdown state.

HTTP success continues to mean that heartbeat persistence committed. Stale heartbeats receive a conflict response and persistence failures receive a non-success response with safely redacted server logging.

## Compatibility

- Database schema remains `6`; there is no migration from 0.6.2.
- Authentication wire protocol remains `1`.
- This release does not require 0.6.3 Minecraft client or server artifacts.
- Existing 0.6.2 configuration remains valid.

See [the 0.6.2 to 0.6.3 upgrade guide](MIGRATION_0.6.2_TO_0.6.3.md).
