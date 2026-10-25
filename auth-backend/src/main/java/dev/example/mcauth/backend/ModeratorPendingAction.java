package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;

import java.time.Instant;

public record ModeratorPendingAction(
        long moderatorTelegramId,
        Type type,
        long ticketId,
        Long targetId,
        TicketStatus targetStatus,
        Instant createdAt,
        Instant expiresAt) {

    public enum Type {
        REPLY_TO_TICKET,
        TICKET_CLOSURE_COMMENT,
        REGISTRATION_REJECTION_REASON,
        USER_BAN_REASON,
        DEVICE_BAN_REASON
    }

    public ModeratorPendingAction(long moderatorTelegramId, Type type, long ticketId,
                                  TicketStatus targetStatus, Instant createdAt, Instant expiresAt) {
        this(moderatorTelegramId, type, ticketId, null, targetStatus, createdAt, expiresAt);
    }
}
