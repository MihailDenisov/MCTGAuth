# Security Model (Draft)

Security decisions that must be resolved before production implementation:

1. Password verifier algorithm and parameters (prefer Argon2id; BCrypt is an acceptable fallback when configured correctly).
2. Replay-resistant client/server login protocol. Do not treat a reusable transmitted hash as a secure password proof.
3. Credential storage on the client. Abstract it now; prefer OS-backed secure storage where feasible. Do not use ordinary TOML for plaintext secrets.
4. HTTPS-only backend synchronization and authenticated server requests.
5. Moderator authorization based on immutable Telegram numeric IDs, not usernames or group membership alone.
6. Rate limits for password rotation, ticket spam, login attempts, and administrative actions.
7. Secrets only from environment/secret files with restrictive permissions; never Git.
8. Redaction: plaintext passwords, bot tokens, API tokens, and authentication proofs must never enter logs/audit records.
9. Minimize personal data replicated to Minecraft.
10. Define licensed/online-mode and offline-mode identity semantics explicitly before relying on UUIDs.
11. Remote administration is typed and allowlisted. Never forward arbitrary Telegram/backend text to shell execution, RCON, `Runtime.exec`, `ProcessBuilder`, or the Minecraft command dispatcher.
12. Remote commands require backend authorization, target-server validation, expiry/TTL, replay/duplicate protection, structured payload validation, result auditing, and per-command permission checks.
13. Button visibility is not authorization. The backend verifies the acting moderator's numeric Telegram ID and permission for every command request.

## Implemented Phase 2 controls

- The embedded HTTP listener rejects non-loopback bind addresses. Public TLS must terminate at a reverse proxy.
- Sync and remote-command endpoints require a bearer token of at least 32 characters and compare it in constant time where practical. Authorization headers are never logged.
- Remote commands use an enum allowlist, exact payload schemas, stable UUIDs, target-server validation, TTLs, idempotent lifecycle updates, permission checks, and audit records.
- Password generation uses `SecureRandom`; Argon2id verifiers and credential versions are stored, while plaintext values are not persisted or logged.
- Password changes enforce a ten-minute minimum interval and five changes per rolling 24 hours.
- Sync payloads exclude Telegram IDs and support/application personal data.
- VPS deployment packages contain only application launchers, libraries, and non-secret release metadata. Runtime secrets, SQLite data, logs, and backups remain outside immutable release directories.
- Management scripts use fixed absolute paths, validate archive entries and release names, reject symbolic links in releases, serialize state-changing operations with `flock`, and never evaluate the environment file as shell code.
- Updates take a verified SQLite online backup before activation and use an atomic `current` symlink switch. Application rollback never silently restores the database; schema compatibility is checked explicitly.

The Minecraft challenge-response protocol and client credential storage remain unresolved and are intentionally outside this backend phase.
