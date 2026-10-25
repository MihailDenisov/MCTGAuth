# Two-stage upgrade: 0.6.4 to Backend 0.6.6

[Русская версия](MIGRATION_0.6.5_TO_BACKEND_0.6.6_RU.md)

The supported path is intentionally two-stage:

```text
MCTGAuth 0.6.4
│
│ Full-stack upgrade: backend + client + server
│ Protocol 1 -> 2; schema 7 -> 8
▼
MCTGAuth 0.6.5
│
│ Backend-only upgrade; client/server stay 0.6.5
│ Protocol stays 2; schema stays 8
▼
Backend 0.6.6 + Client 0.6.5 + Server 0.6.5
```

Do not install backend 0.6.6 while leaving 0.6.4 Minecraft mods in service.

## Stage A: full-stack 0.6.4 to 0.6.5

Follow [the existing 0.6.5 migration guide](MIGRATION_0.6.4_TO_0.6.5.md). Back up the database, configure a new external `DEVICE_FINGERPRINT_SECRET`, deploy V008/schema 8, replace both client and server mods, replace the backend, and verify coordinated protocol-2 authentication and device resolution. Preserve the documented fingerprint settings and run the guide's post-upgrade checks.

Rollback across Stage A is not a simple binary swap: protocol, schema, and device state changed. Follow the 0.6.5 backup/rollback policy and do not run 0.6.4 code against schema 8 without the documented compatibility review or database restoration procedure.

## Stage B: backend-only 0.6.5 to 0.6.6

1. Confirm backend, client, and server 0.6.5 are operational with protocol 2.
2. Create and integrity-check an online backend database backup.
3. Confirm `schema_migrations` ends at version 8.
4. Confirm the deployed client/server JARs are 0.6.5. Do not replace them for this stage.
5. Build or obtain `auth-backend-0.6.6.tar.gz`.
6. Preserve all 0.6.5 production configuration and secrets, including `DEVICE_FINGERPRINT_SECRET`.
7. Use the managed update script to stop, activate, start, and health-check the backend release.
8. Verify `/health`, `/menu`, `/adm`, Back and pagination, password delivery, and device/ban screens.
9. Verify client 0.6.5 -> server 0.6.5 -> protocol 2 -> backend 0.6.6 authentication.

No database migration or new configuration key is required in Stage B.

## Stage B rollback

Stop the backend, reactivate the known-good 0.6.5 backend artifact using the managed rollback tooling, retain the compatible schema-8 database and 0.6.5 mods, restart, and verify health, Telegram navigation, and Minecraft authentication. Database restoration is separate and should not be performed merely to roll back the 0.6.6 UI code.
