# MCTGAuth Server Deployment

Required runtime: Java 21, Minecraft 1.21.1, NeoForge 21.1.244 or a compatible 21.1.x release.

```text
server/
├── mods/mctgauth-server-0.6.4.jar
├── config/mctgauth-server.toml
└── mctgauth/
    ├── accounts.json
    └── accounts.json.bak
```

Default Pterodactyl workflow:

1. Install `mctgauth-server-0.6.4.jar` in `mods/`.
2. Start the server once so NeoForge creates `config/mctgauth-server.toml`.
3. Stop the server.
4. Edit `config/mctgauth-server.toml`.
5. Set `backend.baseUrl` to the HTTPS auth-backend URL.
6. Set `backend.serverId` to the same server identifier configured in auth-backend.
7. Set `backend.apiToken` to the same token as backend `AUTH_API_TOKEN` (32-512 non-whitespace characters).
8. Start the server.

The TOML is a dedicated-server-only, non-synchronized config and must be protected like any other secret file. `MCTGAUTH_API_TOKEN` remains available as an optional advanced environment override and takes priority over `backend.apiToken`; it is not required for normal hosting. No inbound allocation is required.

If neither token source is configured, MCTGAuth logs a redacted warning and disables backend synchronization without disabling the local authentication cache. Invalid enabled backend configuration is rejected without disabling TLS verification or exposing the token.

The server creates an empty schema-version-1 cache on first run. Each mutation is written to a flushed temporary file and atomically moved into place when supported, with replace fallback and backup. Existing corrupt data is preserved and authentication fails closed. Backend outages degrade synchronization only; previously synchronized enabled accounts authenticate locally.

The server mod does not contain SQLite JDBC or Jackson. Run `./gradlew :auth-neoforge-server:verifyServerJar` before deployment to enforce that artifact boundary.
