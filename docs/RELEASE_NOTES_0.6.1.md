# MCTGAuth 0.6.1

[Russian version](RELEASE_NOTES_0.6.1_RU.md)

## Ticket closure comments

- Resolve and Close actions now ask whether the moderator wants to add a closing comment.
- The moderator can add a public comment, finish without a comment, or cancel without changing the ticket.
- A comment is validated to the existing 4,000-character ticket-message limit and stored as a `CLOSURE_COMMENT` in ticket history.
- The user receives the localized closure notification and administrator comment when present.
- Status transition, comment insertion, and audit creation occur in one database transaction.
- A conditional status update prevents stale or concurrent actions from adding another transition or overwriting the first comment.
- Audit details contain the numeric moderator identity, prior/new status, timestamp, and comment-presence flag, but not a duplicate of the comment text.

## Compatibility

- Backend database schema advances from 5 to 6 through `V006__ticket_closure_comments.sql`.
- Authentication wire protocol remains version 1, and the Minecraft authentication-cache schema remains version 1.
- No new environment variable is required.

See [the 0.6.0 to 0.6.1 migration guide](MIGRATION_0.6.0_TO_0.6.1.md).
