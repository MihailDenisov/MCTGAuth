# Upgrade from MCTGAuth 0.6.4 to 0.6.5

[Russian version](MIGRATION_0.6.4_TO_0.6.5_RU.md)

This is a coordinated backend, server, and client upgrade. Protocol 2 requires fingerprint capability, so 0.6.4 clients cannot authenticate against a 0.6.5 server.

1. Back up the SQLite database and Minecraft `mctgauth/accounts.json` cache.
2. Generate a new random secret of at least 32 characters and set `DEVICE_FINGERPRINT_SECRET` only in the backend service environment. Do not reuse `AUTH_API_TOKEN`, commit the value, or copy it to Minecraft/client configuration.
3. Optionally configure `DEVICE_FINGERPRINT_ENABLED`, `DEVICE_OBSERVATION_RETENTION_DAYS`, `DEVICE_BAN_ENABLED`, and `DEVICE_SIGNAL_WEIGHT_<SIGNAL>`. Defaults enable strong signals, keep GPU/MAC client collection disabled, retain observations for a documented 365-day target, and enable explicit device-ban enforcement.
4. Deploy `auth-backend-0.6.5.tar.gz`. Startup transactionally applies `V008__device_fingerprints_and_bans.sql` and preserves existing users, accounts, tickets, and audit history.
5. Verify `/health` and `/version`: application `0.6.5`, schema `8`.
6. Stop Minecraft, install `mctgauth-server-0.6.5.jar`, then distribute `mctgauth-client-0.6.5.jar` to every player before reopening the server.
7. Verify one normal login, one known cached login with backend unavailable, and the moderator device/ban views. Do not create a production ban solely as a deployment probe.

The Minecraft cache is migrated in place from schema 1 to schema 2. The full reset command now removes device observations, relationships, bans, effects, and exemptions and writes an empty schema-2 cache while preserving database migrations and moderator authorization.

Rollback of application binaries does not roll back SQLite schema 8. Restore the pre-upgrade database backup only as an explicit coordinated recovery; do not silently combine a restored database with newer state.
