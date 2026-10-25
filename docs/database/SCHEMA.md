# Database Model

This is a logical schema, not a migration file yet.

## `users`
Telegram identity: `telegram_id` PK, username snapshot, account status, persisted `locale` (`en` or `ru`), dedicated `bot_blocked` moderation state, and created/updated timestamps. Bot blocking does not change `minecraft_accounts.enabled`.

## `minecraft_accounts`
Account ID, owner Telegram ID, Minecraft UUID (nullable until known), unique normalized nickname, license flag, enabled flag, creation method (`REGISTRATION_TICKET`, `ADD_ACCOUNT_TICKET`, `ADMIN_MANUAL`), creator moderator ID where applicable, credential version metadata, timestamps.

Administrative access suspension reuses the single `enabled` flag. A moderator disable/enable transaction updates exactly one account, creates the corresponding `ACCOUNT_DISABLED` or `ACCOUNT_ENABLED` sync event, and records an audit row with the moderator numeric Telegram ID, account ID, nickname snapshot, optional reason, and UTC timestamp. Repeating the current state creates no redundant event or audit transition.

## `tickets`
Global numeric ticket ID, type, status, creator Telegram ID/username snapshot, optional Minecraft account ID, created/updated/closed timestamps, assigned moderator, admin chat/message identifiers.

## Type-specific ticket data
Separate tables such as `registration_ticket_data`, `problem_ticket_data`, `violation_ticket_data`, and `add_account_ticket_data`. Avoid a single table with dozens of nullable type-specific columns.

## `ticket_messages`
Ticket ID, sender type (`USER`, `MODERATOR`, `SYSTEM`), sender Telegram ID, text, logical message type (`MESSAGE` or `CLOSURE_COMMENT`), visibility (`PUBLIC` or `INTERNAL`), timestamp. Initial closure comments are public and user-visible.

## `ticket_attachments`
Ticket ID, Telegram `file_id`, `file_unique_id`, media type, optional caption, timestamp. Do not download media by default.

## `moderators`
Telegram ID PK, username snapshot, enabled flag, role/permissions (future), added timestamp.

## `audit_log`

Human-facing Telegram views render `occurred_at` in `Europe/Moscow`; the stored value remains an absolute UTC timestamp.
Append-oriented event log: event type, ticket/account references, actor Telegram ID/username snapshot, timestamp, structured details. Never put secrets/passwords in details.

## `sync_events`
Stable event ID, type, payload/version, created timestamp, delivery/ack status, attempt metadata. Consumers must apply events idempotently.

## Minecraft local DB
Keep only authentication-critical state: Minecraft identity, enabled flag, credential verifier/version, synchronization version/event ID, and minimal audit/diagnostic metadata. Do not copy registration age/experience or unrelated Telegram ticket content.

## `remote_commands`
Durable queue for typed administrative commands sent from the backend to a Minecraft server. Suggested fields: numeric/internal ID or UUID, `server_id`, `command_type`, versioned structured payload, status (`PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `EXPIRED`, `CANCELLED`), creator Telegram ID, created/expiry/picked-up/completed timestamps, structured result, safe error text, and attempt metadata.

Command creation/completion must also emit `audit_log` records. Command payload/result data must never contain bot tokens, backend API credentials, plaintext passwords, or reusable authentication proofs.

## `moderator_pending_actions`

One expiring private-input action per moderator numeric Telegram ID. `REPLY_TO_TICKET` and `TICKET_CLOSURE_COMMENT` store the ticket ID, optional target status, creation time, and expiry so an action started from an admin-group callback can be completed after a restart in that moderator's private chat. Completion and `/cancel` remove only the acting moderator's row.

## Migrations

`auth-backend` reads the ordered `db/migration/migrations.txt` manifest. Each `VNNN__name.sql` file runs in its own transaction and is recorded in `schema_migrations`; applied versions are never executed again. Startup fails with the migration filename if a required migration cannot be applied.

- `V001__initial.sql`: users, moderators, registration tickets/decisions, accounts, audit log, and sync events.
- `V002__backend_phase_2.sql`: affected-account/assignment fields, problem/violation/add-account data, messages, attachment references, password-change history, moderator permissions, and remote commands.
- `V003__user_locale.sql`: persisted English/Russian Telegram interface preference on `users`.
- `V004__telegram_workflow_fixes.sql`: nullable violation offender nickname and expiring per-moderator pending actions.
- `V005__telemetry_and_bot_blocking.sql`: server heartbeat state, idempotent player events, timestamp-based player sessions, and Telegram bot blocking.
- `V006__ticket_closure_comments.sql`: typed/visible ticket messages and persisted resolve/close comment input targets.
- `V007__chat_tags_and_registration_rejection.sql`: nullable authoritative-account chat-tag preferences, a managed-tag marker used for reliable clearing/reconciliation, and persisted registration-rejection reason input.
- `V008__device_fingerprints_and_bans.sql`: pseudonymous devices/signals, many-to-many user/account observations, auditable user/linked/device bans, account effects, shared-PC exemptions, and persisted ban-reason input.

`users.chat_tag_account_id` references `minecraft_accounts(id)` with `ON DELETE SET NULL`. `users.chat_tag_managed` distinguishes the untouched default from an explicit managed “No tag” preference, allowing a failed Telegram clear to be retried without storing arbitrary tag text. New and existing users default to no managed tag.

## Telemetry tables

`server_status` is keyed by `server_id` and stores instance/process timing, last heartbeat, player counts, and clean shutdown. `player_sessions` references the authoritative Minecraft account ID and uses nicknames only as snapshots. `telemetry_events` de-duplicates stable join/leave UUIDs. A new instance heartbeat finalizes open sessions from the prior instance at its last trustworthy heartbeat.

The current schema requires every Minecraft account to have an owning Telegram user. Ownerless technical accounts are deferred.

## Device and ban tables (schema 8)

`devices` contains only an opaque numeric ID, fingerprint format version, resolver confidence, and timestamps. `device_signals` contains type-tagged backend HMAC tokens—never raw hardware identifiers or unhashed client values. `user_devices` and `account_devices` are independent many-to-many histories with first/last seen and login counts.

`bans` stores append-preserved `USER_BAN`, `LINKED_DEVICE_BAN`, and `DEVICE_BAN` records, moderator numeric ID, reason, timestamps, optional expiry, active/removal state, and an origin link for one-hop groups. `ban_account_effects` records which accounts were affected and their pre-ban enabled state. `device_ban_exemptions` expresses explicit user↔device exemptions for linked-ban propagation; it does not override an explicit device ban.
