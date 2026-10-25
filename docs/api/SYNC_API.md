# Minecraft Synchronization API

The Minecraft server is the client. It initiates outbound HTTPS connections to the VPS backend.

Implemented endpoints:

- `GET /api/v1/server/events?server_id=<server-id>` — return up to 100 unacknowledged events.
- `POST /api/v1/server/events/{id}/ack` — acknowledge durable local application.
- `POST /api/v1/server/device-observations` — resolve a post-proof, versioned digest set and return an opaque device ID, confidence, and policy decision.
- `GET /health` — local/backend health check.
- `GET /version` — public-safe build and database-schema metadata for local deployment diagnostics.

The ACK endpoint also requires `server_id` as a query parameter. Synchronization and device-observation endpoints require `Authorization: Bearer <AUTH_API_TOKEN>`. Repeated GET and ACK requests are safe; acknowledged events remain stored and are no longer returned by the pending query. Device observations accept only allowlisted unique 32-byte client digests, require a strong signal while enabled, and never accept raw hardware values.

The application accepts only its configured `SERVER_ID`. The embedded Java server is restricted to a loopback bind address and should be exposed only through HTTPS termination such as Caddy.

`/health` and `/version` do not require the synchronization bearer token, but they expose only allowlisted non-secret fields and remain protected by the loopback-only listener. The version response contains the application name/version, commit, build time, and current database schema version.

Event payloads contain account ID, nickname, enabled/license state, credential version, and verifier only when required. They never contain age, experience, ticket conversation, violation, or Telegram identity data.

Requirements: TLS, server authentication token or stronger signed-request scheme, request timeouts, bounded retries with backoff, event IDs, schema versioning, idempotent handlers, no plaintext passwords in event payloads, and safe logging.

Do not expose a custom inbound Minecraft/Pterodactyl API solely for this integration unless the architecture changes deliberately.

## Relationship to remote commands

Synchronization events and remote commands are separate concepts even if they share transport/authentication infrastructure. Sync events represent authoritative state changes that should eventually be applied. Remote commands represent administrator-requested operations with explicit status and expiry. Do not overload one queue to silently behave as the other. See `REMOTE_COMMAND_API.md`.
