# MCTGAuth documentation

[Русская версия](README_RU.md) · [Project home](../README.md)

This index separates operator-facing guides from design references. The current release targets Java 21, Minecraft 1.21.1, NeoForge 21.1.244, and MCTGAuth 0.6.4.

## Start here

- [Project overview and build](../README.md)
- [Backend](../auth-backend/README.md)
- [MCTGAuth Server](../auth-neoforge-server/README.md)
- [MCTGAuth Client](../auth-neoforge-client/README.md)
- [Production deployment and operations](deployment/DEPLOYMENT.md)
- [Troubleshooting](troubleshooting/TROUBLESHOOTING.md)
- [0.6.0 release notes](RELEASE_NOTES_0.6.0.md)
- [Upgrade from 0.5.0 to 0.6.0](MIGRATION_0.5.0_TO_0.6.0.md)
- [0.6.1 release notes](RELEASE_NOTES_0.6.1.md)
- [Upgrade from 0.6.0 to 0.6.1](MIGRATION_0.6.0_TO_0.6.1.md)
- [0.6.2 release notes](RELEASE_NOTES_0.6.2.md)
- [Upgrade from 0.6.1 to 0.6.2](MIGRATION_0.6.1_TO_0.6.2.md)
- [0.6.3 release notes](RELEASE_NOTES_0.6.3.md)
- [Upgrade from 0.6.2 to 0.6.3](MIGRATION_0.6.2_TO_0.6.3.md)
- [0.6.4 release notes](RELEASE_NOTES_0.6.4.md)
- [Upgrade from 0.6.3 to 0.6.4](MIGRATION_0.6.3_TO_0.6.4.md)
- [Telemetry API](api/TELEMETRY_API.md)
- [0.4.2 release notes](RELEASE_NOTES_0.4.2.md)

## Architecture and security

- [Project logic](PROJECT_LOGIC.md) — ownership, workflows, synchronization, and outage boundaries.
- [Project structure](PROJECT_STRUCTURE.md) — module dependency direction and important packages.
- [Security model](architecture/SECURITY_MODEL.md) — credential, token, authorization, and deployment controls.
- [Remote-command security](architecture/REMOTE_COMMAND_SECURITY.md) — typed allowlist and replay/expiry controls.
- [Minecraft authentication architecture](MINECRAFT_AUTH_ARCHITECTURE.md)

## Interfaces and workflows

- [Authentication protocol V1](AUTH_PROTOCOL.md)
- [Authentication flow](MCTGAUTH_AUTH_FLOW.md)
- [Sync HTTP API](api/SYNC_API.md)
- [Remote-command HTTP API](api/REMOTE_COMMAND_API.md)
- [Database model and migrations](database/SCHEMA.md)
- [Registration](flows/REGISTRATION.md), [password changes](flows/PASSWORD_CHANGE.md), and [tickets](flows/TICKETS.md)
- [User menu](telegram/USER_MENU.md), [administration menu](telegram/ADMIN_MENU.md), and [ticket UI](telegram/TICKET_FLOWS.md)

## Installation and release operations

- [Deployment guide](deployment/DEPLOYMENT.md) is the canonical bilingual-managed-release procedure.
- [VPS toolkit technical notes](deployment/VPS_MANAGEMENT.md) describe layout and transaction details.
- [Server deployment](NEOFORGE_SERVER_DEPLOYMENT.md) and [client installation](NEOFORGE_CLIENT_INSTALLATION.md) remain focused supplements.

Planned or unsupported behavior is labeled explicitly. In particular, `RESYNC_PLAYER` now returns structured diagnostics and requests an immediate poll, but the sync API still has no single-account snapshot operation; safe live configuration reload is also not currently operational.
