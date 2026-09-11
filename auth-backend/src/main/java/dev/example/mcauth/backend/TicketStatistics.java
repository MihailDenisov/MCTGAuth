package dev.example.mcauth.backend;

public record TicketStatistics(
        long total,
        long open,
        long inProgress,
        long resolved,
        long rejected,
        long registrations,
        long problems,
        long violations,
        long addAccountRequests,
        long last24Hours,
        long last7Days) {
}
