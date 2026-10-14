# Ticket Workflows

All ticket actions must validate actor authorization and current state. Suggested transitions:

`OPEN -> IN_PROGRESS -> WAITING_USER -> IN_PROGRESS -> RESOLVED -> CLOSED`

Alternative terminal path: `OPEN/IN_PROGRESS -> REJECTED -> CLOSED` where meaningful.

Ticket IDs are global numeric IDs. Admin-group messages are projections of database state, not the source of truth. Editing/deleting a Telegram message must not destroy the ticket record.

Phase 2 implements these transitions in `SupportTicketService`, persists user/moderator messages with an explicit ticket ID, and stores Telegram media identifiers separately. User access always checks `creator_telegram_id`; moderator operations always check numeric identity and permission.

Violation tickets may store a null offender nickname when the reporter does not know it; UI rendering localizes this as Unknown rather than persisting a placeholder.

A moderator reply initiated from a shared admin-group card creates an expiring `moderator_pending_actions` row keyed by moderator Telegram ID. The same enabled and assigned moderator completes it with private-chat text. Successful completion or `/cancel` removes the row; different moderators never share interaction state.

Resolve and Close first present an explicit choice: add a public closing comment, finish without a comment, or cancel. Comment input uses the same persisted per-moderator mechanism with a validated target status. The status compare-and-set, optional `CLOSURE_COMMENT` history row, and audit record commit in one transaction; a stale second action cannot replace the first closure or comment.

Rejecting a registration likewise offers Add Reason, Reject Without Reason, or Cancel. A reason is collected in private chat through a persisted moderator-specific action, limited to 1,000 characters, and stored as the existing registration decision comment in the same transaction as the `REJECTED` transition. It is sent to the applicant as plain text. A stale or unauthorized action cannot append a comment after another moderator finalizes the request.
