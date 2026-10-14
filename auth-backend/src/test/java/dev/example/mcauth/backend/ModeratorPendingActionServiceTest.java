package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeratorPendingActionServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void registrationRejectionReasonRequiresAssignedModeratorAndPersistsDedicatedAction() throws Exception {
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("pending-registration-rejection.db"))) {
            long secondModeratorId = 1002L;
            context.moderators.synchronizeConfiguredModerators(
                    Set.of(BackendTestContext.MODERATOR_ID, secondModeratorId));
            long ticketId = context.registrations.submitRegistration(new RegistrationSubmission(
                    7020L, "registration_owner", "RejectedOwner", true, 25, "Experience"));
            context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement("""
                        UPDATE tickets SET status = 'IN_PROGRESS', assigned_moderator_telegram_id = ?
                        WHERE id = ?
                        """)) {
                    statement.setLong(1, BackendTestContext.MODERATOR_ID);
                    statement.setLong(2, ticketId);
                    statement.executeUpdate();
                }
                return null;
            });
            ModeratorIdentity assigned = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "assigned_mod");
            ModeratorIdentity other = new ModeratorIdentity(secondModeratorId, "other_mod");

            WorkflowException unauthorized = assertThrows(WorkflowException.class,
                    () -> context.pendingModeratorActions.beginRegistrationRejectionReason(other, ticketId));
            assertEquals(WorkflowException.Code.UNAUTHORIZED, unauthorized.code());

            context.pendingModeratorActions.beginRegistrationRejectionReason(assigned, ticketId);
            ModeratorPendingAction pending = context.pendingModeratorActions
                    .find(assigned.telegramId()).orElseThrow();
            assertEquals(ModeratorPendingAction.Type.REGISTRATION_REJECTION_REASON, pending.type());
            assertEquals(ticketId, pending.ticketId());
            assertEquals(null, pending.targetStatus());
            assertTrue(context.pendingModeratorActions.find(other.telegramId()).isEmpty());
        }
    }

    @Test
    void pendingRepliesAreIsolatedPerModeratorAndExpire() throws Exception {
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("pending-actions.db"))) {
            long secondModeratorId = 1002L;
            context.moderators.synchronizeConfiguredModerators(
                    Set.of(BackendTestContext.MODERATOR_ID, secondModeratorId));
            long firstAccount = context.register(7001L, "OwnerOne");
            long secondAccount = context.register(7002L, "OwnerTwo");
            long firstTicket = context.tickets.createProblem(
                    7001L, "one", firstAccount, "First", List.of());
            long secondTicket = context.tickets.createProblem(
                    7002L, "two", secondAccount, "Second", List.of());
            ModeratorIdentity first = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "first_mod");
            ModeratorIdentity second = new ModeratorIdentity(secondModeratorId, "second_mod");
            context.tickets.claim(firstTicket, first);
            context.tickets.claim(secondTicket, second);

            assertThrows(WorkflowException.class,
                    () -> context.pendingModeratorActions.beginReply(second, firstTicket));

            context.pendingModeratorActions.beginReply(first, firstTicket);
            context.pendingModeratorActions.beginReply(second, secondTicket);

            assertEquals(firstTicket, context.pendingModeratorActions
                    .find(first.telegramId()).orElseThrow().ticketId());
            assertEquals(secondTicket, context.pendingModeratorActions
                    .find(second.telegramId()).orElseThrow().ticketId());

            context.pendingModeratorActions.clear(first.telegramId());
            assertTrue(context.pendingModeratorActions.find(first.telegramId()).isEmpty());
            assertEquals(secondTicket, context.pendingModeratorActions
                    .find(second.telegramId()).orElseThrow().ticketId());

            context.clock.advance(Duration.ofMinutes(31));
            assertTrue(context.pendingModeratorActions.find(second.telegramId()).isEmpty());
        }
    }

    @Test
    void pendingClosureStoresTargetAndCancelLeavesTicketUnchanged() throws Exception {
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("pending-closure.db"))) {
            long account = context.register(7010L, "ClosureOwner");
            long ticket = context.tickets.createProblem(
                    7010L, "owner", account, "Problem", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticket, moderator);

            context.pendingModeratorActions.beginClosureComment(
                    moderator, ticket, TicketStatus.RESOLVED);
            ModeratorPendingAction pending = context.pendingModeratorActions
                    .find(moderator.telegramId()).orElseThrow();
            assertEquals(ModeratorPendingAction.Type.TICKET_CLOSURE_COMMENT, pending.type());
            assertEquals(TicketStatus.RESOLVED, pending.targetStatus());

            context.pendingModeratorActions.clear(moderator.telegramId());
            assertTrue(context.pendingModeratorActions.find(moderator.telegramId()).isEmpty());
            assertEquals(TicketStatus.IN_PROGRESS,
                    context.tickets.ticketForUser(7010L, ticket).ticket().status());
        }
    }
}
