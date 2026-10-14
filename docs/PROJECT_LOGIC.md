# Project Logic

## 1. Core idea

Telegram is the account-management and support interface. The VPS backend is authoritative for Telegram users, tickets, moderator decisions, Minecraft account ownership, and audit history. The Minecraft server keeps only the minimum local authentication data required to authenticate already-synchronized players even if the VPS is temporarily unavailable.

## 2. Identity model

A Telegram user can own one or more Minecraft accounts. Ordinary users cannot directly register arbitrary additional nicknames. Their first account is created through a registration ticket; additional nicknames require an `ADD_MINECRAFT_ACCOUNT` ticket and moderator approval. Moderators have a privileged manual-registration workflow for administrative alts/technical accounts. Every privileged action is audited.

Use immutable Telegram numeric IDs as identity keys. Usernames are display metadata and may change. For Minecraft, prefer UUID as the durable identity when it can be trusted; retain the normalized current nickname for display/search and carefully define offline-mode behavior before implementation.

## 3. Ticket system

All requests are tickets with one global numeric sequence. Initial types:

- `REGISTRATION`
- `ADD_MINECRAFT_ACCOUNT`
- `PROBLEM`
- `VIOLATION`

Suggested generic statuses: `OPEN`, `IN_PROGRESS`, `WAITING_USER`, `RESOLVED`, `REJECTED`, `CLOSED`.

Registration-specific approval is represented by an audited decision, not by silently mutating data. Tickets are never hard-deleted through Telegram. They may be closed/archived while history remains available.

Each ticket records creator Telegram ID/username snapshot, Minecraft account where applicable, creation/update/close timestamps, assigned moderator, administrative Telegram message ID, messages, attachments, and audit events. Store timestamps in UTC and format them for display at the UI boundary.

## 4. Registration flow

1. Unregistered user starts the bot.
2. Bot opens a `REGISTRATION` ticket and collects Minecraft nickname, license ownership, age, and modded-Minecraft experience.
3. Backend validates uniqueness and stores the ticket before notifying moderators.
4. A ticket card is posted to the private administration group with Approve/Reject controls.
5. Only enabled moderators may act. The decision records moderator Telegram ID, username snapshot, timestamp, and optional comment.
6. On approval, backend creates/links the Minecraft account, generates a strong random initial password, derives/stores the credential verifier according to the security design, creates a synchronization event, and sends the password to the user.
7. Minecraft server polls the backend over outbound HTTPS, applies the account/whitelist update to its local database, and acknowledges the event.
8. The player can authenticate even during a later backend outage because the local verifier is already present.

## 5. User menu after registration

- **How to Play**: short instructions plus a configurable link to the detailed server-chat post.
- **Report a Problem**: creates a `PROBLEM` ticket. The bot automatically records Telegram ID/username, selected Minecraft nickname, UTC time, description, and Telegram attachment identifiers. It posts the ticket to the admin group.
- **Report a Violation**: creates a `VIOLATION` ticket, additionally collecting the reported player's nickname and evidence/description.
- **My Tickets**: paginated history and ticket details; later supports conversation replies.
- **My Account**: lists owned Minecraft accounts, password-change action, and request-to-add-nickname action.

## 6. Ticket conversation

A moderator can claim a ticket, reply, request more information, resolve/reject/close it, and see its history. User and moderator replies are stored as ticket messages. Media should initially be referenced by Telegram `file_id`/`file_unique_id` instead of permanently downloading every file to the small VPS.

## 7. Additional nickname flow

1. Registered user selects **Add Minecraft nickname**.
2. Backend creates `ADD_MINECRAFT_ACCOUNT` ticket.
3. Requested nickname is checked for existing ownership and conflicting pending requests.
4. Moderator approves/rejects.
5. Approval creates a new Minecraft account owned by the same Telegram user, generates its credential, and queues synchronization.
6. Direct self-service nickname creation is not allowed.

