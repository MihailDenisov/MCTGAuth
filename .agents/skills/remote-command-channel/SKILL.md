---
name: remote-command-channel
description: Use when implementing Telegram-triggered server management, remote command queues, sync forcing, player resync, server status, or command authorization.
---
# Remote Command Channel

Read `docs/api/REMOTE_COMMAND_API.md`, `docs/architecture/REMOTE_COMMAND_SECURITY.md`, and `docs/PROJECT_LOGIC.md`.

Implement commands as typed allowlisted operations with dedicated payload validators and server-side handlers. Never create a generic command-text endpoint and never forward untrusted text to shell/RCON/Minecraft command execution.

Before coding a new command define: command type/version, required moderator permission, target `server_id`, payload schema, TTL, idempotency/replay behavior, safe result schema, audit events, and failure behavior.

Test unauthorized actors, wrong server ID, unknown type/version, malformed payload, expired command, duplicate delivery, backend retry, handler failure, and secret redaction.
