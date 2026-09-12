package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAuthAccountStoreTest {
    @TempDir Path temporary;

    @Test
    void atomicallyPersistsAccountsAndProcessedEventsAcrossReload() {
        Path path = temporary.resolve("accounts.json");
        JsonAuthAccountStore store = new JsonAuthAccountStore(path);
        store.open();
        UUID eventId = UUID.randomUUID();
        AuthAccount account = new AuthAccount(42, "Test_Player", "test_player", null,
                "$argon2id$v=19$m=8192,t=1,p=1$MDEyMzQ1Njc4OWFiY2RlZg$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                3, true, false, Instant.parse("2026-09-12T12:00:00Z"));
        assertEquals(AuthAccountStore.SyncApplyResult.APPLIED, store.applySyncEvent(eventId,
                SyncEventType.ACCOUNT_CREATED, Instant.now(), accounts -> accounts.put("test_player", account)));
        assertTrue(Files.exists(path));
        assertFalse(Files.exists(path.resolveSibling("accounts.json.tmp")));
        store.close();

        JsonAuthAccountStore reloaded = new JsonAuthAccountStore(path);
        reloaded.open();
        assertEquals(42, reloaded.findByMinecraftName("TEST_PLAYER").orElseThrow().accountId());
        assertEquals(AuthAccountStore.SyncApplyResult.ALREADY_PROCESSED, reloaded.applySyncEvent(eventId,
                SyncEventType.ACCOUNT_CREATED, Instant.now(), accounts -> accounts.clear()));
        assertEquals(1, reloaded.accountCount());
        reloaded.close();
    }

    @Test
    void corruptExistingStoreIsPreservedAndFailsClosed() throws Exception {
        Path path = temporary.resolve("accounts.json");
        Files.writeString(path, "not-json");
        JsonAuthAccountStore store = new JsonAuthAccountStore(path);
        assertThrows(IllegalStateException.class, store::open);
        assertEquals("not-json", Files.readString(path));
    }
}
