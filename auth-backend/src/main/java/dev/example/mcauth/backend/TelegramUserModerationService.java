package dev.example.mcauth.backend;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Bot-access moderation, intentionally independent from Minecraft account enablement. */
public final class TelegramUserModerationService {
    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final AuditService audit;
    private final Clock clock;

    public TelegramUserModerationService(BackendDatabase database, ModeratorService moderators,
                                         AuditService audit, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isBlocked(long telegramId) {
        if (telegramId <= 0) return false;
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT bot_blocked FROM users WHERE telegram_id=?")) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getInt(1) == 1;
                }
            }
        });
    }

    public ChangeResult setBlocked(ModeratorIdentity moderator, long targetTelegramId,
                                   boolean blocked, String reason) {
        if (targetTelegramId <= 0) throw new WorkflowException(
                WorkflowException.Code.INVALID_INPUT, "Invalid Telegram user ID");
        String safeReason = reason == null ? "" : reason.strip();
        if (safeReason.length() > 500) throw new WorkflowException(
                WorkflowException.Code.INVALID_INPUT, "Reason is too long");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.BOT_USER_MODERATE);
            boolean previous;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT bot_blocked FROM users WHERE telegram_id=?")) {
                query.setLong(1, targetTelegramId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new WorkflowException(
                            WorkflowException.Code.NOT_FOUND, "Telegram user not found");
                    previous = result.getInt(1) == 1;
                }
            }
            if (previous == blocked) return new ChangeResult(targetTelegramId, previous, blocked, false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE users SET bot_blocked=?, updated_at=? WHERE telegram_id=?")) {
                update.setInt(1, blocked ? 1 : 0);
                update.setString(2, Instant.now(clock).toString());
                update.setLong(3, targetTelegramId);
                update.executeUpdate();
            }
            audit.record(connection, blocked ? "TELEGRAM_USER_BLOCKED" : "TELEGRAM_USER_UNBLOCKED",
                    null, null, moderator.telegramId(), moderator.usernameSnapshot(), Instant.now(clock),
                    Map.of("targetTelegramId", targetTelegramId, "previousBotBlocked", previous,
                            "newBotBlocked", blocked, "reason", safeReason));
            return new ChangeResult(targetTelegramId, previous, blocked, true);
        });
    }

    public record ChangeResult(long targetTelegramId, boolean previousState,
                               boolean blocked, boolean changed) { }
}
