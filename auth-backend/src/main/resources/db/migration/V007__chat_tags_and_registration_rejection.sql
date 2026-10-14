ALTER TABLE users
    ADD COLUMN chat_tag_account_id INTEGER
    REFERENCES minecraft_accounts(id) ON DELETE SET NULL;

ALTER TABLE users
    ADD COLUMN chat_tag_managed INTEGER NOT NULL DEFAULT 0
    CHECK (chat_tag_managed IN (0, 1));

CREATE INDEX idx_users_chat_tag_account
    ON users(chat_tag_account_id);

DROP INDEX idx_moderator_pending_actions_expiry;
ALTER TABLE moderator_pending_actions RENAME TO moderator_pending_actions_old;

CREATE TABLE moderator_pending_actions (
    moderator_telegram_id INTEGER PRIMARY KEY REFERENCES moderators(telegram_id),
    action_type TEXT NOT NULL CHECK (action_type IN (
        'REPLY_TO_TICKET',
        'TICKET_CLOSURE_COMMENT',
        'REGISTRATION_REJECTION_REASON'
    )),
    ticket_id INTEGER NOT NULL REFERENCES tickets(id),
    target_status TEXT CHECK (target_status IN ('RESOLVED', 'CLOSED')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    CHECK ((action_type = 'TICKET_CLOSURE_COMMENT' AND target_status IS NOT NULL)
        OR (action_type IN ('REPLY_TO_TICKET', 'REGISTRATION_REJECTION_REASON')
            AND target_status IS NULL))
);

INSERT INTO moderator_pending_actions
    (moderator_telegram_id, action_type, ticket_id, target_status, created_at, expires_at)
SELECT moderator_telegram_id, action_type, ticket_id, target_status, created_at, expires_at
FROM moderator_pending_actions_old;

DROP TABLE moderator_pending_actions_old;

CREATE INDEX idx_moderator_pending_actions_expiry
    ON moderator_pending_actions(expires_at);
