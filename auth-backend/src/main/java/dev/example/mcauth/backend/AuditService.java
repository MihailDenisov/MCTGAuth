package dev.example.mcauth.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class AuditService {
    private final BackendDatabase database;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(BackendDatabase database, ObjectMapper json, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void record(
            String eventType, Long ticketId, Long accountId, Long actorTelegramId,
            String actorUsername, Map<String, ?> details) {
        database.transaction(connection -> {
            record(connection, eventType, ticketId, accountId, actorTelegramId,
                    actorUsername, Instant.now(clock), details);
            return null;
        });
    }

    void record(
            Connection connection,
            String eventType,
            Long ticketId,
            Long accountId,
            Long actorTelegramId,
            String actorUsername,
            Instant occurredAt,
            Map<String, ?> details) throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_log
                    (event_type, ticket_id, account_id, actor_telegram_id,
                     actor_username_snapshot, occurred_at, details_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventType);
            setNullableLong(statement, 2, ticketId);
            setNullableLong(statement, 3, accountId);
            setNullableLong(statement, 4, actorTelegramId);
            statement.setString(5, actorUsername == null ? "" : actorUsername);
            statement.setString(6, occurredAt.toString());
            statement.setString(7, json.writeValueAsString(details == null ? Map.of() : details));
            statement.executeUpdate();
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
