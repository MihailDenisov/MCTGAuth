# Upgrade from MCTGAuth 0.6.0 to 0.6.1

[Russian version](MIGRATION_0.6.0_TO_0.6.1_RU.md)

Back up the SQLite database and current configuration, install the 0.6.1 backend archive, and restart the backend. On startup, `V006__ticket_closure_comments.sql` runs transactionally and advances the database schema from 5 to 6.

The migration adds `message_type` and `visibility` metadata to `ticket_messages`. Existing rows become public ordinary messages. It also extends persisted moderator pending actions with a validated closure target; existing pending reply rows are preserved.

Verify `/health` or `/version` reports `"version":"0.6.1"` and `"schemaVersion":6`, then inspect backend logs for migration or Telegram errors. The authentication protocol and local server cache do not change, but use matching 0.6.1 client/server JARs for consistent release metadata.

No environment or YAML configuration change is required.
