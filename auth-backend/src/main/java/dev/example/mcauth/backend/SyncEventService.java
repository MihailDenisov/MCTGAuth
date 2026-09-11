package dev.example.mcauth.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.api.SyncEventType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SyncEventService {
    private static final int MAX_BATCH = 100;

    private final BackendDatabase database;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;

    public SyncEventService(BackendDatabase database, ObjectMapper json, AuditService audit, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.json = Objects.requireNonNull(json, "json");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    UUID enqueue(
            Connection connection,
            SyncEventType type,
            Map<String, ?> payload,
            Long accountId,
            Long actorTelegramId,
            String actorUsername,
            Instant now) throws SQLException, JsonProcessingException {
        UUID eventId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sync_events (event_id, event_type, schema_version, payload_json, created_at)
                VALUES (?, ?, 1, ?, ?)
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, type.name());
            statement.setString(3, json.writeValueAsString(payload));
            statement.setString(4, now.toString());
            statement.executeUpdate();
        }
        audit.record(connection, "SYNC_EVENT_CREATED", null, accountId, actorTelegramId,
                actorUsername, now, Map.of("eventId", eventId.toString(), "type", type.name()));
        return eventId;
    }

    public List<SyncEvent> pendingEvents() {
        return database.transaction(connection -> {
            List<SyncEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id, event_type, schema_version, payload_json, created_at
                    FROM sync_events
                    WHERE acknowledged_at IS NULL
                    ORDER BY created_at, event_id
                    LIMIT ?
                    """)) {
                statement.setInt(1, MAX_BATCH);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        events.add(new SyncEvent(
                                UUID.fromString(result.getString("event_id")),
                                SyncEventType.valueOf(result.getString("event_type")),
                                result.getInt("schema_version"),
                                result.getString("payload_json"),
                                Instant.parse(result.getString("created_at"))));
                    }
                }
            }
            return List.copyOf(events);
        });
    }

    public boolean acknowledge(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            boolean exists;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT acknowledged_at FROM sync_events WHERE event_id = ?")) {
                select.setString(1, eventId.toString());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        return false;
                    }
                    exists = true;
                    if (result.getString("acknowledged_at") != null) {
                        return true;
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE sync_events SET acknowledged_at = ? WHERE event_id = ? AND acknowledged_at IS NULL
                    """)) {
                update.setString(1, now.toString());
                update.setString(2, eventId.toString());
                update.executeUpdate();
            }
            audit.record(connection, "SYNC_EVENT_ACKNOWLEDGED", null, null, null, "", now,
                    Map.of("eventId", eventId.toString()));
            return exists;
        });
    }
}
