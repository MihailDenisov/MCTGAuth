# Minecraft Authentication Architecture

```text
Telegram user -> auth-backend -> HTTPS sync events -> MCTGAuth server mod
                                                      | atomic JSON cache
                                                      | challenge/response
                                                      v
                                               MCTGAuth client mod -> automatic auth/settings UI
```

The backend is authoritative for account ownership, approval, credentials, enabled state, audit, and sync-event creation. `auth-neoforge-server` atomically persists each account mutation with its processed event ID and acknowledges only after the file replacement succeeds. `auth-neoforge-client` never communicates with Telegram or the backend.

The Minecraft host initiates all backend traffic. A backend outage degrades management and synchronization, but does not prevent authentication of already-synchronized enabled accounts. A corrupt existing local cache is preserved and authentication fails closed.

`auth-neoforge-protocol` is a shared build module whose output is folded into both runtime JARs so its Minecraft payload classes share the runtime mod's module boundary. It owns stable payload definitions, strict limits, Argon2id parameter bounds, identity normalization, and HMAC proof construction. It contains no policy or secrets.

Unauthenticated players enter a temporary quarantine: their position and velocity are held, open containers are closed, interactions, drops, pickup, chat, commands, outgoing/incoming damage, and dimension changes are blocked or terminated. No inventory or game mode is permanently modified.
