# MCTGAuth

[Русская версия](README_RU.md)

MCTGAuth is a Java 21 authentication and support system for Minecraft 1.21.1 on NeoForge 21.1.244. A Telegram bot manages registration, account ownership, tickets, moderation, and password rotation; separate client and dedicated-server mods perform replay-resistant login without transmitting the plaintext password.

Current version: **0.4.2**.

## Architecture

```mermaid
flowchart TD
    U[Telegram user] --> B[auth-backend Telegram bot]
    B --> D[(Authoritative SQLite database)]
    D --> E[Versioned sync events]
    E -->|outbound authenticated HTTPS polling| S[MCTGAuth Server]
    S --> C[(Local atomic authentication cache)]
    P[MCTGAuth Client] <-->|versioned challenge-response| S
```

- `auth-backend` is authoritative for Telegram identities, moderators, tickets, Minecraft-account ownership, credentials, audit history, sync events, and typed remote commands.
- `auth-neoforge-server` polls the backend over outbound HTTPS, keeps the minimum local verifier cache, quarantines unauthenticated players, and validates login proofs.
- `auth-neoforge-client` stores the user-entered password locally and derives the nonce-bound proof off the render thread.
- Minecraft never calls the Telegram API and never reads the backend SQLite database.
- During a backend outage, management and new synchronization stop, but accounts already committed to the server cache remain login-capable.

The client password is intentionally stored as plaintext in its dedicated local JSON configuration. The backend and Minecraft server never store that plaintext. No component may log passwords, API tokens, Authorization headers, verifier material, or reusable proofs.

## Modules

| Module | Responsibility | Documentation |
| --- | --- | --- |
| `auth-api` | Framework-independent domain and synchronization types. | [Project structure](docs/PROJECT_STRUCTURE.md) |
| `auth-backend` | Telegram bot, SQLite authority, moderation, audit, sync and health API. | [English](auth-backend/README.md) · [Russian](auth-backend/README_RU.md) |
| `auth-neoforge-protocol` | Safe wire payloads, compatibility constants, verifier parsing, and proof primitives. | [Protocol](docs/AUTH_PROTOCOL.md) |
| `auth-neoforge-server` | Dedicated-server enforcement, cache, backend polling, and operator commands. | [English](auth-neoforge-server/README.md) · [Russian](auth-neoforge-server/README_RU.md) |
| `auth-neoforge-client` | Settings UI, local credential storage, and automatic authentication. | [English](auth-neoforge-client/README.md) · [Russian](auth-neoforge-client/README_RU.md) |

## Authentication flow

1. A user submits a registration or additional-account ticket in Telegram.
2. An authorized moderator, identified by numeric Telegram ID, approves it.
3. The backend creates an Argon2id verifier and a versioned sync event. The generated plaintext is delivered only to the user.
4. MCTGAuth Server durably applies the event to `mctgauth/accounts.json`, then acknowledges it.
5. Client and server negotiate protocol version 1. The server issues a one-use session and nonce; the client derives a key locally and returns a context-bound HMAC proof.
6. The server consumes the challenge, validates identity and credential version, and releases the player only after success.

See the [authentication protocol](docs/AUTH_PROTOCOL.md) and [end-to-end flow](docs/MCTGAUTH_AUTH_FLOW.md).

## Requirements and development

- 64-bit JDK 21
- Minecraft 1.21.1
- NeoForge 21.1.244
- Git and the included Gradle Wrapper; global Gradle is not required

```powershell
java -version
.\gradlew.bat clean test
.\gradlew.bat build
```

On Linux/macOS use `./gradlew`. For backend development, copy the examples from `.env.example` into process environment variables; the application does not load `.env` automatically. Never commit real values.

## Release artifacts

```text
auth-backend/build/distributions/auth-backend-0.4.2.tar.gz
auth-neoforge-server/build/libs/mctgauth-server-0.4.2.jar
auth-neoforge-client/build/libs/mctgauth-client-0.4.2.jar
```

```powershell
.\gradlew.bat :auth-backend:deploymentArchive :auth-neoforge-server:build :auth-neoforge-client:build
```

Generated binaries belong in release assets, not in the source commit.

## Documentation

- [Documentation index](docs/README.md) · [Russian index](docs/README_RU.md)
- [Architecture and ownership](docs/PROJECT_LOGIC.md)
- [Security model](docs/architecture/SECURITY_MODEL.md)
- [Backend](auth-backend/README.md)
- [Production deployment](docs/deployment/DEPLOYMENT.md)
- [Troubleshooting](docs/troubleshooting/TROUBLESHOOTING.md)

## Current limitations

- The backend listener is loopback-only; production HTTPS must terminate at a trusted reverse proxy.
- `/mcauth resync <name>` and `/mcauth reload` are explicit unsupported stubs. The typed `RESYNC_PLAYER` remote handler is also currently non-operational because the sync API has no single-account snapshot endpoint.
- No automatic reconnect loop runs after a terminal client authentication failure; reconnect manually after fixing the cause.
- Licensed/online-mode versus offline-mode UUID policy is not yet finalized for deployments that depend on UUID identity changes.
