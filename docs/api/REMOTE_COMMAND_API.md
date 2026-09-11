# Remote Command API

## Purpose

Provide a safe backend-to-Minecraft administrative command channel without opening a custom inbound Pterodactyl port and without exposing RCON or arbitrary command execution.

Minecraft initiates all network traffic over authenticated HTTPS.

## Command envelope

Conceptual payload:

```json
{
  "id": "cmd_501",
  "serverId": "survival-main",
  "type": "RESYNC_PLAYER",
  "version": 1,
  "createdAt": "2026-09-11T12:10:00Z",
  "expiresAt": "2026-09-11T12:15:00Z",
  "payload": {
    "accountId": 55
  }
}
```

Do not add a generic `commandText` field.

## Implemented backend endpoints

```text
GET  /api/v1/server/commands?server_id=<server-id>
POST /api/v1/server/commands/{commandId}/started?server_id=<server-id>
POST /api/v1/server/commands/{commandId}/result?server_id=<server-id>
```

All endpoints require the bearer API token. The result request accepts `status` (`SUCCESS` or `FAILED`), a structured `result` object, and optional safe `errorMessage`.

## Polling behavior

1. Server authenticates to the backend.
2. Server requests pending non-expired commands for its own `server_id`.
3. Backend returns a small ordered batch or long-poll response.
4. Server validates command ID, type, version, expiry, and payload.
5. Server marks/acknowledges start if the protocol uses a `RUNNING` state.
6. A local allowlisted handler executes the command.
7. Server posts a structured result.
8. Backend records and audits completion. Proactive Telegram result notifications are a future enhancement.

A duplicate delivery must not accidentally execute a destructive command twice. Track processed command IDs locally when required.

## Enabled command types

- `FORCE_AUTH_SYNC`: trigger immediate auth synchronization processing.
- `AUTH_STATUS`: return local auth/sync diagnostic summary.
- `RESYNC_PLAYER`: request synchronization for one Minecraft account.

The shared enum reserves the following future types, but the backend rejects attempts to create them in this phase:

- `RELOAD_AUTH_CACHE`
- `ENABLE_PLAYER_AUTH`: enable authentication for a known account after authorization.
- `DISABLE_PLAYER_AUTH`: disable authentication for a known account after authorization.

Each type defines its own payload schema and permission.

## Result envelope

Conceptual result:

```json
{
  "status": "SUCCESS",
  "completedAt": "2026-09-11T12:10:02Z",
  "result": {
    "receivedEvents": 4,
    "appliedEvents": 4,
    "failedEvents": 0
  }
}
```

Errors should be safe for logs/admin display and must not expose secrets.

## Expiry

Operational commands should have a short TTL appropriate to their purpose. If a command is not picked up before `expiresAt`, the backend marks it `EXPIRED`; the Minecraft server must reject already-expired commands even if accidentally delivered later.
