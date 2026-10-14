ALTER TABLE users ADD COLUMN bot_blocked INTEGER NOT NULL DEFAULT 0 CHECK (bot_blocked IN (0, 1));

CREATE TABLE server_status (
    server_id TEXT PRIMARY KEY,
    server_instance_id TEXT NOT NULL,
    started_at TEXT NOT NULL,
    last_heartbeat_at TEXT NOT NULL,
    online_players INTEGER NOT NULL CHECK (online_players >= 0),
    max_players INTEGER NOT NULL CHECK (max_players >= 0),
    shutdown_at TEXT
);

CREATE TABLE player_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    join_event_id TEXT NOT NULL UNIQUE,
    leave_event_id TEXT UNIQUE,
    server_id TEXT NOT NULL,
    server_instance_id TEXT NOT NULL,
    minecraft_account_id INTEGER NOT NULL REFERENCES minecraft_accounts(id) ON DELETE CASCADE,
    nickname_snapshot TEXT NOT NULL,
    joined_at TEXT NOT NULL,
    left_at TEXT,
    close_reason TEXT CHECK (close_reason IN ('PLAYER_LEFT', 'SERVER_SHUTDOWN', 'SERVER_LOST', 'ADMIN_RESET'))
);

CREATE TABLE telemetry_events (
    event_id TEXT PRIMARY KEY,
    server_id TEXT NOT NULL,
    server_instance_id TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('PLAYER_JOINED', 'PLAYER_LEFT')),
    occurred_at TEXT NOT NULL,
    processed_at TEXT NOT NULL
);

CREATE INDEX idx_player_sessions_account_time
    ON player_sessions(minecraft_account_id, joined_at, left_at);
CREATE INDEX idx_player_sessions_server_active
    ON player_sessions(server_id, server_instance_id, left_at);
CREATE UNIQUE INDEX idx_player_sessions_one_active
    ON player_sessions(server_id, server_instance_id, minecraft_account_id) WHERE left_at IS NULL;
CREATE INDEX idx_telemetry_events_server_time ON telemetry_events(server_id, occurred_at);
