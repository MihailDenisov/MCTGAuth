package dev.example.mcauth.backend;

import java.time.Instant;

public record ModeratorPendingAction(
        long moderatorTelegramId,
        Type type,
        long ticketId,
        Instant createdAt,
        Instant expiresAt) {

    public enum Type {
        REPLY_TO_TICKET
    }
}
