# MCTGAuth Server

Dedicated-server-only NeoForge 1.21.1 mod. Required runtime is Java 21, Minecraft 1.21.1, and NeoForge 21.1.244 (or a compatible 21.1.x release). Install only `build/libs/mctgauth-server-0.6.5.jar` in the dedicated server's `mods/` directory; never distribute this server JAR to players.

The server stores synchronized Argon2id verifier records, opaque backend device IDs, client-side signal digests, known device bans, and idempotency metadata in `<server>/mctgauth/accounts.json` (schema 2). Writes use `accounts.json.tmp` and `accounts.json.bak`; protect the whole `mctgauth/` directory. The cache contains no plaintext password or raw hardware identifier. Existing synchronized accounts remain locally login-capable while the backend is unavailable, subject to known cached user/device bans.

```powershell
.\gradlew.bat :auth-neoforge-server:test
.\gradlew.bat :auth-neoforge-server:verifyServerJar
.\gradlew.bat :auth-neoforge-server:build
.\gradlew.bat :auth-neoforge-server:runServer
```

NeoForge creates the server-only `config/mctgauth-server.toml`. Configure the backend URL, server ID, and bearer token there:

```toml
[backend]
baseUrl = "https://auth.example.com"
serverId = "survival-main"
apiToken = "replace_with_the_same_32_plus_character_token_as_auth_backend"
pollIntervalSeconds = 10
connectTimeoutSeconds = 5
requestTimeoutSeconds = 15
# Development only; keep false in production.
allowInsecureHttpForDevelopment = false

[authentication]
timeoutSeconds = 60
maxAttempts = 3
disconnectPlayerWhenAccountDisabled = true

[sync]
batchSize = 100

[telemetry]
enabled = true
heartbeatIntervalSeconds = 20
maxQueuedPlayerEvents = 10000

[deviceFingerprint]
enabled = true
fingerprintVersion = 1
minimumStrongSignals = 1
# Default false preserves valid cached-account login for an entirely unknown device during outage.
failClosedWhenBackendUnavailable = false

[deviceBan]
enabled = true
```

The config uses NeoForge's non-synchronized common-config scope and is never sent to players. Protect it as a secret-bearing server file. `MCTGAUTH_API_TOKEN` is supported only as an optional advanced environment override and takes priority over `backend.apiToken`. If neither source contains a token, backend synchronization and remote commands are disabled while enabled accounts already committed to `mctgauth/accounts.json` remain login-capable. The backend URL must use HTTPS unless the explicit development flag is enabled; URLs containing credentials, queries, or fragments are rejected.

No inbound port is required: the server polls the backend, evaluates devices, and posts telemetry over authenticated outbound HTTPS for `server_id`. Player events are durably queued in `mctgauth/telemetry-events.json`; HTTP/device evaluation never runs on tick threads. Protocol 2 uses nonce-bound challenge proofs and mandatory fingerprint capability; plaintext passwords and raw hardware values are never sent. See [`docs/api/TELEMETRY_API.md`](../docs/api/TELEMETRY_API.md).

## Operator commands

Permission level 2 or higher is required:

* `/mcauth status` — backend/local health, last sync, account/player counts, and protocol version.
* `/mcauth sync` — request an immediate backend poll.
* `/mcauth player <name>` — inspect one locally cached account.
* `/mcauth resync <name>` — explicitly unsupported by the current backend API.
* `/mcauth reload` — unsupported; restart after editing configuration.

The backend's typed command queue currently supports force sync, auth status, and a resync request (the current server resync handler reports unsupported). Unknown or malformed commands fail closed.

## Authentication behavior and logs

Players are quarantined after login until the client completes authentication: movement, containers, interactions, item drops/pickups, damage, chat, commands, and dimension changes are blocked or terminated. The server advertises `server_hello` first and accepts `client_hello` before issuing a challenge. Authentication timeout is 60 seconds by default, with three attempts. When account disabling is synchronized, currently connected disabled players are disconnected when `disconnectPlayerWhenAccountDisabled=true`.

Server logs report redacted backend configuration, synchronization health, authentication start/success/rejection, and initialization errors. They never log API tokens, plaintext passwords, proofs, raw device values, or stored device tokens. Release 0.6.5 adds explicit device-ban and device-verification-unavailable failures on wire protocol 2.

For backend deployment and API details, see [`docs/NEOFORGE_SERVER_DEPLOYMENT.md`](../docs/NEOFORGE_SERVER_DEPLOYMENT.md), [`docs/MCTGAUTH_SERVER.md`](../docs/MCTGAUTH_SERVER.md), [`docs/api/SYNC_API.md`](../docs/api/SYNC_API.md), and [`docs/api/REMOTE_COMMAND_API.md`](../docs/api/REMOTE_COMMAND_API.md).
