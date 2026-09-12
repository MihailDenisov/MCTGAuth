package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupportTicketServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void problemAttachmentsAndConversationAreDurableAndOwned() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("tickets.db"))) {
            long accountId = context.register(2001L, "Reporter");
            long ticketId = context.tickets.createProblem(2001L, "reporter", accountId, "Cannot join",
                    List.of(new AttachmentReference("file-id", "unique-id", "PHOTO", "Screenshot")));

            TicketDetail created = context.tickets.ticketForUser(2001L, ticketId);
            assertEquals(1, created.attachments().size());
            assertEquals("Cannot join", created.fields().get("description"));
            assertThrows(WorkflowException.class, () -> context.tickets.ticketForUser(9999L, ticketId));

            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod");
            context.tickets.claim(ticketId, moderator);
            context.tickets.moderatorReply(ticketId, moderator, "Please send the log.");
            context.tickets.userReply(2001L, ticketId, "Here are the details.");
            context.tickets.transition(ticketId, moderator, TicketStatus.RESOLVED);

            TicketDetail resolved = context.tickets.ticketForUser(2001L, ticketId);
            assertEquals(TicketStatus.RESOLVED, resolved.ticket().status());
            assertEquals(2, resolved.messages().size());
        }
    }

    @Test
    void addAccountRequiresApprovalAndRejectsDuplicateNickname() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("accounts.db"))) {
            context.register(2002L, "MainPlayer");
            long ticketId = context.tickets.createAddAccountRequest(2002L, "owner", "SecondPlayer", false);
            try (TicketDecisionResult result = context.tickets.approveAddAccount(ticketId,
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"))) {
                assertEquals(TicketDecisionResult.Outcome.APPROVED, result.outcome());
            }
            assertEquals(2, context.accounts.accountsFor(2002L).size());
            assertThrows(WorkflowException.class,
                    () -> context.tickets.createAddAccountRequest(2002L, "owner", "secondplayer", true));
        }
    }

    @Test
    void assignedAddAccountTicketCannotBeDecidedByAnotherModerator() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("assigned.db"))) {
            long otherModeratorId = 1002L;
            context.moderators.synchronizeConfiguredModerators(
                    Set.of(BackendTestContext.MODERATOR_ID, otherModeratorId));
            context.register(2004L, "OwnerPlayer");
            long ticketId = context.tickets.createAddAccountRequest(
                    2004L, "owner", "AnotherPlayer", true);
            context.tickets.claim(ticketId,
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "assigned_mod"));

            WorkflowException exception = assertThrows(WorkflowException.class,
                    () -> context.tickets.approveAddAccount(ticketId,
                            new ModeratorIdentity(otherModeratorId, "other_mod")));

            assertEquals(WorkflowException.Code.UNAUTHORIZED, exception.code());
        }
    }

    @Test
    void violationUsesGlobalTicketSequenceAndStoresEvidenceReference() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("violation.db"))) {
            long accountId = context.register(2003L, "Witness");
            long problemId = context.tickets.createProblem(2003L, "witness", accountId,
                    "First ticket", List.of());
            long violationId = context.tickets.createViolation(2003L, "witness", accountId,
                    "Offender", "Griefing", List.of(
                            new AttachmentReference("video-id", "video-unique", "VIDEO", "Evidence")));

            assertEquals(problemId + 1, violationId);
            TicketDetail detail = context.tickets.ticketForUser(2003L, violationId);
            assertEquals("Offender", detail.fields().get("offender_nickname"));
            assertEquals(1, detail.attachments().size());

            long unknownId = context.tickets.createViolation(
                    2003L, "witness", accountId, null, "Unknown player", List.of());
            assertEquals("", context.tickets.ticketForUser(2003L, unknownId)
                    .fields().get("offender_nickname"));
            int storedAsNull = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT offender_nickname IS NULL
                        FROM violation_ticket_data WHERE ticket_id = ?
                        """)) {
                    statement.setLong(1, unknownId);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        return result.getInt(1);
                    }
                }
            });
            assertEquals(1, storedAsNull);
        }
    }
}
