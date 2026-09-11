# Minecraft Telegram Auth

Starter repository for a Minecraft 1.21.1 NeoForge authentication system integrated with a Telegram bot and ticket-based administration workflow.

## Status

Backend Phase 2 is implemented: button-oriented Telegram user/admin menus, registration and support tickets, attachment references and conversation history, multiple-account approval, password rotation, moderator workflows/search/statistics, ordered SQLite migrations, authenticated synchronization APIs, and a typed remote-command queue. The NeoForge authentication protocol and both runtime mod integrations remain scaffolded.

## Development environment

- Java: JDK 21 (64-bit)
- IDE: Visual Studio Code
- Build system: Gradle Wrapper
- Minecraft: 1.21.1
- Mod loader/API: NeoForge
- Backend host: separate European VPS
- Backend persistence: SQLite initially
- Minecraft server persistence: local SQLite/cache, independent of backend availability
- Telegram update transport: long polling initially

NeoForge 1.21.1 requires Java 21. VS Code is usable even though NeoForge's documentation primarily highlights IDEs with integrated Gradle support.

## First setup

1. Install a 64-bit JDK 21.
2. Install the recommended VS Code extensions from `.vscode/extensions.json`.
3. Start from the official NeoForge 1.21.1 MDK and pin the chosen NeoForge version in `gradle.properties`.
4. Configure the two NeoForge module build files using the MDK as the source of truth.
5. Copy `.env.example` to a secure deployment environment. Never commit real tokens.
6. Run `./gradlew build`.

## Run the backend

Create a bot with BotFather, add it to a private administration group, and obtain the group's numeric chat ID. Supply secrets through environment variables; the application does not read or commit a `.env` file.

PowerShell example:

```powershell
$env:TELEGRAM_BOT_TOKEN = '<bot-token>'
$env:TELEGRAM_ADMIN_CHAT_ID = '-1000000000000'
$env:MODERATOR_TELEGRAM_IDS = '123456789,987654321'
$env:AUTH_DATABASE_PATH = '.\data\auth-backend.db'
$env:HOW_TO_PLAY_URL = 'https://t.me/example/1'
$env:HTTP_BIND_ADDRESS = '127.0.0.1'
$env:HTTP_PORT = '8080'
$env:AUTH_API_TOKEN = '<at-least-32-random-characters>'
$env:SERVER_ID = 'survival-main'
$env:ADMIN_LOCALE = 'ru'
$env:DEFAULT_USER_LOCALE = 'en'
.\gradlew.bat :auth-backend:run
```

Users send `/start` in a private chat, select English or Russian, and can later change the choice in Settings. Submitted tickets are persisted before the bot posts their admin cards. `ADMIN_LOCALE` independently controls the shared administration group. Only Telegram numeric IDs in `MODERATOR_TELEGRAM_IDS` can approve or reject a registration.

Telegram menu documentation is under `docs/telegram/`. HTTP API details are in `docs/api/SYNC_API.md` and `docs/api/REMOTE_COMMAND_API.md`.

## Modules

### `auth-api`
Pure Java shared contracts and domain types. It must not depend on Telegram, Minecraft, NeoForge, SQLite, or an HTTP framework. Put stable DTOs, enums, service interfaces, event schemas, and validation contracts here.

### `auth-backend`
Standalone Java 21 application deployed to the VPS. Owns Telegram interaction, ticket workflows, moderator actions, the authoritative user/account registry, audit history, backend SQLite database, and the HTTPS synchronization API consumed by Minecraft.

See [`auth-backend/README.md`](auth-backend/README.md) for local and Debian VPS installation instructions. A [Russian version](auth-backend/README_RU.md) is also available.

Production release management scripts and their Russian operator guide are under [`deploy/vps/`](deploy/vps/README.md).

### `auth-neoforge-server`
Server-side NeoForge mod. Owns local authentication state, local credential verifier cache, whitelist synchronization, authentication timeout/restrictions, client-server authentication protocol, and outbound synchronization with `auth-backend`. Existing registered players must still be able to authenticate when the VPS is unavailable.

### `auth-neoforge-client`
Client-side NeoForge mod. Owns password entry/settings UI, local credential storage abstraction, and the client side of the authentication protocol. Do not store plaintext passwords in ordinary NeoForge TOML configuration.

## Documentation

Start with `docs/PROJECT_LOGIC.md` and `docs/PROJECT_STRUCTURE.md`. `docs/database/SCHEMA.md` defines the intended data model; `docs/flows/` contains the major state machines; `docs/api/SYNC_API.md` defines the backend-to-Minecraft synchronization boundary.

## Codex

`AGENTS.md` contains durable repository rules. Repo-local skills are stored under `.agents/skills/` so implementation sessions can load detailed workflows only when relevant.

## Remote administration channel

The administrative Telegram interface may enqueue a restricted set of typed remote commands for Minecraft servers, for example forcing an authentication sync, requesting sync status, or resynchronizing one player. The backend never opens an interactive shell or forwards arbitrary command text.

Minecraft continues to initiate all communication: the server polls the backend for pending commands over authenticated HTTPS, executes only locally registered handlers, and posts a structured result. See `docs/api/REMOTE_COMMAND_API.md` and `docs/architecture/REMOTE_COMMAND_SECURITY.md`.
