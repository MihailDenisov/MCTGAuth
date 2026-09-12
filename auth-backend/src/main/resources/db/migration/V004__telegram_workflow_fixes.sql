CREATE TABLE violation_ticket_data_new (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    offender_nickname TEXT,
    normalized_offender_nickname TEXT,
    description TEXT NOT NULL,
    CHECK ((offender_nickname IS NULL) = (normalized_offender_nickname IS NULL))
);

INSERT INTO violation_ticket_data_new
    (ticket_id, offender_nickname, normalized_offender_nickname, description)
SELECT ticket_id, offender_nickname, normalized_offender_nickname, description
FROM violation_ticket_data;

DROP TABLE violation_ticket_data;
ALTER TABLE violation_ticket_data_new RENAME TO violation_ticket_data;
CREATE INDEX idx_violation_tickets ON violation_ticket_data(ticket_id);

CREATE TABLE moderator_pending_actions (
    moderator_telegram_id INTEGER PRIMARY KEY REFERENCES moderators(telegram_id),
    action_type TEXT NOT NULL CHECK (action_type IN ('REPLY_TO_TICKET')),
    ticket_id INTEGER NOT NULL REFERENCES tickets(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE INDEX idx_moderator_pending_actions_expiry
    ON moderator_pending_actions(expires_at);
