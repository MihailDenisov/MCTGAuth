# Database Model

This is a logical schema, not a migration file yet.

## `users`
Telegram identity: `telegram_id` PK, username snapshot, account status, persisted `locale` (`en` or `ru`), and created/updated timestamps. Locale values are stable codes rather than translated names.

## `minecraft_accounts`
Account ID, owner Telegram ID, Minecraft UUID (nullable until known), unique normalized nickname, license flag, enabled flag, creation method (`REGISTRATION_TICKET`, `ADD_ACCOUNT_TICKET`, `ADMIN_MANUAL`), creator moderator ID where applicable, credential version metadata, timestamps.

## `tickets`
Global numeric ticket ID, type, status, creator Telegram ID/username snapshot, optional Minecraft account ID, created/updated/closed timestamps, assigned moderator, admin chat/message identifiers.

## Type-specific ticket data
Separate tables such as `registration_ticket_data`, `problem_ticket_data`, `violation_ticket_data`, and `add_account_ticket_data`. Avoid a single table with dozens of nullable type-specific columns.

## `ticket_messages`
Ticket ID, sender type (`USER`, `MODERATOR`, `SYSTEM`), sender Telegram ID, text, timestamp.

## `ticket_attachments`
Ticket ID, Telegram `file_id`, `file_unique_id`, media type, optional caption, timestamp. Do not download media by default.

## `moderators`
Telegram ID PK, username snapshot, enabled flag, role/permissions (future), added timestamp.

## `audit_log`
Append-oriented event log: event type, ticket/account references, actor Telegram ID/username snapshot, timestamp, structured details. Never put secrets/passwords in details.

## `sync_events`
Stable event ID, type, payload/version, created timestamp, delivery/ack status, attempt metadata. Consumers must apply events idempotently.

## Minecraft local DB
Keep only authentication-critical state: Minecraft identity, enabled flag, credential verifier/version, synchronization version/event ID, and minimal audit/diagnostic metadata. Do not copy registration age/experience or unrelated Telegram ticket content.

## `remote_commands`
Durable queue for typed administrative commands sent from the backend to a Minecraft server. Suggested fields: numeric/internal ID or UUID, `server_id`, `command_type`, versioned structured payload, status (`PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `EXPIRED`, `CANCELLED`), creator Telegram ID, created/expiry/picked-up/completed timestamps, structured result, safe error text, and attempt metadata.

Command creation/completion must also emit `audit_log` records. Command payload/result data must never contain bot tokens, backend API credentials, plaintext passwords, or reusable authentication proofs.

## Migrations

`auth-backend` reads the ordered `db/migration/migrations.txt` manifest. Each `VNNN__name.sql` file runs in its own transaction and is recorded in `schema_migrations`; applied versions are never executed again. Startup fails with the migration filename if a required migration cannot be applied.

- `V001__initial.sql`: users, moderators, registration tickets/decisions, accounts, audit log, and sync events.
- `V002__backend_phase_2.sql`: affected-account/assignment fields, problem/violation/add-account data, messages, attachment references, password-change history, moderator permissions, and remote commands.
- `V003__user_locale.sql`: persisted English/Russian Telegram interface preference on `users`.

The current schema requires every Minecraft account to have an owning Telegram user. Ownerless technical accounts are deferred.