Moderator manual registration bypasses the ticket requirement but requires explicit confirmation and creates a complete audit record with `creation_method = ADMIN_MANUAL`.

## 8. Password change

1. User selects one of their Minecraft accounts.
2. Backend confirms ownership and applies rate limits.
3. User confirms password rotation.
4. Backend generates a new password with a cryptographically secure RNG and prepares the new verifier/version.
5. A synchronization event is queued for Minecraft.
6. Define the activation rule before coding: recommended behavior is to tell the user that the new password becomes active only after Minecraft acknowledges the event, avoiding an ambiguous split-brain period.
7. Never store old plaintext passwords. Audit the rotation event without logging the password.

## 9. Minecraft login

The server mod treats authentication as a state machine: `NOT_REGISTERED`, `AUTHENTICATING`, `AUTHENTICATED`, `FAILED`. A connecting player is restricted until authentication succeeds or a timeout expires. Missing registration produces a clear kick reason; invalid proof/password produces a different reason.

Do not blindly transmit a reusable static password hash. Finalize a challenge-response or otherwise replay-resistant protocol before implementing networking. Password verifiers at rest should use a modern password hashing scheme such as Argon2id or an appropriately configured BCrypt implementation.

## 10. Synchronization

Minecraft initiates all backend communication so no custom inbound Pterodactyl port is required. Use HTTPS and authenticate server requests. Poll or long-poll a versioned event endpoint. Events must have stable IDs and idempotent application semantics. The server acknowledges only after local DB and whitelist changes are durably applied.

Typical events: `ACCOUNT_CREATED`, `PASSWORD_CHANGED`, `ACCOUNT_ENABLED`, `ACCOUNT_DISABLED`, `ACCOUNT_RENAMED` (if supported later).

## 11. Availability boundary

Telegram/backend outage must block only management operations that require the backend (new registration, ticket actions, password rotation, new account sync). It must not prevent already-synchronized users from authenticating to Minecraft.

## 12. Audit and privacy

Audit moderator actions, ticket transitions, account creation, password rotations, sync success/failure, and security-relevant changes. Never log bot tokens, API tokens, plaintext passwords, or credential proofs. Keep age/experience and other application-only data on the VPS; do not replicate it to Minecraft.

## 13. Remote administrative commands

The Telegram administration UI may request a limited set of server-management operations without exposing RCON or an arbitrary remote console. A moderator action creates a durable remote-command record on the backend. The Minecraft server retrieves commands through the same outbound HTTPS trust boundary used for synchronization.

Initial command types may include:

- `FORCE_AUTH_SYNC`
- `AUTH_STATUS`
- `LIST_AUTH_ERRORS`
- `RESYNC_PLAYER`
- `ENABLE_PLAYER_AUTH`
- `DISABLE_PLAYER_AUTH`
- `RELOAD_AUTH_CACHE`

The list is an allowlist, not a free-form command field. Each command type has a dedicated server-side handler with a validated payload and a structured result.

Suggested command lifecycle: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `EXPIRED`, `CANCELLED`. Commands include a target `server_id`, creator Telegram ID, creation timestamp, expiry timestamp, pickup/start/completion timestamps, and result/error metadata. Short-lived operational commands should expire rather than unexpectedly run hours or days later.

The administration bot should expose only commands permitted for the acting moderator. The backend must enforce permissions independently of button visibility. Suggested permission examples: `auth.sync.force`, `auth.player.resync`, `auth.player.enable`, `auth.player.disable`, `server.status`.

Remote command actions are fully audited. Never log secrets in command payloads/results. Never implement arbitrary shell execution, arbitrary RCON, or direct forwarding of Telegram text into the Minecraft command dispatcher.

The command channel preserves the availability boundary: if the backend is unavailable, queued remote management operations are unavailable, but already-synchronized players can still authenticate locally.

## 14. Phase 2 backend status

