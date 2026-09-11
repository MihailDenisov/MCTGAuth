PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    telegram_id INTEGER PRIMARY KEY,
    username_snapshot TEXT NOT NULL DEFAULT '',
    account_status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS moderators (
    telegram_id INTEGER PRIMARY KEY,
    username_snapshot TEXT NOT NULL DEFAULT '',
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    added_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    creator_telegram_id INTEGER NOT NULL REFERENCES users(telegram_id),
    creator_username_snapshot TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    closed_at TEXT,
    assigned_moderator_telegram_id INTEGER REFERENCES moderators(telegram_id),
    admin_chat_id INTEGER,
    admin_message_id INTEGER
);

CREATE TABLE IF NOT EXISTS registration_ticket_data (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    minecraft_nickname TEXT NOT NULL,
    normalized_nickname TEXT NOT NULL,
    licensed INTEGER NOT NULL CHECK (licensed IN (0, 1)),
    age INTEGER NOT NULL CHECK (age BETWEEN 1 AND 120),
    experience TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ticket_decisions (
    ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id),
    decision TEXT NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    moderator_telegram_id INTEGER NOT NULL REFERENCES moderators(telegram_id),
    moderator_username_snapshot TEXT NOT NULL DEFAULT '',
    comment TEXT NOT NULL DEFAULT '',
    decided_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS minecraft_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_telegram_id INTEGER NOT NULL REFERENCES users(telegram_id),
    minecraft_uuid TEXT,
    nickname TEXT NOT NULL,
    normalized_nickname TEXT NOT NULL UNIQUE,
    licensed INTEGER NOT NULL CHECK (licensed IN (0, 1)),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    creation_method TEXT NOT NULL,
    creator_moderator_id INTEGER REFERENCES moderators(telegram_id),
    credential_verifier TEXT NOT NULL,
    credential_version INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    ticket_id INTEGER REFERENCES tickets(id),
    account_id INTEGER REFERENCES minecraft_accounts(id),
    actor_telegram_id INTEGER,
    actor_username_snapshot TEXT NOT NULL DEFAULT '',
    occurred_at TEXT NOT NULL,
    details_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS sync_events (
    event_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    acknowledged_at TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tickets_creator ON tickets(creator_telegram_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status, id);
CREATE INDEX IF NOT EXISTS idx_registration_nickname ON registration_ticket_data(normalized_nickname);
CREATE INDEX IF NOT EXISTS idx_sync_events_pending ON sync_events(acknowledged_at, created_at);
