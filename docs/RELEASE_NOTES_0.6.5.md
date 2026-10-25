# MCTGAuth 0.6.5

[Russian version](RELEASE_NOTES_0.6.5_RU.md)

MCTGAuth 0.6.5 introduces privacy-conscious device recognition and moderator-controlled ban enforcement. A device is a many-to-many security observation: one user may use several devices and one device may legitimately serve several users. A match is evidence for review, never proof that users are the same person, and never creates a ban by itself.

The client collects enabled identifiers only after an MCTGAuth protocol handshake and on its authentication worker. Strong signals (`SYSTEM_UUID`, `MACHINE_ID`, `MOTHERBOARD_UUID`, `DISK_ID`) are enabled by default. Weak `GPU` and `MAC_ADDRESS` signals are disabled by default and are not queried while disabled. Values are normalized and SHA-256 digested locally; raw values are neither transmitted nor logged. The backend applies HMAC-SHA-256 with the separately managed `DEVICE_FINGERPRINT_SECRET` and persists only those keyed tokens.

The resolver uses centralized weights and requires at least one matching strong signal. Strong stable signals can preserve continuity when a disk changes; weak-only matches never resolve a device or expand a linked ban. First/last seen timestamps and login counts are maintained for device↔user and device↔account relationships.

Moderators can preview impact, ban only a user, explicitly include users sharing a target user's directly observed devices, ban a device, remove an individual or whole linked-ban group, and manage shared-PC exemptions. Linked propagation is exactly one hop and non-recursive. Explicit device bans remain blocked regardless of linked-ban exemptions. Ban records remain as inactive audit history after unban, and ordinary account `enabled` state is independent of ban state.

Known user and device bans are synchronized into the server cache and remain enforceable while the backend is unavailable. An entirely unknown device is allowed by default during an outage to preserve cached-account availability; operators can enable fail-closed behavior with `deviceFingerprint.failClosedWhenBackendUnavailable` in the server configuration.

Automatic observation cleanup is deferred in 0.6.5. The documented retention target is 365 days; operators must retain records referenced by active bans, exemptions, or audit history.

This release requires coordinated deployment:

- application version `0.6.5`;
- authentication protocol `2` (0.6.4 clients are rejected);
- backend schema `8` via `V008__device_fingerprints_and_bans.sql`;
- independent fingerprint format version `1`.

See [the 0.6.4 to 0.6.5 upgrade guide](MIGRATION_0.6.4_TO_0.6.5.md).
