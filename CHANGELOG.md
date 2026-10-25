# Changelog

## 0.6.5 - 2026-10-25

### Added

- Versioned client device fingerprints with strong identifiers enabled by default, optional weak GPU/MAC signals, local SHA-256 digests, and backend HMAC tokenization using an external secret.
- Many-to-many device, Telegram-user, and Minecraft-account history with confidence-based continuity across ordinary hardware changes.
- Explicit moderator user, direct linked-user, and device bans; impact preview; auditable individual/group unban; shared-PC exemptions; and EN/RU Telegram controls.
- Offline enforcement for known banned users/devices and an explicit configurable policy for unknown devices during backend outages.

### Changed

- Authentication protocol is now 2 and requires fingerprint capability, preventing legacy clients from bypassing device enforcement.
- Backend database schema is now 8 through `V008__device_fingerprints_and_bans.sql`.
- Client and server artifacts must be upgraded together; 0.6.4 clients are intentionally rejected.

### Security and privacy

- Raw hardware identifiers are normalized and hashed locally, never transmitted or stored; backend persistence contains separately keyed HMAC tokens only.
- Device matches never create a ban automatically. Linked propagation is moderator-confirmed, direct-only, non-recursive, and respects explicit exemptions.
- Weak signals cannot resolve a device by themselves. GPU and MAC collection remain disabled unless explicitly enabled.

All notable changes to MCTGAuth are documented here. Historical release details remain in `docs/RELEASE_NOTES_*.md`.

## 0.6.4 - 2026-10-14

### Added

- Optional Telegram member tags sourced from a user-selected, enabled Minecraft account in one explicitly configured chat; the default remains No tag.
- Permission-controlled administrator reconciliation with structured processed/updated/cleared/skipped/not-member/failed statistics.
- Optional, public registration-rejection reasons stored transactionally with the terminal ticket decision.
- Localized authentication failures for missing accounts, true synchronization gaps, disabled accounts, invalid/outdated credentials, timeout, verification/session failures, protocol mismatch, and internal errors.
- Structured `RESYNC_PLAYER` failure reasons, including UUID-based `MINECRAFT_NAME_MISMATCH` diagnostics without credential data.

### Security and compatibility

- Chat-tag callbacks revalidate numeric Telegram ownership and source tags only from authoritative Minecraft account names; disabled/deleted selections are cleared.
- Telegram chat administrators/owners are excluded from game tags, and temporary Telegram API failures never roll back the saved preference.
- Database schema 7 adds `users.chat_tag_account_id` with `ON DELETE SET NULL` and persisted registration-rejection input state.
- Authentication wire protocol remains version 1, but the precise failure mapping and `RESYNC_PLAYER` handler require coordinated 0.6.4 client, server, and backend deployment.

## 0.6.3 - 2026-09-18

- Published a backend-only archive based on the 0.6.2 source line for deployment version sequencing.
- Included the committed-persistence heartbeat correction already documented for 0.6.2; no additional feature, protocol, or database migration was introduced.
- Database schema remained version 6 and authentication wire protocol remained version 1. No 0.6.3 Minecraft client/server artifacts were required.

## 0.6.2 - 2026-09-18

### Fixed

- Server Information **Back** now always returns to the normal user menu, including for moderators.
- Server Information submenu actions now use consistent emoji and explicit locale-independent callbacks.
- Dedicated-server telemetry now keeps recurring heartbeats alive after individual task failures and starts only after the server-ready lifecycle event.
- Backend heartbeat upserts now advance existing `server_status` rows, preserve same-instance start time, replace newer-instance metadata, clear shutdown state, and acknowledge only committed persistence.

### Compatibility

- Database schema remains version 6; no migration is required.
- Authentication wire protocol remains version 1.
- Backend configuration and the Minecraft authentication cache remain compatible with 0.6.1.

## 0.6.1 - 2026-09-18

- Resolving or closing a support ticket now asks whether to add a public closing comment.
- Closing comments are stored transactionally in ticket history with moderator identity and visibility metadata.
- User notifications include the administrator comment when present; close-without-comment remains available.
- Conditional status updates prevent a second moderator from overwriting the original closure or comment.
- Schema 6 adds typed ticket-message metadata and persisted closing-comment input state.

## 0.6.0 - 2026-09-18

- Telegram-issued passwords render only the secret itself as escaped monospace text.
- Server Information now contains How to Play, optional downloads/maps, live status, and the current player list.
- Authenticated outbound telemetry adds heartbeats, per-process instance IDs, durable idempotent join/leave delivery, stale-state protection, crash finalization, and bounded shutdown flushing.
- Players receive owned-account 24-hour, 7-day, and all-time playtime; moderators receive paginated player totals and last-seen state.
- `/adm` groups ticket functions under Requests and adds Player Statistics.
- Audited Telegram bot blocking is independent from Minecraft account enablement.
- Schema 5 and full-reset tooling include server status, player sessions, telemetry idempotency, and the local event journal.

## 0.5.0 - 2026-09-15

### Added

- UTF-8 YAML configuration for localized How to Play text, an optional registration subscription gate, and optional server-map and modpack links.
- Telegram `getChatMember` subscription verification with localized retry behavior and no registration state created before a successful check.
- Complete before/after audit details for administrative account activation and deactivation.
- An explicit, backup-first `reset-database.sh` operation that clears backend identity data and the Minecraft authentication cache together.
- Regression coverage for feature configuration, Telegram menus and subscription behavior, synchronized account disable, persistence-before-acknowledgement, and full-reset safety.

### Changed

- Account synchronization persists cache changes before acknowledging events.
- Connected players are disconnected when an account-disable event is committed and `disconnectPlayerWhenAccountDisabled=true`.
- Telegram callbacks reject stale or unavailable feature actions safely.

### Compatibility

- Authentication wire protocol remains version 1.
- Database schema remains version 4; no database migration or cache-format migration is required from 0.4.2.
- Disabled optional Telegram features preserve the previous menu behavior.

## 0.4.2 - 2026-09-12

- Added capability-driven passive client activation for singleplayer and unsupported servers.
- Hardened asynchronous authentication lifecycle handling and compatibility behavior.

## 0.4.1 - 2026-09-12

- Added fail-closed authentication lifecycle handling, Telegram account workflows, and production deployment tooling.
