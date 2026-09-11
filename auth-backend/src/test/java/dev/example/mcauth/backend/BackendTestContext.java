package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Set;

final class BackendTestContext implements AutoCloseable {
    static final long MODERATOR_ID = 1001L;

    final BackendDatabase database;
    final MutableClock clock;
    final ModeratorService moderators;
    final CredentialService credentials;
    final AuditService audit;
    final SyncEventService syncEvents;
    final AccountService accounts;
    final TicketService registrations;
    final SupportTicketService tickets;
    final RemoteCommandService commands;

    BackendTestContext(Path databasePath) throws Exception {
        this.database = BackendDatabase.open(databasePath);
        this.clock = new MutableClock(java.time.Instant.parse("2026-09-11T12:00:00Z"));
        ObjectMapper json = new ObjectMapper();
        this.moderators = new ModeratorService(database, clock);
        moderators.synchronizeConfiguredModerators(Set.of(MODERATOR_ID));
        this.credentials = new CredentialService();
        this.audit = new AuditService(database, json, clock);
        this.syncEvents = new SyncEventService(database, json, audit, clock);
        this.accounts = new AccountService(database, credentials, syncEvents, audit, moderators, clock);
        this.registrations = new TicketService(database, moderators, credentials, json, clock);
        this.tickets = new SupportTicketService(database, moderators, credentials, syncEvents, audit, clock);
        this.commands = new RemoteCommandService(database, moderators, audit, json, clock, "survival-main");
    }

    long register(long telegramId, String nickname) {
        long ticketId = registrations.submitRegistration(new RegistrationSubmission(
                telegramId, "user" + telegramId, nickname, true, 25, "Modpack experience"));
        try (TicketDecisionResult result = registrations.approveRegistration(
                ticketId, new ModeratorIdentity(MODERATOR_ID, "moderator"))) {
            return accounts.accountsFor(telegramId).getFirst().id();
        }
    }

    @Override public void close() throws Exception { database.close(); }
}
