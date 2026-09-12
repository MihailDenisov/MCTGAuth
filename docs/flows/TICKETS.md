# Ticket Workflows

All ticket actions must validate actor authorization and current state. Suggested transitions:

`OPEN -> IN_PROGRESS -> WAITING_USER -> IN_PROGRESS -> RESOLVED -> CLOSED`

Alternative terminal path: `OPEN/IN_PROGRESS -> REJECTED -> CLOSED` where meaningful.

Ticket IDs are global numeric IDs. Admin-group messages are projections of database state, not the source of truth. Editing/deleting a Telegram message must not destroy the ticket record.

Phase 2 implements these transitions in `SupportTicketService`, persists user/moderator messages with an explicit ticket ID, and stores Telegram media identifiers separately. User access always checks `creator_telegram_id`; moderator operations always check numeric identity and permission.

Violation tickets may store a null offender nickname when the reporter does not know it; UI rendering localizes this as Unknown rather than persisting a placeholder.

A moderator reply initiated from a shared admin-group card creates an expiring `moderator_pending_actions` row keyed by moderator Telegram ID. The same enabled and assigned moderator completes it with private-chat text. Successful completion or `/cancel` removes the row; different moderators never share interaction state.
