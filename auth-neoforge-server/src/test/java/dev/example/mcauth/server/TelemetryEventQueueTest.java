package dev.example.mcauth.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryEventQueueTest {
    @TempDir Path temporary;

    @Test
    void journalSurvivesRestartPreservesOrderAndIsBounded() throws Exception {
        Path path = temporary.resolve("telemetry-events.json");
        TelemetryEventQueue first = new TelemetryEventQueue(path, 2);
        first.open();
        TelemetryPlayerEvent one = event("PLAYER_JOINED", 1);
        TelemetryPlayerEvent two = event("PLAYER_LEFT", 1);
        assertTrue(first.add(one));
        assertTrue(first.add(two));
        assertFalse(first.add(event("PLAYER_JOINED", 2)));

        TelemetryEventQueue reopened = new TelemetryEventQueue(path, 2);
        reopened.open();
        assertEquals(one.eventId(), reopened.peek().eventId());
        reopened.removeFirst();
        assertEquals(two.eventId(), reopened.peek().eventId());
        reopened.removeFirst();
        assertNull(reopened.peek());
    }

    private static TelemetryPlayerEvent event(String type, long accountId) {
        return new TelemetryPlayerEvent(UUID.randomUUID(), "survival-main", UUID.randomUUID(), type,
                accountId, "Player" + accountId, Instant.parse("2026-09-18T10:00:00Z"));
    }
}
