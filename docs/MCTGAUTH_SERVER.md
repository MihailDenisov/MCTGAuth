# MCTGAuth Server

The dedicated-server mod mirrors only approved account names, Argon2id verifier material, credential versions, enabled/licensed state, sync-event IDs, and typed remote-command outcomes. The backend remains authoritative.

The schema-version-2 cache is `<server>/mctgauth/accounts.json`. It adds opaque backend device IDs, client digest maps, and known device-ban IDs; schema 1 migrates in place. An immutable snapshot serves authentication reads; a serialized mutation lock copies state, validates it, flushes `accounts.json.tmp`, creates `accounts.json.bak`, atomically replaces the live file where supported, and only then exposes the new snapshot. Sync ACK happens afterward.

The mod uses Minecraft's Gson and JDK HTTP/crypto facilities. The server JAR deliberately excludes SQLite JDBC, Jackson, and BouncyCastle to avoid module/package collisions in large modpacks.

Backend connection settings, including `backend.apiToken`, live in the dedicated-server-only `config/mctgauth-server.toml`. This common-config scope is not synchronized to Minecraft clients. The token is redacted in diagnostics and optional `MCTGAUTH_API_TOKEN` environment override takes priority. With no token, synchronization is degraded but local cached authentication remains available.

Version 0.4.2 logs one authentication result per decision with stable reason codes and logs every MCTGAuth terminal disconnect separately. Successful authentication is `INFO`, expected rejection is `WARN`, and internal authentication failure is `ERROR`. Log arguments are limited to player name, UUID, credential version, reason code, and non-sensitive exception type; password, verifier, nonce, salt, proof, and API token are never included.

Authentication currently begins immediately after the NeoForge player-login event. The server advertises `server_hello`, waits for `client_hello`, and then issues the challenge. Until success, strict quarantine blocks movement, interaction, inventory containers, item transfer, damage, chat, and commands. Moving authentication before normal play configuration remains a future protocol/lifecycle change.

Protocol 2 consumes a valid credential proof before sending its device observation to the authenticated backend. This prevents unauthenticated clients from polluting device history. Device evaluation runs on virtual-thread workers; completion is scheduled back onto the server thread before authentication success or disconnect. During backend outages, the cache denies known banned accounts/devices and applies the configured unknown-device policy.
