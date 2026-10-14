# Upgrade from MCTGAuth 0.6.2 to 0.6.3

[Russian version](MIGRATION_0.6.2_TO_0.6.3_RU.md)

Back up the SQLite database and current configuration, then deploy `auth-backend-0.6.3.tar.gz` through the normal managed updater. Version 0.6.3 is a backend-only deployment release; do not replace the 0.6.2 Minecraft client or server JARs solely for this update.

No database migration is added. Schema remains `6`, authentication wire protocol remains `1`, and existing backend/Telegram configuration stays valid.

After restart, verify `/version` reports application `0.6.3` and schema `6`. Repeat the `server_status` query over several heartbeat intervals and confirm `last_heartbeat_at` advances while Telegram Server Status remains online.
