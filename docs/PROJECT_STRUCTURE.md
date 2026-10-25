# Project Structure

```text
minecraft-telegram-auth/
├── AGENTS.md
├── README.md
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew / gradlew.bat                 # Gradle Wrapper; add during bootstrap
├── gradle/wrapper/                       # Wrapper JAR/properties; add during bootstrap
├── .vscode/                              # VS Code recommendations/tasks
├── .agents/skills/                       # Repo-local Codex skills
├── docs/
│   ├── PROJECT_LOGIC.md
│   ├── PROJECT_STRUCTURE.md
│   ├── architecture/SECURITY_MODEL.md
│   ├── database/SCHEMA.md
│   ├── api/SYNC_API.md
│   ├── flows/REGISTRATION.md
│   ├── flows/TICKETS.md
│   ├── flows/PASSWORD_CHANGE.md
│   ├── telegram/USER_MENU.md
│   ├── telegram/ADMIN_MENU.md
│   ├── telegram/TICKET_FLOWS.md
│   └── deployment/VPS.md
├── deploy/vps/                          # Debian release/update/backup/rollback toolkit
├── auth-api/                             # Framework-independent contracts
├── auth-backend/                         # Telegram bot + HTTP sync backend
├── auth-neoforge-server/                 # Dedicated-server NeoForge mod
├── auth-neoforge-protocol/               # Safe client/server payloads and proof/fingerprint primitives
└── auth-neoforge-client/                 # Client NeoForge mod
```

## Package guidance

Use `dev.example.mcauth` only as a placeholder. Replace it with the final reverse-domain package before implementation becomes substantial.

Keep dependencies pointing inward: NeoForge and backend modules may depend on `auth-api`; `auth-api` must not depend on them. The backend and Minecraft server must communicate through versioned DTOs/events rather than sharing database files.

## Backend Phase 2 components

`auth-backend` separates Telegram transport (`TelegramApiClient`), update routing (`TelegramUpdateRouter`), keyboards, application services, SQLite migrations, and `BackendHttpServer`. The built-in HTTP server delegates to `SyncApiService`, `SyncEventService`, and `RemoteCommandService`; it does not contain account/ticket SQL business logic.

Telegram localization lives under `dev.example.mcauth.backend.telegram.i18n`, while UTF-8 translations live in `auth-backend/src/main/resources/i18n`. Handlers operate on stable `BotAction` and callback identifiers. Locale resolution and persistence remain backend concerns and do not enter `auth-api`.

Version 0.3 introduced `ModeratorPendingActionService` in `auth-backend`; version 0.4.1 retains it as the owner of expiring moderator private-input state persisted by migration V004. Telegram group cards remain projections, and no Telegram interaction state crosses into `auth-api` or either NeoForge module.

`auth-api` contains framework-independent sync-event and remote-command enums/records. It remains free of Telegram, HTTP, JDBC, SQLite, Minecraft, and NeoForge dependencies.

## Remote command additions

```text
docs/
├── api/REMOTE_COMMAND_API.md
└── architecture/REMOTE_COMMAND_SECURITY.md

auth-api/src/main/java/.../api/
├── RemoteCommand.java
├── RemoteCommandType.java
└── RemoteCommandStatus.java

auth-backend/src/main/java/.../backend/
└── RemoteCommandService.java

auth-neoforge-server/src/main/java/.../server/remote/
├── RemoteCommandPoller.java
├── RemoteCommandDispatcher.java
├── RemoteCommandHandler.java
└── handlers/
    ├── ForceAuthSyncHandler.java
    ├── AuthStatusHandler.java
    └── ResyncPlayerHandler.java
```

The backend owns command creation, authorization, persistence, expiry, and result collection. The Minecraft server owns execution. `auth-api` contains only framework-independent command DTOs/enums. No module should expose a general-purpose remote console.

## Device fingerprint and ban ownership

`auth-neoforge-protocol` owns only the versioned fingerprint payload and allowlisted signal types. `auth-neoforge-client` owns OS collection, normalization, local digesting, opt-in weak signals, and process caching. `auth-neoforge-server` owns login gating, asynchronous backend evaluation, and the minimal offline device-ban cache. `auth-backend` owns HMAC tokenization, device resolution/history, authoritative bans/exemptions/audit, Telegram moderation UI, and sync-event production. No raw hardware identifier belongs outside the client collection boundary.
