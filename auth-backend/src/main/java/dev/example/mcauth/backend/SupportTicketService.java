package dev.example.mcauth.backend;

import dev.example.mcauth.api.AccountCreationMethod;
import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.api.TicketStatus;
import dev.example.mcauth.api.TicketType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SupportTicketService {
    public static final int PAGE_SIZE = 5;
    private static final int MAX_DESCRIPTION = 4_000;
    private static final int MAX_MESSAGE = 4_000;
    private static final int MAX_ATTACHMENTS = 10;

    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final CredentialService credentials;
    private final SyncEventService syncEvents;
    private final AuditService audit;
    private final Clock clock;

    public SupportTicketService(
            BackendDatabase database,
            ModeratorService moderators,
            CredentialService credentials,
            SyncEventService syncEvents,
            AuditService audit,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.syncEvents = Objects.requireNonNull(syncEvents, "syncEvents");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long createProblem(
            long telegramId, String username, long accountId, String description,
            List<AttachmentReference> attachments) {
        String safeDescription = requiredText(description, MAX_DESCRIPTION, "Problem description");
        List<AttachmentReference> safeAttachments = validateAttachments(attachments);
        return database.transaction(connection -> {
            requireOwnedAccount(connection, telegramId, accountId);
            Instant now = Instant.now(clock);
            upsertUser(connection, telegramId, username, now);
            long ticketId = insertTicket(connection, TicketType.PROBLEM, telegramId, username, accountId, now);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO problem_ticket_data (ticket_id, description) VALUES (?, ?)")) {
                statement.setLong(1, ticketId);
                statement.setString(2, safeDescription);
                statement.executeUpdate();
            }
            insertAttachments(connection, ticketId, safeAttachments, now);
            audit.record(connection, "TICKET_CREATED", ticketId, accountId, telegramId, username, now,
                    Map.of("ticketType", TicketType.PROBLEM.name(), "attachments", safeAttachments.size()));
            return ticketId;
        });
    }

    public long createViolation(
            long telegramId, String username, long accountId, String offenderNickname,
            String description, List<AttachmentReference> attachments) {
        String offender = AccountService.validateNickname(offenderNickname);
        String safeDescription = requiredText(description, MAX_DESCRIPTION, "Violation description");
        List<AttachmentReference> safeAttachments = validateAttachments(attachments);
        return database.transaction(connection -> {
            requireOwnedAccount(connection, telegramId, accountId);
            Instant now = Instant.now(clock);
            upsertUser(connection, telegramId, username, now);
            long ticketId = insertTicket(connection, TicketType.VIOLATION, telegramId, username, accountId, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO violation_ticket_data
                        (ticket_id, offender_nickname, normalized_offender_nickname, description)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setLong(1, ticketId);
                statement.setString(2, offender);
                statement.setString(3, offender.toLowerCase(Locale.ROOT));
                statement.setString(4, safeDescription);
                statement.executeUpdate();
            }
            insertAttachments(connection, ticketId, safeAttachments, now);
            audit.record(connection, "TICKET_CREATED", ticketId, accountId, telegramId, username, now,
                    Map.of("ticketType", TicketType.VIOLATION.name(), "attachments", safeAttachments.size()));
            return ticketId;
        });
    }

    public long createAddAccountRequest(
            long telegramId, String username, String nickname, boolean licensed) {
        String displayName = AccountService.validateNickname(nickname);
        String normalized = displayName.toLowerCase(Locale.ROOT);
        return database.transaction(connection -> {
            if (!hasAccount(connection, telegramId)) {
                throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                        "Only registered users can request another Minecraft account");
            }
            AccountService.requireNicknameAvailable(connection, normalized);
            requireNoPendingNickname(connection, normalized);
            Instant now = Instant.now(clock);
            upsertUser(connection, telegramId, username, now);
            long ticketId = insertTicket(connection, TicketType.ADD_MINECRAFT_ACCOUNT,
                    telegramId, username, null, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO add_account_ticket_data
                        (ticket_id, minecraft_nickname, normalized_nickname, licensed)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setLong(1, ticketId);
                statement.setString(2, displayName);
                statement.setString(3, normalized);
                statement.setInt(4, licensed ? 1 : 0);
                statement.executeUpdate();
            }
            audit.record(connection, "TICKET_CREATED", ticketId, null, telegramId, username, now,
                    Map.of("ticketType", TicketType.ADD_MINECRAFT_ACCOUNT.name()));
            return ticketId;
        });
    }

    public Optional<TicketListItem> pendingRegistration(long telegramId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM tickets
                    WHERE creator_telegram_id = ? AND type = 'REGISTRATION'
                      AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER')
                    ORDER BY id DESC LIMIT 1
                    """)) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapTicket(result)) : Optional.empty();
                }
            }
        });
    }

    public List<TicketListItem> ticketsForUser(long telegramId, int page) {
        if (page < 0) throw invalid("Page must not be negative");
        return database.transaction(connection -> listTickets(connection,
                "WHERE creator_telegram_id = ?", statement -> statement.setLong(1, telegramId), page));
    }

    public long ticketCountForUser(long telegramId) {
        return database.transaction(connection -> scalarLong(connection,
                "SELECT COUNT(*) FROM tickets WHERE creator_telegram_id = ?", telegramId));
    }

    public TicketDetail ticketForUser(long telegramId, long ticketId) {
        return database.transaction(connection -> loadDetail(connection, ticketId, telegramId, false));
    }

    public TicketDetail ticketForModerator(ModeratorIdentity moderator, long ticketId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            return loadDetail(connection, ticketId, null, true);
        });
    }

    public List<TicketListItem> adminTickets(
            ModeratorIdentity moderator, TicketStatus status, TicketType type, int page) {
        if (page < 0) throw invalid("Page must not be negative");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            List<TicketListItem> tickets = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM tickets WHERE 1=1");
            if (status != null) sql.append(" AND status = ?");
            if (type != null) sql.append(" AND type = ?");
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                if (status != null) statement.setString(index++, status.name());
                if (type != null) statement.setString(index++, type.name());
                statement.setInt(index++, PAGE_SIZE);
                statement.setInt(index, page * PAGE_SIZE);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) tickets.add(mapTicket(result));
                }
            }
            return List.copyOf(tickets);
        });
    }

    public boolean claim(long ticketId, ModeratorIdentity moderator) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_ASSIGN);
            TicketListItem ticket = loadTicket(connection, ticketId);
            if (ticket.status() == TicketStatus.IN_PROGRESS
                    && Objects.equals(ticket.assignedModeratorId(), moderator.telegramId())) return true;
            if (ticket.status() != TicketStatus.OPEN) {
                throw invalidState("Only an OPEN ticket can be taken");
            }
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tickets SET status = 'IN_PROGRESS', assigned_moderator_telegram_id = ?,
                        assigned_at = ?, updated_at = ? WHERE id = ? AND status = 'OPEN'
                    """)) {
                statement.setLong(1, moderator.telegramId());
                statement.setString(2, now.toString());
                statement.setString(3, now.toString());
                statement.setLong(4, ticketId);
                if (statement.executeUpdate() != 1) throw invalidState("Ticket state changed; refresh it");
            }
            audit.record(connection, "TICKET_ASSIGNED", ticketId, ticket.affectedAccountId(),
                    moderator.telegramId(), moderator.usernameSnapshot(), now, Map.of());
            return true;
        });
    }

    public void moderatorReply(long ticketId, ModeratorIdentity moderator, String text) {
        String message = requiredText(text, MAX_MESSAGE, "Reply");
        database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_REPLY);
            TicketListItem ticket = loadTicket(connection, ticketId);
            if (ticket.status() != TicketStatus.IN_PROGRESS && ticket.status() != TicketStatus.WAITING_USER) {
                throw invalidState("Ticket is not open for conversation");
            }
            Instant now = Instant.now(clock);
            insertMessage(connection, ticketId, "MODERATOR", moderator.telegramId(), message, now);
            updateStatus(connection, ticketId, TicketStatus.WAITING_USER, now);
            audit.record(connection, "TICKET_MESSAGE_MODERATOR", ticketId, ticket.affectedAccountId(),
                    moderator.telegramId(), moderator.usernameSnapshot(), now, Map.of());
            return null;
        });
    }

    public void userReply(long telegramId, long ticketId, String text) {
        String message = requiredText(text, MAX_MESSAGE, "Reply");
        database.transaction(connection -> {
            TicketListItem ticket = loadTicket(connection, ticketId);
            if (ticket.creatorTelegramId() != telegramId) {
                throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Ticket is not owned by this user");
            }
            if (ticket.status() != TicketStatus.WAITING_USER) {
                throw invalidState("Ticket is not waiting for a user reply");
            }
            Instant now = Instant.now(clock);
            insertMessage(connection, ticketId, "USER", telegramId, message, now);
            updateStatus(connection, ticketId, TicketStatus.IN_PROGRESS, now);
            audit.record(connection, "TICKET_MESSAGE_USER", ticketId, ticket.affectedAccountId(),
                    telegramId, "", now, Map.of());
            return null;
        });
    }

    public void transition(long ticketId, ModeratorIdentity moderator, TicketStatus target) {
        database.transaction(connection -> {
            ModeratorPermission permission = target == TicketStatus.REJECTED
                    ? ModeratorPermission.TICKET_REJECT : ModeratorPermission.TICKET_RESOLVE;
            moderators.requirePermission(connection, moderator, permission);
            TicketListItem ticket = loadTicket(connection, ticketId);
            if (!allowed(ticket.status(), target)) {
                throw invalidState("Invalid ticket transition: " + ticket.status() + " -> " + target);
            }
            Instant now = Instant.now(clock);
            updateStatus(connection, ticketId, target, now);
            audit.record(connection, target == TicketStatus.REJECTED ? "TICKET_REJECTED" : "TICKET_RESOLVED",
                    ticketId, ticket.affectedAccountId(), moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("from", ticket.status().name(), "to", target.name()));
            return null;
        });
    }

    public TicketDecisionResult approveAddAccount(long ticketId, ModeratorIdentity moderator) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_RESOLVE);
            AddAccountTicket ticket = loadAddAccount(connection, ticketId);
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.REJECTED) {
                return new TicketDecisionResult(TicketDecisionResult.Outcome.ALREADY_DECIDED,
                        ticketId, ticket.ownerTelegramId(), ticket.nickname(), null);
            }
            if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.IN_PROGRESS) {
                throw invalidState("Add-account ticket cannot be approved from " + ticket.status());
            }
            AccountService.requireNicknameAvailable(connection, ticket.normalizedNickname());
            Instant now = Instant.now(clock);
            char[] password = credentials.generatePassword();
            try {
                String verifier = credentials.hash(password);
                long accountId = AccountService.insertAccount(connection, ticket.ownerTelegramId(),
                        ticket.nickname(), ticket.normalizedNickname(), ticket.licensed(),
                        AccountCreationMethod.ADD_ACCOUNT_TICKET, moderator.telegramId(), verifier, now);
                recordDecision(connection, ticketId, moderator, "APPROVED", now);
                updateStatus(connection, ticketId, TicketStatus.RESOLVED, now);
                syncEvents.enqueue(connection, SyncEventType.ACCOUNT_CREATED,
                        AccountService.accountPayload(accountId, ticket.nickname(), ticket.normalizedNickname(),
                                ticket.licensed(), true, 1, verifier),
                        accountId, moderator.telegramId(), moderator.usernameSnapshot(), now);
                audit.record(connection, "MINECRAFT_ACCOUNT_CREATED", ticketId, accountId,
                        moderator.telegramId(), moderator.usernameSnapshot(), now,
                        Map.of("creationMethod", AccountCreationMethod.ADD_ACCOUNT_TICKET.name()));
                return new TicketDecisionResult(TicketDecisionResult.Outcome.APPROVED,
                        ticketId, ticket.ownerTelegramId(), ticket.nickname(), password);
            } catch (Exception exception) {
                Arrays.fill(password, '\0');
                throw exception;
            }
        });
    }

    public TicketDecisionResult rejectAddAccount(long ticketId, ModeratorIdentity moderator) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_REJECT);
            AddAccountTicket ticket = loadAddAccount(connection, ticketId);
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.REJECTED) {
                return new TicketDecisionResult(TicketDecisionResult.Outcome.ALREADY_DECIDED,
                        ticketId, ticket.ownerTelegramId(), ticket.nickname(), null);
            }
            if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.IN_PROGRESS) {
                throw invalidState("Add-account ticket cannot be rejected from " + ticket.status());
            }
            Instant now = Instant.now(clock);
            recordDecision(connection, ticketId, moderator, "REJECTED", now);
            updateStatus(connection, ticketId, TicketStatus.REJECTED, now);
            audit.record(connection, "TICKET_REJECTED", ticketId, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("ticketType", TicketType.ADD_MINECRAFT_ACCOUNT.name()));
            return new TicketDecisionResult(TicketDecisionResult.Outcome.REJECTED,
                    ticketId, ticket.ownerTelegramId(), ticket.nickname(), null);
        });
    }

    public List<TicketListItem> search(ModeratorIdentity moderator, String query) {
        String value = requiredText(query, 100, "Search query");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            Long numeric = parseLong(value);
            String like = "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
            List<TicketListItem> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT t.* FROM tickets t
                    LEFT JOIN registration_ticket_data r ON r.ticket_id = t.id
                    LEFT JOIN add_account_ticket_data a ON a.ticket_id = t.id
                    LEFT JOIN minecraft_accounts m ON m.owner_telegram_id = t.creator_telegram_id
                    LEFT JOIN users u ON u.telegram_id = t.creator_telegram_id
                    WHERE (? IS NOT NULL AND (t.id = ? OR t.creator_telegram_id = ?))
                       OR lower(t.creator_username_snapshot) LIKE ? ESCAPE '\\'
                       OR r.normalized_nickname LIKE ? ESCAPE '\\'
                       OR a.normalized_nickname LIKE ? ESCAPE '\\'
                       OR m.normalized_nickname LIKE ? ESCAPE '\\'
                    ORDER BY t.id DESC LIMIT 20
                    """)) {
                if (numeric == null) {
                    statement.setNull(1, java.sql.Types.BIGINT);
                    statement.setNull(2, java.sql.Types.BIGINT);
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(1, numeric);
                    statement.setLong(2, numeric);
                    statement.setLong(3, numeric);
                }
                for (int index = 4; index <= 7; index++) statement.setString(index, like);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) results.add(mapTicket(result));
                }
            }
            return List.copyOf(results);
        });
    }

    public TicketStatistics statistics(ModeratorIdentity moderator) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) total,
                      SUM(status = 'OPEN') open_count,
                      SUM(status = 'IN_PROGRESS') in_progress_count,
                      SUM(status = 'RESOLVED') resolved_count,
                      SUM(status = 'REJECTED') rejected_count,
                      SUM(type = 'REGISTRATION') registrations,
                      SUM(type = 'PROBLEM') problems,
                      SUM(type = 'VIOLATION') violations,
                      SUM(type = 'ADD_MINECRAFT_ACCOUNT') add_accounts,
                      SUM(created_at >= ?) last_24h,
                      SUM(created_at >= ?) last_7d
                    FROM tickets
                    """)) {
                statement.setString(1, now.minus(Duration.ofHours(24)).toString());
                statement.setString(2, now.minus(Duration.ofDays(7)).toString());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return new TicketStatistics(result.getLong("total"), result.getLong("open_count"),
                            result.getLong("in_progress_count"), result.getLong("resolved_count"),
                            result.getLong("rejected_count"), result.getLong("registrations"),
                            result.getLong("problems"), result.getLong("violations"),
                            result.getLong("add_accounts"), result.getLong("last_24h"),
                            result.getLong("last_7d"));
                }
            }
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
                if (statement.executeUpdate() != 1) throw notFound();
            }
            return null;
        });
    }

    private static long insertTicket(
            Connection connection, TicketType type, long telegramId, String username,
            Long accountId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tickets
                    (type, status, creator_telegram_id, creator_username_snapshot,
                     affected_account_id, created_at, updated_at)
                VALUES (?, 'OPEN', ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, type.name());
            statement.setLong(2, telegramId);
            statement.setString(3, safeUsername(username));
            if (accountId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, accountId);
            statement.setString(5, now.toString());
            statement.setString(6, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Ticket insert returned no ID");
                return keys.getLong(1);
            }
        }
    }

    private static void upsertUser(
            Connection connection, long telegramId, String username, Instant now) throws SQLException {
        if (telegramId <= 0) throw invalid("Invalid Telegram identity");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (telegram_id, username_snapshot, account_status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET username_snapshot = excluded.username_snapshot,
                    updated_at = excluded.updated_at
                """)) {
            statement.setLong(1, telegramId);
            statement.setString(2, safeUsername(username));
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.executeUpdate();
        }
    }

    private static void requireOwnedAccount(Connection connection, long telegramId, long accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM minecraft_accounts WHERE id = ? AND owner_telegram_id = ? AND enabled = 1")) {
            statement.setLong(1, accountId);
            statement.setLong(2, telegramId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED,
                        "The selected Minecraft account is not available");
            }
        }
    }

    private static boolean hasAccount(Connection connection, long telegramId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM minecraft_accounts WHERE owner_telegram_id = ? AND enabled = 1 LIMIT 1")) {
            statement.setLong(1, telegramId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static void requireNoPendingNickname(Connection connection, String normalized) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM add_account_ticket_data a JOIN tickets t ON t.id = a.ticket_id
                WHERE a.normalized_nickname = ? AND t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER')
                """)) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new WorkflowException(WorkflowException.Code.CONFLICT,
                        "An active request already exists for that nickname");
            }
        }
    }

    private static void insertAttachments(
            Connection connection, long ticketId, List<AttachmentReference> attachments, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ticket_attachments
                    (ticket_id, telegram_file_id, telegram_file_unique_id, file_type, caption, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (AttachmentReference attachment : attachments) {
                statement.setLong(1, ticketId);
                statement.setString(2, attachment.fileId());
                statement.setString(3, attachment.fileUniqueId());
                statement.setString(4, attachment.fileType());
                statement.setString(5, attachment.caption());
                statement.setString(6, now.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<AttachmentReference> validateAttachments(List<AttachmentReference> attachments) {
        if (attachments == null) return List.of();
        if (attachments.size() > MAX_ATTACHMENTS) throw invalid("A ticket can contain at most 10 attachments");
        List<AttachmentReference> validated = new ArrayList<>();
        for (AttachmentReference attachment : attachments) {
            if (attachment == null || attachment.fileId() == null || attachment.fileId().isBlank()
                    || attachment.fileUniqueId() == null || attachment.fileUniqueId().isBlank()) {
                throw invalid("Attachment identifiers are required");
            }
            String type = attachment.fileType() == null ? "" : attachment.fileType().toUpperCase(Locale.ROOT);
            if (!List.of("PHOTO", "VIDEO", "DOCUMENT", "ANIMATION").contains(type)) {
                throw invalid("Unsupported attachment type");
            }
            validated.add(new AttachmentReference(attachment.fileId(), attachment.fileUniqueId(), type,
                    optionalText(attachment.caption(), 1_000, "Attachment caption")));
        }
        return List.copyOf(validated);
    }

    private static void insertMessage(
            Connection connection, long ticketId, String senderType, Long senderId, String text, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ticket_messages
                    (ticket_id, sender_type, sender_telegram_id, message_text, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, ticketId);
            statement.setString(2, senderType);
            if (senderId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, senderId);
            statement.setString(4, text);
            statement.setString(5, now.toString());
            statement.executeUpdate();
        }
    }

    private static void updateStatus(Connection connection, long ticketId, TicketStatus status, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tickets SET status = ?, updated_at = ?, closed_at = ? WHERE id = ?
                """)) {
            statement.setString(1, status.name());
            statement.setString(2, now.toString());
            if (status == TicketStatus.CLOSED || status == TicketStatus.REJECTED) {
                statement.setString(3, now.toString());
            } else {
                statement.setNull(3, java.sql.Types.VARCHAR);
            }
            statement.setLong(4, ticketId);
            statement.executeUpdate();
        }
    }

    private static boolean allowed(TicketStatus from, TicketStatus to) {
        return switch (from) {
            case OPEN -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.REJECTED;
            case IN_PROGRESS -> to == TicketStatus.WAITING_USER || to == TicketStatus.RESOLVED
                    || to == TicketStatus.REJECTED;
            case WAITING_USER -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.REJECTED;
            case RESOLVED, REJECTED -> to == TicketStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    private static TicketListItem loadTicket(Connection connection, long ticketId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM tickets WHERE id = ?")) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw notFound();
                return mapTicket(result);
            }
        }
    }

    private static TicketDetail loadDetail(
            Connection connection, long ticketId, Long ownerId, boolean moderator) throws SQLException {
        TicketListItem ticket = loadTicket(connection, ticketId);
        if (!moderator && !Objects.equals(ownerId, ticket.creatorTelegramId())) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Ticket is not owned by this user");
        }
        Map<String, String> fields = loadFields(connection, ticket);
        List<TicketMessageView> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM ticket_messages WHERE ticket_id = ? ORDER BY id
                """)) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long senderId = result.getLong("sender_telegram_id");
                    messages.add(new TicketMessageView(result.getLong("id"), result.getString("sender_type"),
                            result.wasNull() ? null : senderId, result.getString("message_text"),
                            Instant.parse(result.getString("created_at"))));
                }
            }
        }
        List<AttachmentReference> attachments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT telegram_file_id, telegram_file_unique_id, file_type, caption
                FROM ticket_attachments WHERE ticket_id = ? ORDER BY id
                """)) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) attachments.add(new AttachmentReference(
                        result.getString(1), result.getString(2), result.getString(3), result.getString(4)));
            }
        }
        return new TicketDetail(ticket, Map.copyOf(fields), List.copyOf(messages), List.copyOf(attachments));
    }

    private static Map<String, String> loadFields(Connection connection, TicketListItem ticket) throws SQLException {
        Map<String, String> fields = new LinkedHashMap<>();
        String sql = switch (ticket.type()) {
            case REGISTRATION -> "SELECT minecraft_nickname, licensed, age, experience FROM registration_ticket_data WHERE ticket_id = ?";
            case PROBLEM -> "SELECT description FROM problem_ticket_data WHERE ticket_id = ?";
            case VIOLATION -> "SELECT offender_nickname, description FROM violation_ticket_data WHERE ticket_id = ?";
            case ADD_MINECRAFT_ACCOUNT -> "SELECT minecraft_nickname, licensed FROM add_account_ticket_data WHERE ticket_id = ?";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ticket.id());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    java.sql.ResultSetMetaData metadata = result.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        fields.put(metadata.getColumnName(index), result.getString(index));
                    }
                }
            }
        }
        return fields;
    }

    private static List<TicketListItem> listTickets(
            Connection connection, String where, StatementBinder binder, int page) throws SQLException {
        List<TicketListItem> tickets = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM tickets " + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
            binder.bind(statement);
            statement.setInt(2, PAGE_SIZE);
            statement.setInt(3, page * PAGE_SIZE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) tickets.add(mapTicket(result));
            }
        }
        return List.copyOf(tickets);
    }

    private static TicketListItem mapTicket(ResultSet result) throws SQLException {
        long accountId = result.getLong("affected_account_id");
        Long account = result.wasNull() ? null : accountId;
        long moderatorId = result.getLong("assigned_moderator_telegram_id");
        Long moderator = result.wasNull() ? null : moderatorId;
        return new TicketListItem(result.getLong("id"), TicketType.valueOf(result.getString("type")),
                TicketStatus.valueOf(result.getString("status")), result.getLong("creator_telegram_id"),
                result.getString("creator_username_snapshot"), account, moderator,
                Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")));
    }

    private static AddAccountTicket loadAddAccount(Connection connection, long ticketId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.status, t.creator_telegram_id, a.minecraft_nickname, a.normalized_nickname, a.licensed
                FROM tickets t JOIN add_account_ticket_data a ON a.ticket_id = t.id
                WHERE t.id = ? AND t.type = 'ADD_MINECRAFT_ACCOUNT'
                """)) {
            statement.setLong(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw notFound();
                return new AddAccountTicket(TicketStatus.valueOf(result.getString(1)), result.getLong(2),
                        result.getString(3), result.getString(4), result.getInt(5) == 1);
            }
        }
    }

    private static void recordDecision(
            Connection connection, long ticketId, ModeratorIdentity moderator, String decision, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ticket_decisions
                    (ticket_id, decision, moderator_telegram_id, moderator_username_snapshot, comment, decided_at)
                VALUES (?, ?, ?, ?, '', ?)
                """)) {
            statement.setLong(1, ticketId);
            statement.setString(2, decision);
            statement.setLong(3, moderator.telegramId());
            statement.setString(4, moderator.usernameSnapshot());
            statement.setString(5, now.toString());
            statement.executeUpdate();
        }
    }

    private static long scalarLong(Connection connection, String sql, long argument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, argument);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private static String safeUsername(String username) {
        return optionalText(username, 64, "Telegram username");
    }

    private static String requiredText(String value, int maximum, String field) {
        String result = optionalText(value, maximum, field);
        if (result.isEmpty()) throw invalid(field + " is required");
        return result;
    }

    private static String optionalText(String value, int maximum, String field) {
        String result = value == null ? "" : value.strip();
        if (result.length() > maximum) throw invalid(field + " is too long");
        return result;
    }

    private static Long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return null; }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }

    private static WorkflowException invalidState(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_STATE, message);
    }

    private static WorkflowException notFound() {
        return new WorkflowException(WorkflowException.Code.NOT_FOUND, "Ticket not found");
    }

    @FunctionalInterface
    private interface StatementBinder { void bind(PreparedStatement statement) throws SQLException; }

    private record AddAccountTicket(
            TicketStatus status, long ownerTelegramId, String nickname,
            String normalizedNickname, boolean licensed) { }
}
