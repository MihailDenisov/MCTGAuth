# MCTGAuth Client

Client-only NeoForge 1.21.1 mod required by an MCTGAuth Server. Required runtime is Java 21, Minecraft 1.21.1, and NeoForge 21.1.244 (or a compatible 1.21.x release). Install only `build/libs/mctgauth-client-0.6.5.jar` in the player's `mods/` directory.

The client stores the Telegram-issued password in `.minecraft/config/mctgauth-client.json` as plaintext by product decision. Protect the Minecraft configuration folder and never share it. This is the only approved plaintext-password location: the password is never sent to the backend, included in diagnostics, or transmitted over the Minecraft protocol. Argon2id derivation runs off the render thread and only a nonce-bound HMAC-SHA256 proof is sent.

## Settings and authentication

Open the settings screen from the title-screen **MCTGAuth** button or **Mods → MCTGAuth Client → Config**. It supports entering, changing, or clearing the password, **Show Password**, and **Automatic Authentication**. Automatic authentication means “respond automatically after an MCTGAuth-enabled server requests authentication”; it never starts authentication on an unrelated connection. If the saved value is rejected, the client asks for a replacement. Clearing the value does not change the authoritative backend account password.

MCTGAuth Client may remain installed while playing singleplayer or connecting to servers that do not use MCTGAuth. Such connections stay passive: no authentication timer, payload, password UI, disconnect override, reconnect work, or Argon2id derivation starts. In particular, no password-derived proof or other authentication material is sent to an unrelated server. Authentication activates only after the current remote server advertises a valid `mctgauth:server_hello` capability.

The server and client negotiate protocol version 2. After activation, the authentication worker collects only enabled device signals and sends type-bound SHA-256 digests; strong signals default on while weak GPU/MAC signals default off. Raw identifiers are not transmitted or logged. A missing client mod, protocol mismatch, invalid credentials, device ban, too many attempts, timeout, disabled/unsynchronized account, or internal error causes the corresponding server disconnect/failure message. Existing server-side cached accounts and known devices support the configured backend-outage policy.

## Build and run

```powershell
.\gradlew.bat :auth-neoforge-client:test
.\gradlew.bat :auth-neoforge-client:build
.\gradlew.bat :auth-neoforge-client:runClient
```

For installation and the distinction between local password storage and Telegram account management, see [`docs/NEOFORGE_CLIENT_INSTALLATION.md`](../docs/NEOFORGE_CLIENT_INSTALLATION.md), [`docs/MCTGAUTH_CLIENT.md`](../docs/MCTGAUTH_CLIENT.md), and [`docs/AUTH_PROTOCOL.md`](../docs/AUTH_PROTOCOL.md). AutoModPack is an optional distribution convenience; it does not replace installing the required client mod or protecting the local configuration file.
