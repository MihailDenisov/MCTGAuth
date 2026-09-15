# Changelog

All notable changes to MCTGAuth are documented here. Historical release details remain in `docs/RELEASE_NOTES_*.md`.

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
