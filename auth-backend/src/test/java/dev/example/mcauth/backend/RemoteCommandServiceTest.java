package dev.example.mcauth.backend;

import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCommandServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsTypedCommandAndCompletesIdempotently() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("commands.db"))) {
            var command = context.commands.create(new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.RESYNC_PLAYER, "{\"accountId\":42}");
            assertEquals(1, context.commands.pendingForServer("survival-main").size());
            assertThrows(WorkflowException.class, () -> context.commands.pendingForServer("other-server"));
            assertTrue(context.commands.markStarted("survival-main", command.id()));
            assertTrue(context.commands.markStarted("survival-main", command.id()));
            assertTrue(context.commands.complete("survival-main", command.id(),
                    RemoteCommandStatus.SUCCESS, "{\"applied\":1}", ""));
            assertTrue(context.commands.complete("survival-main", command.id(),
                    RemoteCommandStatus.SUCCESS, "{\"applied\":1}", ""));
            assertEquals(0, context.commands.pendingForServer("survival-main").size());
        }
    }

    @Test
    void rejectsUnknownPayloadAndExpiresCommand() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("expiry.db"))) {
            assertThrows(WorkflowException.class, () -> context.commands.create(
                    new ModeratorIdentity(9999L, "intruder"), RemoteCommandType.AUTH_STATUS, "{}"));
            assertThrows(WorkflowException.class, () -> context.commands.create(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.RELOAD_AUTH_CACHE, "{}"));
            assertThrows(WorkflowException.class, () -> context.commands.create(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.FORCE_AUTH_SYNC, "{\"commandText\":\"stop\"}"));
            var command = context.commands.create(new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.FORCE_AUTH_SYNC, "{}");
            context.clock.advance(Duration.ofMinutes(6));
            assertEquals(0, context.commands.pendingForServer("survival-main").size());
            assertFalse(context.commands.markStarted("survival-main", command.id()));
        }
    }
}
