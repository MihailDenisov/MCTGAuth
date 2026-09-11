package dev.example.mcauth.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SyncEvent(
        UUID id,
        SyncEventType type,
        int schemaVersion,
        String payloadJson,
        Instant createdAt) {

    public SyncEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }
}
