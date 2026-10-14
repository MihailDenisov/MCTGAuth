package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void closureCommentIsPublicHistoryAndAuditWithoutDuplicatingText() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("closure.db"))) {
            long accountId = context.register(2010L, "ClosureOwner");
            long ticketId = context.tickets.createProblem(
                    2010L, "owner", accountId, "Need help", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod");
            context.tickets.claim(ticketId, moderator);

            context.tickets.closeWithOptionalComment(
                    ticketId, moderator, TicketStatus.RESOLVED, "Fixed after restart.");

            TicketDetail detail = context.tickets.ticketForUser(2010L, ticketId);
            assertEquals(TicketStatus.RESOLVED, detail.ticket().status());
            assertEquals(1, detail.messages().size());
            TicketMessageView comment = detail.messages().getFirst();
            assertEquals("CLOSURE_COMMENT", comment.messageType());
            assertEquals("PUBLIC", comment.visibility());
            assertEquals(BackendTestContext.MODERATOR_ID, comment.senderTelegramId());
            assertEquals("Fixed after restart.", comment.text());

            String audit = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT actor_telegram_id, details_json FROM audit_log
                        WHERE ticket_id = ? AND event_type = 'TICKET_RESOLVED'
                        ORDER BY id DESC LIMIT 1
                        """)) {
                    statement.setLong(1, ticketId);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        assertEquals(BackendTestContext.MODERATOR_ID, result.getLong(1));
                        return result.getString(2);
                    }
                }
            });
            assertTrue(audit.contains("\"closingCommentPresent\":true"));
            assertFalse(audit.contains("Fixed after restart."));
        }
    }

    @Test
    void closureWithoutCommentAndFinalCloseRemainIdempotent() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("closure-no-comment.db"))) {
            long secondModeratorId = 1002L;
            context.moderators.synchronizeConfiguredModerators(
                    Set.of(BackendTestContext.MODERATOR_ID, secondModeratorId));
            long accountId = context.register(2011L, "NoCommentOwner");
            long ticketId = context.tickets.createProblem(
                    2011L, "owner", accountId, "Need help", List.of());
            ModeratorIdentity first = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "first");
            context.tickets.claim(ticketId, first);
            context.tickets.closeWithOptionalComment(ticketId, first, TicketStatus.RESOLVED, null);
            assertTrue(context.tickets.ticketForUser(2011L, ticketId).messages().isEmpty());

            WorkflowException stale = assertThrows(WorkflowException.class,
                    () -> context.tickets.closeWithOptionalComment(ticketId,
                            new ModeratorIdentity(secondModeratorId, "second"),
                            TicketStatus.RESOLVED, "Must not overwrite"));
            assertEquals(WorkflowException.Code.INVALID_STATE, stale.code());
            assertTrue(context.tickets.ticketForUser(2011L, ticketId).messages().isEmpty());

            context.tickets.closeWithOptionalComment(
                    ticketId, first, TicketStatus.CLOSED, "Archived.");
            TicketDetail closed = context.tickets.ticketForUser(2011L, ticketId);
            assertEquals(TicketStatus.CLOSED, closed.ticket().status());
            assertEquals("Archived.", closed.messages().getFirst().text());
        }
    }

    @Test
    void unauthorizedClosureIsRejected() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("closure-auth.db"))) {
            long accountId = context.register(2012L, "AuthOwner");
            long ticketId = context.tickets.createProblem(
                    2012L, "owner", accountId, "Need help", List.of());
            WorkflowException failure = assertThrows(WorkflowException.class,
                    () -> context.tickets.closeWithOptionalComment(ticketId,
                            new ModeratorIdentity(9999L, "not_a_moderator"),
                            TicketStatus.RESOLVED, "No"));
            assertEquals(WorkflowException.Code.UNAUTHORIZED, failure.code());
            assertEquals(TicketStatus.OPEN, context.tickets.ticketForUser(2012L, ticketId).ticket().status());
        }
    }
}
