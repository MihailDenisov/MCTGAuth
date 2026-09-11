# Registration Flow

State-machine draft: `START -> MINECRAFT_NAME -> LICENSE -> AGE -> EXPERIENCE -> CONFIRM -> SUBMITTED`.

Submission creates a `REGISTRATION` ticket before the admin notification is sent. Moderator decision is atomic/idempotent: a second moderator cannot approve an already-decided ticket. Approval creates the account and sync event; rejection records the decision and reason/comment if used.

The Telegram UI now exposes the wizard through a persistent reply keyboard while retaining `/register`. An existing active registration ticket is shown instead of opening a duplicate.
