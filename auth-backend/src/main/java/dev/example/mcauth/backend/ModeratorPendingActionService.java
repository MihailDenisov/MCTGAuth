package dev.example.mcauth.backend;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ModeratorPendingActionService {
    private static final Duration ACTION_TTL = Duration.ofMinutes(30);

    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final Clock clock;

    public ModeratorPendingActionService(
            BackendDatabase database, ModeratorService moderators, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void beginReply(ModeratorIdentity moderator, long ticketId) {
        database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_REPLY);
            try (PreparedStatement ticket = connection.prepareStatement(
                    "SELECT status, assigned_moderator_telegram_id FROM tickets WHERE id = ?")) {
                ticket.setLong(1, ticketId);
                try (ResultSet result = ticket.executeQuery()) {
                    if (!result.next()) throw new WorkflowException(
                            WorkflowException.Code.NOT_FOUND, "Ticket not found");
                    String status = result.getString(1);
                    if (!"IN_PROGRESS".equals(status) && !"WAITING_USER".equals(status)) {
                        throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                                "Ticket is not open for conversation");
                    }
                    long assigned = result.getLong("assigned_moderator_telegram_id");
                    if (result.wasNull() || assigned != moderator.telegramId()) {
                        throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                                "Ticket is assigned to another moderator");
                    }
                }
            }
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO moderator_pending_actions
                        (moderator_telegram_id, action_type, ticket_id, created_at, expires_at)
                    VALUES (?, 'REPLY_TO_TICKET', ?, ?, ?)
                    ON CONFLICT(moderator_telegram_id) DO UPDATE SET
                        action_type = excluded.action_type,
                        ticket_id = excluded.ticket_id,
                        created_at = excluded.created_at,
                        expires_at = excluded.expires_at
                    """)) {
                statement.setLong(1, moderator.telegramId());
                statement.setLong(2, ticketId);
                statement.setString(3, now.toString());
                statement.setString(4, now.plus(ACTION_TTL).toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<ModeratorPendingAction> find(long moderatorTelegramId) {
        if (moderatorTelegramId <= 0) return Optional.empty();
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            try (PreparedStatement expired = connection.prepareStatement(
                    "DELETE FROM moderator_pending_actions WHERE expires_at <= ?")) {
                expired.setString(1, now.toString());
                expired.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT action_type, ticket_id, created_at, expires_at
                    FROM moderator_pending_actions WHERE moderator_telegram_id = ?
                    """)) {
                statement.setLong(1, moderatorTelegramId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    return Optional.of(new ModeratorPendingAction(
                            moderatorTelegramId,
                            ModeratorPendingAction.Type.valueOf(result.getString("action_type")),
                            result.getLong("ticket_id"),
                            Instant.parse(result.getString("created_at")),
                            Instant.parse(result.getString("expires_at"))));
                }
            }
        });
    }

    public void clear(long moderatorTelegramId) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM moderator_pending_actions WHERE moderator_telegram_id = ?")) {
                statement.setLong(1, moderatorTelegramId);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