The Telegram backend now provides persistent reply-keyboard menus for registered/unregistered users and enabled moderators. Implemented workflows include registration status, problem/violation reports with Telegram attachment references, paginated user ticket history, account listing, add-account approval tickets, password rotation, moderator ticket assignment/replies/transitions, search/statistics, manual owned-account creation, and typed server-management command creation.

An embedded loopback-only HTTP server provides minimal health, authenticated synchronization polling/ACK, and authenticated remote-command polling/start/result endpoints. Minecraft remains the initiating party. Submitted business state is durable in SQLite; short-lived Telegram wizard drafts remain memory-only and restart safely from the relevant menu.

## 15. Telegram localization

The Telegram UI supports `en` and `ru`, with English fallback. Each known user has a persisted locale and can change it from Settings; Telegram's language code is only an initial suggestion. The shared administration group uses independently configured `ADMIN_LOCALE`, while moderator private chats use each moderator's user locale.

Translations are presentation-only. Ticket types/statuses, remote-command types/statuses, account creation methods, callback data, permissions, and Telegram numeric identities retain stable locale-independent values. Reply-keyboard labels resolve through stable actions instead of being used directly as business identifiers. UTC timestamps are formatted only while rendering messages.

## 16. VPS deployment and recovery

The backend is deployed as immutable releases under `/opt/minecraft-auth/releases`, with an atomically switched `/opt/minecraft-auth/current` symlink. Persistent SQLite data, secret configuration, backups, and deployment logs remain outside release directories. A release artifact contains only launchers, libraries, and public build/schema metadata.

Every update validates the uploaded archive, Java version, release layout, and database-schema compatibility, then creates and integrity-checks an SQLite online backup before stopping the service. Activation succeeds only after the systemd service passes its loopback health check. Failed activation automatically restores the previous application release without silently replacing the database.

Rollback changes application code only and refuses a release whose declared maximum schema is older than the current database unless an operator explicitly overrides the guard after reviewing migrations. Database restoration is a separate confirmed operation that creates an emergency backup first. Update, rollback, restore, and cleanup operations share a lock and record non-secret deployment outcomes.

## 17. Telegram UI and moderator interaction state

Version 0.3 introduced, and version 0.4.1 retains, `/menu`, `/adm`, and `/cancel` in private chats plus `/menu@BotUsername` and `/adm@BotUsername` in the configured administration group. Chat ID selects presentation context and locale; enabled moderator identity and every privileged authorization decision use the sender's numeric Telegram ID.

Free-form moderator input is private. When a moderator starts Reply from a shared group card, the backend persists a moderator-specific expiring action and completes it only from the same enabled, assigned moderator's private chat. Translated labels never become business identifiers. All wizard/submenu views provide localized escape navigation, and violation reports may persist a null offender nickname.

Version 0.4 makes `/menu` and the localized Main menu action always return to the ordinary user-facing menu in private chat, including for moderators; `/adm` remains the administrative entry point. Moderator account search can open one authoritative account record and disable or enable it with dedicated permission checks, audit records, and `ACCOUNT_DISABLED` / `ACCOUNT_ENABLED` sync events. Disable requires an optional-reason step and a separate explicit confirmation. Telegram timestamp rendering is centralized on `Europe/Moscow`; database timestamps and expiry calculations remain UTC-based.

## 18. Version 0.6 telemetry and statistics

The dedicated server sends authenticated outbound heartbeats and idempotent join/leave events. A unique `serverInstanceId` is generated for every process. Game-thread callbacks capture immutable account-ID snapshots; a background worker atomically journals and posts them. The backend derives freshness from the heartbeat timeout and never exposes stale counts/lists as current. Playtime is calculated from UTC session intervals, includes active sessions, and merges overlaps before aggregation.

Telegram bot blocking is a dedicated user moderation state. Blocked users receive only the localized restricted-access response. This does not disable Minecraft authentication; account disable remains a separate moderator action.
