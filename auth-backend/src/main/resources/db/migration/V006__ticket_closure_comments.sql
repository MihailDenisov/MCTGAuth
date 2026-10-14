ALTER TABLE ticket_messages
    ADD COLUMN message_type TEXT NOT NULL DEFAULT 'MESSAGE'
    CHECK (message_type IN ('MESSAGE', 'CLOSURE_COMMENT'));

ALTER TABLE ticket_messages
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'PUBLIC'
    CHECK (visibility IN ('PUBLIC', 'INTERNAL'));

DROP INDEX idx_moderator_pending_actions_expiry;
ALTER TABLE moderator_pending_actions RENAME TO moderator_pending_actions_old;

CREATE TABLE moderator_pending_actions (
    moderator_telegram_id INTEGER PRIMARY KEY REFERENCES moderators(telegram_id),
    action_type TEXT NOT NULL CHECK (action_type IN ('REPLY_TO_TICKET', 'TICKET_CLOSURE_COMMENT')),
    ticket_id INTEGER NOT NULL REFERENCES tickets(id),
    target_status TEXT CHECK (target_status IN ('RESOLVED', 'CLOSED')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    CHECK ((action_type = 'REPLY_TO_TICKET' AND target_status IS NULL)
        OR (action_type = 'TICKET_CLOSURE_COMMENT' AND target_status IS NOT NULL))
);

INSERT INTO moderator_pending_actions
    (moderator_telegram_id, action_type, ticket_id, target_status, created_at, expires_at)
SELECT moderator_telegram_id, action_type, ticket_id, NULL, created_at, expires_at
FROM moderator_pending_actions_old;

DROP TABLE moderator_pending_actions_old;

CREATE INDEX idx_moderator_pending_actions_expiry
    ON moderator_pending_actions(expires_at);
