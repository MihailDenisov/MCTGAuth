package dev.example.mcauth.backend;

import java.util.Arrays;

public final class TicketDecisionResult implements AutoCloseable {
    public enum Outcome {
        APPROVED,
        REJECTED,
        ALREADY_DECIDED
    }

    private final Outcome outcome;
    private final long ticketId;
    private final long creatorTelegramId;
    private final String nickname;
    private final char[] initialPassword;

    TicketDecisionResult(
            Outcome outcome,
            long ticketId,
            long creatorTelegramId,
            String nickname,
            char[] initialPassword) {
        this.outcome = outcome;
        this.ticketId = ticketId;
        this.creatorTelegramId = creatorTelegramId;
        this.nickname = nickname;
        this.initialPassword = initialPassword;
    }

    public Outcome outcome() {
        return outcome;
    }

    public long ticketId() {
        return ticketId;
    }

    public long creatorTelegramId() {
        return creatorTelegramId;
    }

    public String nickname() {
        return nickname;
    }

    public char[] copyInitialPassword() {
        return initialPassword == null ? null : Arrays.copyOf(initialPassword, initialPassword.length);
    }

    @Override
    public void close() {
        if (initialPassword != null) {
            Arrays.fill(initialPassword, '\0');
        }
    }
}
