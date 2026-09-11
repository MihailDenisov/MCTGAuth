package dev.example.mcauth.api;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_USER,
    RESOLVED,
    REJECTED,
    CLOSED;

    public boolean isTerminalDecision() {
        return this == RESOLVED || this == REJECTED || this == CLOSED;
    }
}
