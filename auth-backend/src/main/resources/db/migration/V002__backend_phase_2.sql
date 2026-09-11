ALTER TABLE tickets ADD COLUMN affected_account_id INTEGER REFERENCES minecraft_accounts(id);
ALTER TABLE tickets ADD COLUMN assigned_at TEXT;

CREATE TABLE problem_ticket_data (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    description TEXT NOT NULL
);

CREATE TABLE violation_ticket_data (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    offender_nickname TEXT NOT NULL,
    normalized_offender_nickname TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE add_account_ticket_data (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    minecraft_nickname TEXT NOT NULL,
    normalized_nickname TEXT NOT NULL,
    licensed INTEGER NOT NULL CHECK (licensed IN (0, 1))
);

CREATE TABLE ticket_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id INTEGER NOT NULL REFERENCES tickets(id),
    sender_type TEXT NOT NULL CHECK (sender_type IN ('USER', 'MODERATOR', 'SYSTEM')),
    sender_telegram_id INTEGER,
    message_text TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE ticket_attachments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id INTEGER NOT NULL REFERENCES tickets(id),
    telegram_file_id TEXT NOT NULL,
    telegram_file_unique_id TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK (file_type IN ('PHOTO', 'VIDEO', 'DOCUMENT', 'ANIMATION')),
    caption TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE password_changes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES minecraft_accounts(id),
    requested_by_telegram_id INTEGER NOT NULL,
    credential_version INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE moderator_permissions (
    moderator_telegram_id INTEGER NOT NULL REFERENCES moderators(telegram_id),
    permission TEXT NOT NULL,
    PRIMARY KEY (moderator_telegram_id, permission)
);

CREATE TABLE remote_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    public_id TEXT NOT NULL UNIQUE,
    server_id TEXT NOT NULL,
    command_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_by_telegram_id INTEGER NOT NULL REFERENCES moderators(telegram_id),
    picked_up_at TEXT,
    completed_at TEXT,
    result_json TEXT,
    error_message TEXT
);

CREATE INDEX idx_ticket_messages_ticket ON ticket_messages(ticket_id, id);
CREATE INDEX idx_ticket_attachments_ticket ON ticket_attachments(ticket_id, id);
CREATE INDEX idx_problem_tickets ON problem_ticket_data(ticket_id);
CREATE INDEX idx_violation_tickets ON violation_ticket_data(ticket_id);
CREATE INDEX idx_add_account_nickname ON add_account_ticket_data(normalized_nickname);
CREATE INDEX idx_password_changes_account_time ON password_changes(account_id, created_at);
CREATE INDEX idx_remote_commands_poll ON remote_commands(server_id, status, expires_at, id);
