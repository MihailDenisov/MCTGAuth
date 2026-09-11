package dev.example.mcauth.backend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class ModeratorService {
    private final BackendDatabase database;
    private final Clock clock;

    public ModeratorService(BackendDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void synchronizeConfiguredModerators(Set<Long> configuredIds) {
        database.transaction(connection -> {
            try (PreparedStatement disable = connection.prepareStatement("UPDATE moderators SET enabled = 0")) {
                disable.executeUpdate();
            }
            String sql = """
                    INSERT INTO moderators (telegram_id, username_snapshot, enabled, added_at)
                    VALUES (?, '', 1, ?)
                    ON CONFLICT(telegram_id) DO UPDATE SET enabled = 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (long telegramId : configuredIds) {
                    if (telegramId <= 0) {
                        throw new IllegalArgumentException("Moderator Telegram IDs must be positive");
                    }
                    statement.setLong(1, telegramId);
                    statement.setString(2, Instant.now(clock).toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement remove = connection.prepareStatement("""
                    DELETE FROM moderator_permissions
                    WHERE moderator_telegram_id IN (SELECT telegram_id FROM moderators WHERE enabled = 0)
                    """)) {
                remove.executeUpdate();
            }
            try (PreparedStatement grant = connection.prepareStatement("""
                    INSERT OR IGNORE INTO moderator_permissions (moderator_telegram_id, permission)
                    VALUES (?, ?)
                    """)) {
                for (long telegramId : configuredIds) {
                    for (ModeratorPermission permission : ModeratorPermission.values()) {
                        grant.setLong(1, telegramId);
                        grant.setString(2, permission.key());
                        grant.addBatch();
                    }
                }
                grant.executeBatch();
            }
            return null;
        });
    }

    void requireEnabled(Connection connection, ModeratorIdentity moderator) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled FROM moderators WHERE telegram_id = ?")) {
            statement.setLong(1, moderator.telegramId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt("enabled") != 1) {
                    throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                            "This Telegram account is not an enabled moderator");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE moderators SET username_snapshot = ? WHERE telegram_id = ?")) {
            statement.setString(1, moderator.usernameSnapshot());
            statement.setLong(2, moderator.telegramId());
            statement.executeUpdate();
        }
    }

    void requirePermission(
            Connection connection, ModeratorIdentity moderator, ModeratorPermission permission) throws SQLException {
        requireEnabled(connection, moderator);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM moderator_permissions
                WHERE moderator_telegram_id = ? AND permission = ?
                """)) {
            statement.setLong(1, moderator.telegramId());
            statement.setString(2, permission.key());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                            "Moderator permission is required: " + permission.key());
                }
            }
        }
    }

    public boolean isEnabled(long telegramId) {
        if (telegramId <= 0) {
            return false;
        }
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT enabled FROM moderators WHERE telegram_id = ?")) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getInt(1) == 1;
                }
            }
        });
    }

}
