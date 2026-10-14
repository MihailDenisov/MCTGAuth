package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TelegramUserModerationServiceTest {
    @TempDir Path temporary;

    @Test
    void blockIsAuditedIdempotentAndIndependentFromMinecraftEnablement() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("blocked.db"))) {
            long user = 8001L;
            long account = context.register(user, "Notch");
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "moderator");
            assertTrue(context.userModeration.setBlocked(moderator, user, true, "spam").changed());
            assertTrue(context.userModeration.isBlocked(user));
            assertFalse(context.userModeration.setBlocked(moderator, user, true, "repeat").changed());
            assertTrue(context.accounts.accountsFor(user).stream().filter(a -> a.id() == account).findFirst().orElseThrow().enabled());
            assertTrue(context.userModeration.setBlocked(moderator, user, false, "").changed());
            assertFalse(context.userModeration.isBlocked(user));
            long audits = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM audit_log WHERE event_type IN
                        ('TELEGRAM_USER_BLOCKED','TELEGRAM_USER_UNBLOCKED')
                        """); var result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
            });
            assertEquals(2, audits);

            long unregistered = 8002L;
            context.accounts.touchTelegramUser(unregistered, "ticket_spammer", "en");
            assertTrue(context.userModeration.setBlocked(moderator, unregistered, true, "registration spam").changed());
            assertTrue(context.userModeration.isBlocked(unregistered));
        }
    }
}
