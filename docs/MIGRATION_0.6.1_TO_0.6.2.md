# Upgrade from MCTGAuth 0.6.1 to 0.6.2

[Russian version](MIGRATION_0.6.1_TO_0.6.2_RU.md)

Back up the SQLite database and current configuration, install the 0.6.2 backend archive, and restart the backend through the normal managed update procedure.

This patch does not add a database migration or configuration key. The database remains at schema version `6`, and existing 0.6.1 environment, Telegram feature YAML, and server telemetry interval settings remain valid. The authentication wire protocol and Minecraft authentication-cache schema remain version `1`.

After deployment, verify `/health` or `/version` reports `"version":"0.6.2"` and `"schemaVersion":6`. In a private chat, verify Server Information → Back for both an ordinary user and a moderator, then verify `/adm` still opens the administration menu for an authorized moderator.
