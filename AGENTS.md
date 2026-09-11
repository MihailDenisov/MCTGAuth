# Codex Repository Instructions

## Mission
Build a secure, maintainable Java 21 authentication/ticket system connecting a Telegram bot on a VPS with Minecraft 1.21.1 NeoForge client/server mods.

## Read first
Before architectural changes, read `docs/PROJECT_LOGIC.md`, `docs/PROJECT_STRUCTURE.md`, and `docs/architecture/SECURITY_MODEL.md`.

## Hard boundaries
- `auth-api` is framework-independent and must not import Telegram, NeoForge, Minecraft, SQLite, or HTTP framework classes.
- `auth-backend` owns Telegram users, tickets, moderation, authoritative account ownership, audit history, and sync-event production.
- `auth-neoforge-server` owns local login enforcement/cache and consumes backend sync events via outbound HTTPS.
- `auth-neoforge-client` owns UI/local credential handling and authentication protocol client logic.
- Existing synchronized Minecraft accounts must remain login-capable during backend/Telegram outages.
- Never log or commit plaintext passwords, Telegram bot tokens, API tokens, or reusable authentication proofs.
- Do not let ordinary users directly add arbitrary Minecraft nicknames; use an approval ticket. Manual bypass is moderator-only and audited.
- Telegram numeric IDs are authorization identities; usernames are display metadata.
- Ticket/admin Telegram messages are views; the database is the source of truth.
- Make state transitions and sync event handling idempotent.

## Implementation practices
- Java 21; Gradle; UTF-8; LF line endings.
- Prefer small interfaces and explicit domain types over framework leakage.
- Add tests for state transitions, authorization, idempotency, and security-sensitive validation before UI polish.
- Pin dependency/plugin versions; do not use dynamic `+` versions in production.
- For NeoForge changes, verify against the official 1.21.1 documentation/MDK rather than guessing APIs.

## Skills
Use repo-local skills under `.agents/skills/` when relevant:
- `project-architecture`: module ownership, dependency direction, and change placement.
- `security-review`: credentials, authentication protocol, secrets, authorization, and privacy checks.
- `gradle-neoforge`: Gradle/Java/NeoForge 1.21.1 setup and verification workflow.
- `ticket-workflows`: ticket state machines, moderator actions, audit, and Telegram interaction rules.
- Remote administration must use typed, allowlisted commands and dedicated handlers. Never pass Telegram/backend strings to a shell, RCON, `Runtime.exec`, `ProcessBuilder`, or Minecraft command dispatcher as arbitrary commands.
- Every remote command must have a stable ID, target `server_id`, creator Telegram ID, TTL/expiry, authorization check, idempotent execution semantics where possible, structured result, and audit trail.

Additional skill:
- `remote-command-channel`: safe design and implementation of backend-to-Minecraft administrative commands.
