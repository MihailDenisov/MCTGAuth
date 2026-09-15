package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.api.SyncEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncWorkerTest {
    @TempDir Path temporary;

    @Test
    void persistsAppliedEventBeforeAcknowledgingIt() throws Exception {
        Path storePath = temporary.resolve("accounts.json");
        JsonAuthAccountStore store = new JsonAuthAccountStore(storePath);
        store.open();
        SyncEvent event = new SyncEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_CREATED, 1, """
                {"accountId":1,"nickname":"Persisted","normalizedNickname":"persisted",
                "licensed":false,"enabled":false,"credentialVersion":1,
                "credentialVerifier":"$argon2id$v=19$m=8192,t=1,p=1$MDEyMzQ1Njc4OWFiY2RlZg$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"}
                """, Instant.parse("2026-09-15T12:00:00Z"));
        CountDownLatch acknowledged = new CountDownLatch(1);
        AtomicBoolean persistedBeforeAck = new AtomicBoolean();
        SyncBackend backend = new SyncBackend() {
            private boolean delivered;
            @Override public List<SyncEvent> pendingEvents() {
                if (delivered) return List.of();
                delivered = true;
                return List.of(event);
            }
            @Override public void acknowledge(UUID eventId) throws java.io.IOException {
                String json = Files.readString(storePath);
                persistedBeforeAck.set(json.contains("\"persisted\"")
                        && json.contains(event.id().toString()));
                acknowledged.countDown();
            }
            @Override public void close() { }
        };
        try (SyncWorker worker = new SyncWorker(backend, new SyncEventProcessor(store), new BackendStatus(),
                Clock.systemUTC(), Duration.ofHours(1), ignored -> { })) {
            worker.start();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertTrue(persistedBeforeAck.get());
        } finally {
            store.close();
        }
    }
}
