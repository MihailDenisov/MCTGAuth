package dev.example.mcauth.backend;

import java.time.Instant;

public record TicketMessageView(
        long id,
        String senderType,
        Long senderTelegramId,
        String text,
        Instant createdAt) {
}
