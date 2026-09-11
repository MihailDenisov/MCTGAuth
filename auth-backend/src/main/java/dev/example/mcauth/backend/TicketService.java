package dev.example.mcauth.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.api.TicketStatus;
import dev.example.mcauth.api.TicketType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TicketService {
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_EXPERIENCE_LENGTH = 2_000;
    private static final int MAX_COMMENT_LENGTH = 1_000;
    private static final int MAX_REGISTRATION_TICKETS_PER_HOUR = 3;

    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final CredentialService credentials;
    private final ObjectMapper json;
    private final Clock clock;

    public TicketService(
            BackendDatabase database,
            ModeratorService moderators,
            CredentialService credentials,
            ObjectMapper json,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long submitRegistration(RegistrationSubmission submission) {
        ValidatedRegistration valid = validate(submission);
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            upsertUser(connection, valid, now);
            requireRegistrationAvailable(connection, valid.telegramId(), valid.normalizedNickname(), now);

            long ticketId;
            String insertTicket = """
                    INSERT INTO tickets
                        (type, status, creator_telegram_id, creator_username_snapshot, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(
                    insertTicket, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, TicketType.REGISTRATION.name());
                statement.setString(2, TicketStatus.OPEN.name());
                statement.setLong(3, valid.telegramId());
                statement.setString(4, valid.usernameSnapshot());
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Ticket insert returned no ID");
                    }
                    ticketId = keys.getLong(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO registration_ticket_data
                        (ticket_id, minecraft_nickname, normalized_nickname, licensed, age, experience)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, ticketId);
                statement.setString(2, valid.minecraftNickname());
                statement.setString(3, valid.normalizedNickname());
                statement.setInt(4, valid.licensed() ? 1 : 0);
                statement.setInt(5, valid.age());
                statement.setString(6, valid.experience());
                statement.executeUpdate();
            }
            audit(connection, "TICKET_CREATED", ticketId, null, valid.telegramId(),
                    valid.usernameSnapshot(), now, Map.of("ticketType", TicketType.REGISTRATION.name()));
            return ticketId;
        });
    }

    public TicketDecisionResult approveRegistration(long ticketId, ModeratorIdentity moderator) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_RESOLVE);
            RegistrationTicket ticket = loadRegistration(connection, ticketId);
            TicketDecisionResult existing = existingDecision(connection, ticket, ticketId);
            if (existing != null) {
                return existing;
            }
            if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.IN_PROGRESS) {
                throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                        "Ticket cannot be approved from status " + ticket.status());
            }
            requireNicknameUnowned(connection, ticket.normalizedNickname());

            char[] password = credentials.generatePassword();
            try {
                String verifier = credentials.hash(password);
                Instant now = Instant.now(clock);
                long accountId = insertAccount(connection, ticket, moderator, verifier, now);
                recordDecision(connection, ticketId, moderator, "APPROVED", "", now);
                updateTicketDecisionState(connection, ticketId, TicketStatus.RESOLVED, moderator.telegramId(), now);
                insertSyncEvent(connection, accountId, ticket, verifier, now);
                audit(connection, "MINECRAFT_ACCOUNT_CREATED", ticketId, accountId, moderator.telegramId(),
                        moderator.usernameSnapshot(), now, Map.of("creationMethod", "REGISTRATION_TICKET"));
                audit(connection, "REGISTRATION_APPROVED", ticketId, accountId, moderator.telegramId(),
                        moderator.usernameSnapshot(), now, Map.of("credentialVersion", 1));
                return new TicketDecisionResult(TicketDecisionResult.Outcome.APPROVED, ticketId,
                        ticket.creatorTelegramId(), ticket.nickname(), password);
            } catch (Exception exception) {
                Arrays.fill(password, '\0');
                throw exception;
            }
        });
    }

    public TicketDecisionResult rejectRegistration(
            long ticketId, ModeratorIdentity moderator, String comment) {
        String safeComment = normalizeBounded(comment, MAX_COMMENT_LENGTH, "comment");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_REJECT);
            RegistrationTicket ticket = loadRegistration(connection, ticketId);
            TicketDecisionResult existing = existingDecision(connection, ticket, ticketId);
            if (existing != null) {
                return existing;
            }
            if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.IN_PROGRESS) {
                throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                        "Ticket cannot be rejected from status " + ticket.status());
            }
            Instant now = Instant.now(clock);
            recordDecision(connection, ticketId, moderator, "REJECTED", safeComment, now);
            updateTicketDecisionState(connection, ticketId, TicketStatus.REJECTED, moderator.telegramId(), now);
            audit(connection, "REGISTRATION_REJECTED", ticketId, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("hasComment", !safeComment.isEmpty()));
            return new TicketDecisionResult(TicketDecisionResult.Outcome.REJECTED, ticketId,
                    ticket.creatorTelegramId(), ticket.nickname(), null);
        });
    }

    public void attachAdminProjection(long ticketId, long chatId, long messageId) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tickets SET admin_chat_id = ?, admin_message_id = ?, updated_at = ? WHERE id = ?
                    """)) {
                statement.setLong(1, chatId);
                statement.setLong(2, messageId);
                statement.setString(3, Instant.now(clock).toString());
                statement.setLong(4, ticketId);
                if (statement.executeUpdate() != 1) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "Ticket not found");
                }
            }
            return null;
        });
    }

    private ValidatedRegistration validate(RegistrationSubmission submission) {
        if (submission == null || submission.telegramId() <= 0) {
            throw invalid("Invalid Telegram identity");
        }
        String nickname = submission.minecraftNickname() == null ? "" : submission.minecraftNickname().trim();
        if (!MINECRAFT_NAME.matcher(nickname).matches()) {
            throw invalid("Minecraft nickname must contain 3-16 letters, digits, or underscores");
        }
        if (submission.age() < 1 || submission.age() > 120) {
            throw invalid("Age must be between 1 and 120");
        }
        String experience = normalizeBounded(submission.experience(), MAX_EXPERIENCE_LENGTH, "experience");
        if (experience.isEmpty()) {
            throw invalid("Experience answer is required");
        }
        String username = normalizeBounded(submission.usernameSnapshot(), 64, "username");
        return new ValidatedRegistration(submission.telegramId(), username, nickname,
                nickname.toLowerCase(Locale.ROOT), submission.licensed(), submission.age(), experience);
    }

    private static String normalizeBounded(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maximumLength) {
            throw invalid(field + " is too long");
        }
        return normalized;
    }

    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }

    private static void upsertUser(Connection connection, ValidatedRegistration registration, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (telegram_id, username_snapshot, account_status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    username_snapshot = excluded.username_snapshot,
                    updated_at = excluded.updated_at
                """)) {
            statement.setLong(1, registration.telegramId());
            statement.setString(2, registration.usernameSnapshot());
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.executeUpdate();
        }
    }

    private static void requireRegistrationAvailable(
            Connection connection, long telegramId, String normalizedNickname, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM minecraft_accounts
                WHERE owner_telegram_id = ? OR normalized_nickname = ? LIMIT 1
                """)) {
            statement.setLong(1, telegramId);
            statement.setString(2, normalizedNickname);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new WorkflowException(WorkflowException.Code.CONFLICT,
                            "A Minecraft account is already registered for this user or nickname");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM tickets t
                JOIN registration_ticket_data r ON r.ticket_id = t.id
                WHERE t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER')
                  AND (t.creator_telegram_id = ? OR r.normalized_nickname = ?)
                LIMIT 1
                """)) {
            statement.setLong(1, telegramId);
            statement.setString(2, normalizedNickname);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new WorkflowException(WorkflowException.Code.CONFLICT,
                            "An active registration ticket already exists for this user or nickname");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM tickets
                WHERE creator_telegram_id = ? AND type = 'REGISTRATION' AND created_at >= ?
                """)) {
            statement.setLong(1, telegramId);
            statement.setString(2, now.minusSeconds(3_600).toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) >= MAX_REGISTRATION_TICKETS_PER_HOUR) {
                    throw new WorkflowException(WorkflowException.Code.CONFLICT,
                            "Registration ticket rate limit reached; try again later");
                }
            }
        }
    }

    private static void requireNicknameUnowned(Connection connection, String normalizedNickname) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM minecraft_accounts WHERE normalized_nickname = ? LIMIT 1")) {
            statement.setString(1, normalizedNickname);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new WorkflowException(WorkflowException.Code.CONFLICT,
                            "That Minecraft nickname is already registered");
                }
            }
        }
    }

    private static RegistrationTicket loadRegistration(Connection connection, long ticketId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.status, t.creator_telegram_id, r.minecraft_nickname, r.normalized_nickname, r.licensed
                FROM tickets t
                JOIN registration_ticket_data r ON r.ticket_id = t.id
                WHERE t.id = ? AND t.type = 'REGISTRATION'
                """)) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND,
                            "Registration ticket not found");
                }
                return new RegistrationTicket(
                        TicketStatus.valueOf(result.getString("status")),
                        result.getLong("creator_telegram_id"),
                        result.getString("minecraft_nickname"),
                        result.getString("normalized_nickname"),
                        result.getInt("licensed") == 1);
            }
        }
    }

    private static TicketDecisionResult existingDecision(
            Connection connection, RegistrationTicket ticket, long ticketId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT decision FROM ticket_decisions WHERE ticket_id = ?")) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new TicketDecisionResult(TicketDecisionResult.Outcome.ALREADY_DECIDED, ticketId,
                        ticket.creatorTelegramId(), ticket.nickname(), null);
            }
        }
    }

    private static long insertAccount(
            Connection connection,
            RegistrationTicket ticket,
            ModeratorIdentity moderator,
            String verifier,
            Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO minecraft_accounts
                    (owner_telegram_id, nickname, normalized_nickname, licensed, enabled,
                     creation_method, creator_moderator_id, credential_verifier, credential_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'REGISTRATION_TICKET', ?, ?, 1, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, ticket.creatorTelegramId());
            statement.setString(2, ticket.nickname());
            statement.setString(3, ticket.normalizedNickname());
            statement.setInt(4, ticket.licensed() ? 1 : 0);
            statement.setLong(5, moderator.telegramId());
            statement.setString(6, verifier);
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Account insert returned no ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void recordDecision(
            Connection connection, long ticketId, ModeratorIdentity moderator,
            String decision, String comment, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ticket_decisions
                    (ticket_id, decision, moderator_telegram_id, moderator_username_snapshot, comment, decided_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, ticketId);
            statement.setString(2, decision);
            statement.setLong(3, moderator.telegramId());
            statement.setString(4, moderator.usernameSnapshot());
            statement.setString(5, comment);
            statement.setString(6, now.toString());
            statement.executeUpdate();
        }
    }

    private static void updateTicketDecisionState(
            Connection connection, long ticketId, TicketStatus status, long moderatorId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tickets
                SET status = ?, assigned_moderator_telegram_id = ?, updated_at = ?, closed_at = ?
                WHERE id = ?
                """)) {
            statement.setString(1, status.name());
            statement.setLong(2, moderatorId);
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.setLong(5, ticketId);
            statement.executeUpdate();
        }
    }

    private void insertSyncEvent(
            Connection connection, long accountId, RegistrationTicket ticket, String verifier, Instant now)
            throws SQLException, JsonProcessingException {
        String payload = json.writeValueAsString(Map.of(
                "accountId", accountId,
                "nickname", ticket.nickname(),
                "normalizedNickname", ticket.normalizedNickname(),
                "licensed", ticket.licensed(),
                "enabled", true,
                "credentialVersion", 1,
                "credentialVerifier", verifier));
        String eventId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sync_events (event_id, event_type, schema_version, payload_json, created_at)
                VALUES (?, 'ACCOUNT_CREATED', 1, ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, payload);
            statement.setString(3, now.toString());
            statement.executeUpdate();
        }
        audit(connection, "SYNC_EVENT_CREATED", null, accountId, null, "", now,
                Map.of("eventId", eventId, "type", "ACCOUNT_CREATED"));
    }

    private void audit(
            Connection connection,
            String eventType,
            Long ticketId,
            Long accountId,
            Long actorTelegramId,
            String actorUsername,
            Instant now,
            Map<String, ?> details) throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_log
                    (event_type, ticket_id, account_id, actor_telegram_id,
                     actor_username_snapshot, occurred_at, details_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventType);
            if (ticketId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, ticketId);
            if (accountId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, accountId);
            if (actorTelegramId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, actorTelegramId);
            statement.setString(5, actorUsername == null ? "" : actorUsername);
            statement.setString(6, now.toString());
            statement.setString(7, json.writeValueAsString(details));
            statement.executeUpdate();
        }
    }

    private record ValidatedRegistration(
            long telegramId,
            String usernameSnapshot,
            String minecraftNickname,
            String normalizedNickname,
            boolean licensed,
            int age,
            String experience) {
    }

    private record RegistrationTicket(
            TicketStatus status,
            long creatorTelegramId,
            String nickname,
            String normalizedNickname,
            boolean licensed) {
    }
}
