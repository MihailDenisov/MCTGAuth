package dev.example.mcauth.backend;

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
}
