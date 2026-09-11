package dev.example.mcauth.api;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

public record Ticket(
        long id,
        TicketType type,
        TicketStatus status,
        long creatorTelegramId,
        String creatorUsernameSnapshot,
        Instant createdAt,
        Instant updatedAt,
        Long assignedModeratorTelegramId) {

    public Ticket {
        if (id <= 0 || creatorTelegramId <= 0) {
            throw new IllegalArgumentException("Ticket and Telegram IDs must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        creatorUsernameSnapshot = creatorUsernameSnapshot == null ? "" : creatorUsernameSnapshot;
    }

    public OptionalLong assignedModeratorId() {
        return assignedModeratorTelegramId == null
                ? OptionalLong.empty()
                : OptionalLong.of(assignedModeratorTelegramId);
    }
}
