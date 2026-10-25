PRAGMA foreign_keys = ON;

CREATE TABLE devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fingerprint_version INTEGER NOT NULL CHECK (fingerprint_version > 0),
    confidence TEXT NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    created_at TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE TABLE device_signals (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    signal_type TEXT NOT NULL CHECK (signal_type IN
        ('SYSTEM_UUID', 'MACHINE_ID', 'MOTHERBOARD_UUID', 'DISK_ID', 'GPU', 'MAC_ADDRESS')),
    signal_hash TEXT NOT NULL CHECK (length(signal_hash) = 64),
    strong INTEGER NOT NULL CHECK (strong IN (0, 1)),
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    PRIMARY KEY (device_id, signal_type, signal_hash)
);

CREATE TABLE user_devices (
    user_id INTEGER NOT NULL REFERENCES users(telegram_id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    login_count INTEGER NOT NULL DEFAULT 1 CHECK (login_count > 0),
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE account_devices (
    account_id INTEGER NOT NULL REFERENCES minecraft_accounts(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    login_count INTEGER NOT NULL DEFAULT 1 CHECK (login_count > 0),
    PRIMARY KEY (account_id, device_id)
);

CREATE TABLE bans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ban_type TEXT NOT NULL CHECK (ban_type IN ('USER_BAN', 'DEVICE_BAN', 'LINKED_DEVICE_BAN')),
    target_user_id INTEGER REFERENCES users(telegram_id),
    target_device_id INTEGER REFERENCES devices(id),
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    created_by INTEGER NOT NULL REFERENCES moderators(telegram_id),
    created_at TEXT NOT NULL,
    expires_at TEXT,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    origin_ban_id INTEGER REFERENCES bans(id),
    removed_by INTEGER REFERENCES moderators(telegram_id),
    removed_at TEXT,
    remove_reason TEXT,
    CHECK ((ban_type = 'DEVICE_BAN' AND target_device_id IS NOT NULL AND target_user_id IS NULL)
        OR (ban_type IN ('USER_BAN', 'LINKED_DEVICE_BAN') AND target_user_id IS NOT NULL AND target_device_id IS NULL)),
    CHECK (origin_ban_id IS NULL OR origin_ban_id <> id)
);

CREATE TABLE ban_account_effects (
    ban_id INTEGER NOT NULL REFERENCES bans(id) ON DELETE CASCADE,
    account_id INTEGER NOT NULL REFERENCES minecraft_accounts(id) ON DELETE CASCADE,
    was_enabled_before_ban INTEGER NOT NULL CHECK (was_enabled_before_ban IN (0, 1)),
    PRIMARY KEY (ban_id, account_id)
);

CREATE TABLE device_ban_exemptions (
    user_id INTEGER NOT NULL REFERENCES users(telegram_id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_by INTEGER NOT NULL REFERENCES moderators(telegram_id),
    created_at TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    removed_by INTEGER REFERENCES moderators(telegram_id),
    removed_at TEXT,
    remove_reason TEXT,
    PRIMARY KEY (user_id, device_id)
);

CREATE INDEX idx_device_signals_lookup ON device_signals(signal_type, signal_hash, device_id);
CREATE INDEX idx_devices_last_seen ON devices(last_seen_at);
CREATE INDEX idx_user_devices_device ON user_devices(device_id, user_id);
CREATE INDEX idx_account_devices_device ON account_devices(device_id, account_id);
CREATE INDEX idx_bans_active_user ON bans(target_user_id, active, expires_at);
CREATE INDEX idx_bans_active_device ON bans(target_device_id, active, expires_at);
CREATE INDEX idx_bans_origin ON bans(origin_ban_id, active);
CREATE INDEX idx_exemptions_device ON device_ban_exemptions(device_id, active, user_id);

DROP INDEX idx_moderator_pending_actions_expiry;
ALTER TABLE moderator_pending_actions RENAME TO moderator_pending_actions_old;

CREATE TABLE moderator_pending_actions (
    moderator_telegram_id INTEGER PRIMARY KEY REFERENCES moderators(telegram_id),
    action_type TEXT NOT NULL CHECK (action_type IN (
        'REPLY_TO_TICKET', 'TICKET_CLOSURE_COMMENT', 'REGISTRATION_REJECTION_REASON',
        'USER_BAN_REASON', 'DEVICE_BAN_REASON'
    )),
    ticket_id INTEGER REFERENCES tickets(id),
    target_id INTEGER,
    target_status TEXT CHECK (target_status IN ('RESOLVED', 'CLOSED')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    CHECK ((action_type = 'TICKET_CLOSURE_COMMENT' AND ticket_id IS NOT NULL
                AND target_id IS NULL AND target_status IS NOT NULL)
        OR (action_type IN ('REPLY_TO_TICKET', 'REGISTRATION_REJECTION_REASON')
                AND ticket_id IS NOT NULL AND target_id IS NULL AND target_status IS NULL)
        OR (action_type IN ('USER_BAN_REASON', 'DEVICE_BAN_REASON')
                AND ticket_id IS NULL AND target_id IS NOT NULL AND target_status IS NULL))
);

INSERT INTO moderator_pending_actions
    (moderator_telegram_id, action_type, ticket_id, target_id, target_status, created_at, expires_at)
SELECT moderator_telegram_id, action_type, ticket_id, NULL, target_status, created_at, expires_at
FROM moderator_pending_actions_old;

DROP TABLE moderator_pending_actions_old;
CREATE INDEX idx_moderator_pending_actions_expiry ON moderator_pending_actions(expires_at);
