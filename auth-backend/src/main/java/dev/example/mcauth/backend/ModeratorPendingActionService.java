package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;

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
        begin(moderator, ticketId, null, ModeratorPendingAction.Type.REPLY_TO_TICKET, null);
    }

    public void beginClosureComment(
            ModeratorIdentity moderator, long ticketId, TicketStatus targetStatus) {
        if (targetStatus != TicketStatus.RESOLVED && targetStatus != TicketStatus.CLOSED) {
            throw new IllegalArgumentException("Unsupported closure status");
        }
        begin(moderator, ticketId, null, ModeratorPendingAction.Type.TICKET_CLOSURE_COMMENT, targetStatus);
    }

    public void beginRegistrationRejectionReason(ModeratorIdentity moderator, long ticketId) {
        begin(moderator, ticketId, null, ModeratorPendingAction.Type.REGISTRATION_REJECTION_REASON, null);
    }

    public void beginUserBanReason(ModeratorIdentity moderator, long userId) {
        if (userId <= 0) throw new IllegalArgumentException("User ID must be positive");
        begin(moderator, 0, userId, ModeratorPendingAction.Type.USER_BAN_REASON, null);
    }

    public void beginDeviceBanReason(ModeratorIdentity moderator, long deviceId) {
        if (deviceId <= 0) throw new IllegalArgumentException("Device ID must be positive");
        begin(moderator, 0, deviceId, ModeratorPendingAction.Type.DEVICE_BAN_REASON, null);
    }

    private void begin(
            ModeratorIdentity moderator, long ticketId, Long targetId, ModeratorPendingAction.Type type,
            TicketStatus targetStatus) {
        database.transaction(connection -> {
            ModeratorPermission permission = switch (type) {
                case REPLY_TO_TICKET -> ModeratorPermission.TICKET_REPLY;
                case TICKET_CLOSURE_COMMENT -> ModeratorPermission.TICKET_RESOLVE;
                case REGISTRATION_REJECTION_REASON -> ModeratorPermission.TICKET_REJECT;
                case USER_BAN_REASON -> ModeratorPermission.USER_BAN;
                case DEVICE_BAN_REASON -> ModeratorPermission.DEVICE_BAN;
            };
            moderators.requirePermission(connection, moderator, permission);
            if (type != ModeratorPendingAction.Type.USER_BAN_REASON
                    && type != ModeratorPendingAction.Type.DEVICE_BAN_REASON) {
            try (PreparedStatement ticket = connection.prepareStatement(
                    "SELECT type, status, assigned_moderator_telegram_id FROM tickets WHERE id = ?")) {
                ticket.setLong(1, ticketId);
                try (ResultSet result = ticket.executeQuery()) {
                    if (!result.next()) throw new WorkflowException(
                            WorkflowException.Code.NOT_FOUND, "Ticket not found");
                    String ticketType = result.getString("type");
                    String status = result.getString("status");
                    boolean valid = switch (type) {
                        case REPLY_TO_TICKET -> "IN_PROGRESS".equals(status) || "WAITING_USER".equals(status);
                        case TICKET_CLOSURE_COMMENT -> targetStatus == TicketStatus.RESOLVED
                                ? "IN_PROGRESS".equals(status)
                                : "RESOLVED".equals(status) || "REJECTED".equals(status);
                        case REGISTRATION_REJECTION_REASON -> "REGISTRATION".equals(ticketType)
                                && ("OPEN".equals(status) || "IN_PROGRESS".equals(status));
                        case USER_BAN_REASON, DEVICE_BAN_REASON -> false;
                    };
                    if (!valid) {
                        throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                                "Ticket is not valid for this action");
                    }
                    long assigned = result.getLong("assigned_moderator_telegram_id");
                    if (result.wasNull() || assigned != moderator.telegramId()) {
                        throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                                "Ticket is assigned to another moderator");
                    }
                }
            }
            }
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO moderator_pending_actions
                        (moderator_telegram_id, action_type, ticket_id, target_id, target_status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(moderator_telegram_id) DO UPDATE SET
                        action_type = excluded.action_type,
                        ticket_id = excluded.ticket_id,
                        target_id = excluded.target_id,
                        target_status = excluded.target_status,
                        created_at = excluded.created_at,
                        expires_at = excluded.expires_at
                    """)) {
                statement.setLong(1, moderator.telegramId());
                statement.setString(2, type.name());
                if (ticketId <= 0) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, ticketId);
                if (targetId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, targetId);
                if (targetStatus == null) statement.setNull(5, java.sql.Types.VARCHAR);
                else statement.setString(5, targetStatus.name());
                statement.setString(6, now.toString());
                statement.setString(7, now.plus(ACTION_TTL).toString());
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
                    SELECT action_type, ticket_id, target_id, target_status, created_at, expires_at
                    FROM moderator_pending_actions WHERE moderator_telegram_id = ?
                    """)) {
                statement.setLong(1, moderatorTelegramId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    return Optional.of(new ModeratorPendingAction(
                            moderatorTelegramId,
                            ModeratorPendingAction.Type.valueOf(result.getString("action_type")),
                            result.getLong("ticket_id"),
                            nullableLong(result, "target_id"),
                            result.getString("target_status") == null ? null
                                    : TicketStatus.valueOf(result.getString("target_status")),
                            Instant.parse(result.getString("created_at")),
                            Instant.parse(result.getString("expires_at"))));
                }
            }
        });
    }

    private static Long nullableLong(ResultSet result, String column) throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
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
