package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RemoteCommandService {
    private static final int MAX_PAYLOAD_LENGTH = 4_096;
    private static final int MAX_RESULT_LENGTH = 8_192;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Set<String> SENSITIVE_FIELD_PARTS = Set.of(
            "token", "password", "verifier", "authorization", "secret", "proof");

    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final String configuredServerId;

    public RemoteCommandService(
            BackendDatabase database,
            ModeratorService moderators,
            AuditService audit,
            ObjectMapper json,
            Clock clock,
            String configuredServerId) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuredServerId = requireServerId(configuredServerId);
    }

    public RemoteCommand create(
            ModeratorIdentity moderator, RemoteCommandType type, String payloadJson) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(type, "type");
        JsonNode payload = parseAndValidatePayload(type, payloadJson);
        String normalizedPayload = compact(payload);
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, permissionFor(type));
            Instant now = Instant.now(clock);
            Instant expiresAt = now.plus(ttlFor(type));
            UUID id = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO remote_commands
                        (public_id, server_id, command_type, schema_version, payload_json, status,
                         created_at, expires_at, created_by_telegram_id)
                    VALUES (?, ?, ?, 1, ?, 'PENDING', ?, ?, ?)
                    """)) {
                statement.setString(1, id.toString());
                statement.setString(2, configuredServerId);
                statement.setString(3, type.name());
                statement.setString(4, normalizedPayload);
                statement.setString(5, now.toString());
                statement.setString(6, expiresAt.toString());
                statement.setLong(7, moderator.telegramId());
                statement.executeUpdate();
            }
            audit.record(connection, "REMOTE_COMMAND_CREATED", null, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now,
                    Map.of("commandId", id.toString(), "type", type.name(), "serverId", configuredServerId));
            return new RemoteCommand(id, configuredServerId, type, 1, normalizedPayload,
                    RemoteCommandStatus.PENDING, now, expiresAt);
        });
    }

    public List<RemoteCommand> pendingForServer(String serverId) {
        requireMatchingServer(serverId);
        return database.transaction(connection -> {
            expirePending(connection, Instant.now(clock));
            List<RemoteCommand> commands = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT public_id, server_id, command_type, schema_version, payload_json,
                           status, created_at, expires_at
                    FROM remote_commands
                    WHERE server_id = ? AND status IN ('PENDING', 'RUNNING')
                    ORDER BY id
                    LIMIT 100
                    """)) {
                statement.setString(1, serverId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        commands.add(map(result));
                    }
                }
            }
            return List.copyOf(commands);
        });
    }

    public boolean markStarted(String serverId, UUID commandId) {
        requireMatchingServer(serverId);
        Objects.requireNonNull(commandId, "commandId");
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            expirePending(connection, now);
            RemoteCommandStatus status = status(connection, serverId, commandId);
            if (status == null) {
                return false;
            }
            if (status == RemoteCommandStatus.RUNNING) {
                return true;
            }
            if (status != RemoteCommandStatus.PENDING) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE remote_commands SET status = 'RUNNING', picked_up_at = ?
                    WHERE public_id = ? AND server_id = ? AND status = 'PENDING'
                    """)) {
                statement.setString(1, now.toString());
                statement.setString(2, commandId.toString());
                statement.setString(3, serverId);
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            audit.record(connection, "REMOTE_COMMAND_STARTED", null, null, null, "", now,
                    Map.of("commandId", commandId.toString(), "serverId", serverId));
            return true;
        });
    }

    public boolean complete(
            String serverId, UUID commandId, RemoteCommandStatus finalStatus,
            String resultJson, String errorMessage) {
        requireMatchingServer(serverId);
        Objects.requireNonNull(commandId, "commandId");
        if (finalStatus != RemoteCommandStatus.SUCCESS && finalStatus != RemoteCommandStatus.FAILED) {
            throw new WorkflowException(WorkflowException.Code.INVALID_INPUT,
                    "Command result status must be SUCCESS or FAILED");
        }
        String safeResult = validateSafeJson(resultJson, MAX_RESULT_LENGTH, "result");
        String safeError = sanitizeError(errorMessage);
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            expirePending(connection, now);
            RemoteCommandStatus current = status(connection, serverId, commandId);
            if (current == null) {
                return false;
            }
            if (current == finalStatus) {
                return true;
            }
            if (current != RemoteCommandStatus.RUNNING) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE remote_commands
                    SET status = ?, completed_at = ?, result_json = ?, error_message = ?
                    WHERE public_id = ? AND server_id = ? AND status = 'RUNNING'
                    """)) {
                statement.setString(1, finalStatus.name());
                statement.setString(2, now.toString());
                statement.setString(3, safeResult);
                statement.setString(4, safeError);
                statement.setString(5, commandId.toString());
                statement.setString(6, serverId);
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            audit.record(connection,
                    finalStatus == RemoteCommandStatus.SUCCESS
                            ? "REMOTE_COMMAND_SUCCEEDED" : "REMOTE_COMMAND_FAILED",
                    null, null, null, "", now,
                    Map.of("commandId", commandId.toString(), "serverId", serverId));
            return true;
        });
    }

    private void expirePending(Connection connection, Instant now) throws Exception {
        List<String> expiredIds = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT public_id FROM remote_commands
                WHERE server_id = ? AND status IN ('PENDING', 'RUNNING') AND expires_at <= ?
                """)) {
            select.setString(1, configuredServerId);
            select.setString(2, now.toString());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    expiredIds.add(result.getString(1));
                }
            }
        }
        if (expiredIds.isEmpty()) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE remote_commands SET status = 'EXPIRED', completed_at = ?
                WHERE public_id = ? AND status IN ('PENDING', 'RUNNING')
                """)) {
            for (String id : expiredIds) {
                update.setString(1, now.toString());
                update.setString(2, id);
                update.addBatch();
            }
            update.executeBatch();
        }
        for (String id : expiredIds) {
            audit.record(connection, "REMOTE_COMMAND_EXPIRED", null, null, null, "", now,
                    Map.of("commandId", id, "serverId", configuredServerId));
        }
    }

    private static RemoteCommandStatus status(Connection connection, String serverId, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM remote_commands WHERE public_id = ? AND server_id = ?
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? RemoteCommandStatus.valueOf(result.getString(1)) : null;
            }
        }
    }

    private static RemoteCommand map(ResultSet result) throws SQLException {
        return new RemoteCommand(
                UUID.fromString(result.getString("public_id")),
                result.getString("server_id"),
                RemoteCommandType.valueOf(result.getString("command_type")),
                result.getInt("schema_version"),
                result.getString("payload_json"),
                RemoteCommandStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("expires_at")));
    }

    private JsonNode parseAndValidatePayload(RemoteCommandType type, String payloadJson) {
        String safeJson = validateSafeJson(payloadJson, MAX_PAYLOAD_LENGTH, "payload");
        try {
            JsonNode payload = json.readTree(safeJson);
            if (!payload.isObject()) {
                throw invalid("Command payload must be a JSON object");
            }
            switch (type) {
                case FORCE_AUTH_SYNC, AUTH_STATUS -> requireExactFields(payload, Set.of());
                case RESYNC_PLAYER -> {
                    requireExactFields(payload, Set.of("accountId"));
                    if (!payload.path("accountId").canConvertToLong() || payload.path("accountId").asLong() <= 0) {
                        throw invalid("RESYNC_PLAYER requires a positive accountId");
                    }
                }
                default -> throw invalid("Remote command type is not enabled in this phase");
            }
            return payload;
        } catch (java.io.IOException exception) {
            throw invalid("Command payload is not valid JSON");
        }
    }

    private String validateSafeJson(String value, int limit, String label) {
        String source = value == null || value.isBlank() ? "{}" : value.strip();
        if (source.length() > limit) {
            throw invalid("Command " + label + " is too large");
        }
        try {
            JsonNode node = json.readTree(source);
            if (containsSensitiveField(node)) {
                throw invalid("Command " + label + " contains a forbidden sensitive field");
            }
            return compact(node);
        } catch (java.io.IOException exception) {
            throw invalid("Command " + label + " is not valid JSON");
        }
    }

    private boolean containsSensitiveField(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String lower = field.getKey().toLowerCase(Locale.ROOT);
                if (SENSITIVE_FIELD_PARTS.stream().anyMatch(lower::contains)
                        || containsSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveField(child)) return true;
            }
        }
        return false;
    }

    private String compact(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("Could not encode command JSON");
        }
    }

    private static void requireExactFields(JsonNode payload, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        payload.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid("Command payload fields do not match the command schema");
        }
    }

    private static String sanitizeError(String error) {
        String safe = error == null ? "" : error.strip();
        if (safe.length() > MAX_ERROR_LENGTH) {
            safe = safe.substring(0, MAX_ERROR_LENGTH);
        }
        String lower = safe.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELD_PARTS.stream().anyMatch(lower::contains)) {
            return "Remote command failed; sensitive details were redacted";
        }
        return safe;
    }

    private static ModeratorPermission permissionFor(RemoteCommandType type) {
        return switch (type) {
            case FORCE_AUTH_SYNC -> ModeratorPermission.AUTH_SYNC_FORCE;
            case AUTH_STATUS -> ModeratorPermission.SERVER_STATUS;
            case RESYNC_PLAYER -> ModeratorPermission.AUTH_PLAYER_RESYNC;
            default -> throw invalid("Remote command type is not enabled in this phase");
        };
    }

    private static Duration ttlFor(RemoteCommandType type) {
        return switch (type) {
            case FORCE_AUTH_SYNC, AUTH_STATUS -> Duration.ofMinutes(5);
            case RESYNC_PLAYER -> Duration.ofMinutes(10);
            default -> throw invalid("Remote command type is not enabled in this phase");
        };
    }

    private static String requireServerId(String serverId) {
        String normalized = serverId == null ? "" : serverId.strip();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw invalid("SERVER_ID must contain only letters, digits, dot, underscore, or hyphen");
        }
        return normalized;
    }

    private void requireMatchingServer(String serverId) {
        if (!configuredServerId.equals(requireServerId(serverId))) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Server is not authorized");
        }
    }

    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }
}
