package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;
import dev.example.mcauth.api.TicketType;

import java.time.Instant;

public record TicketListItem(
        long id,
        TicketType type,
        TicketStatus status,
        long creatorTelegramId,
        String creatorUsername,
        Long affectedAccountId,
        Long assignedModeratorId,
        Instant createdAt,
        Instant updatedAt) {
}
