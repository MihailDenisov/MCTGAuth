# MCTGAuth Server

The dedicated-server mod mirrors only approved account names, Argon2id verifier material, credential versions, enabled/licensed state, sync-event IDs, and typed remote-command outcomes. The backend remains authoritative.

The schema-version-1 cache is `<server>/mctgauth/accounts.json`. An immutable snapshot serves authentication reads; a serialized mutation lock copies state, validates it, flushes `accounts.json.tmp`, creates `accounts.json.bak`, atomically replaces the live file where supported, and only then exposes the new snapshot. Sync ACK happens afterward.

The mod uses Minecraft's Gson and JDK HTTP/crypto facilities. The server JAR deliberately excludes SQLite JDBC, Jackson, and BouncyCastle to avoid module/package collisions in large modpacks.

Backend connection settings, including `backend.apiToken`, live in the dedicated-server-only `config/mctgauth-server.toml`. This common-config scope is not synchronized to Minecraft clients. The token is redacted in diagnostics and optional `MCTGAUTH_API_TOKEN` environment override takes priority. With no token, synchronization is degraded but local cached authentication remains available.

Version 0.4.1 logs one authentication result per decision with stable reason codes and logs every MCTGAuth terminal disconnect separately. Successful authentication is `INFO`, expected rejection is `WARN`, and internal authentication failure is `ERROR`. Log arguments are limited to player name, UUID, credential version, reason code, and non-sensitive exception type; password, verifier, nonce, salt, proof, and API token are never included.

Authentication currently begins immediately after the NeoForge player-login event. Until success, strict quarantine blocks movement, interaction, inventory containers, item transfer, damage, chat, and commands. Moving authentication before normal play configuration is a larger protocol/lifecycle change and remains a follow-up rather than part of the 0.4.1 logging/reconnect hotfix.
