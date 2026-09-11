package dev.example.mcauth.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RemoteCommand(
        UUID id,
        String serverId,
        RemoteCommandType type,
        int schemaVersion,
        String payloadJson,
        RemoteCommandStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public RemoteCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (serverId.isBlank() || schemaVersion <= 0 || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid remote command envelope");
        }
    }
}
