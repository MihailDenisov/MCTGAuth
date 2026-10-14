package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerTelemetryServiceTest {
    @TempDir Path temporary;

    @Test
    void repeatedHeartbeatsUpdateOneStatusRowAndPreserveInstanceSemantics() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("heartbeat-upsert.db"))) {
            UUID firstInstance = UUID.randomUUID();
            Instant firstStarted = Instant.parse("2026-09-11T11:00:00Z");
            Instant firstHeartbeat = Instant.parse("2026-09-11T11:55:00Z");

            assertTrue(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", firstInstance, firstStarted, firstHeartbeat, 0, 20)));
            assertStatus(context, firstInstance, firstStarted, firstHeartbeat, 0, 20, null);

            Instant secondHeartbeat = firstHeartbeat.plusSeconds(20);
            assertTrue(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", firstInstance, firstStarted.plusSeconds(5), secondHeartbeat, 3, 30)));
            assertStatus(context, firstInstance, firstStarted, secondHeartbeat, 3, 30, null);

            Instant thirdHeartbeat = secondHeartbeat.plusSeconds(20);
            assertTrue(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", firstInstance, firstStarted, thirdHeartbeat, 7, 40)));
            assertStatus(context, firstInstance, firstStarted, thirdHeartbeat, 7, 40, null);

            Instant shutdownAt = thirdHeartbeat.plusSeconds(1);
            context.telemetry.shutdown("survival-main", firstInstance, shutdownAt);
            assertStatus(context, firstInstance, firstStarted, shutdownAt, 0, 40, shutdownAt);

            Instant resumedHeartbeat = shutdownAt.plusSeconds(1);
            assertTrue(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", firstInstance, firstStarted, resumedHeartbeat, 1, 50)));
            assertStatus(context, firstInstance, firstStarted, resumedHeartbeat, 1, 50, null);

            UUID secondInstance = UUID.randomUUID();
            Instant secondStarted = Instant.parse("2026-09-11T11:58:00Z");
            Instant newInstanceHeartbeat = Instant.parse("2026-09-11T12:00:00Z");
            assertTrue(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", secondInstance, secondStarted, newInstanceHeartbeat, 2, 60)));
            assertStatus(context, secondInstance, secondStarted, newInstanceHeartbeat, 2, 60, null);

            int statusRows = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM server_status WHERE server_id=?")) {
                    statement.setString(1, "survival-main");
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        return result.getInt(1);
                    }
                }
            });
            assertEquals(1, statusRows);
        }
    }

    @Test
    void heartbeatSessionsIdempotencyPlaytimeAndRestartAreHandled() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("telemetry.db"))) {
            long owner = 7001L;
            long account = context.register(owner, "Steve");
            UUID first = UUID.randomUUID();
            Instant started = context.clock.instant().minusSeconds(120);
            context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", first, started, context.clock.instant(), 1, 20));
            assertTrue(context.telemetry.status("survival-main").online());
            UUID joinId = UUID.randomUUID();
            var join = new ServerTelemetryService.PlayerEvent(joinId, "survival-main", first,
                    ServerTelemetryService.PlayerEventType.PLAYER_JOINED, account, "Steve",
                    context.clock.instant().minusSeconds(90));
            assertTrue(context.telemetry.playerEvent(join));
            assertFalse(context.telemetry.playerEvent(join));
            assertEquals(Duration.ofSeconds(90), context.telemetry.playtime(owner, account).allTime());

            UUID leaveId = UUID.randomUUID();
            var leave = new ServerTelemetryService.PlayerEvent(leaveId, "survival-main", first,
                    ServerTelemetryService.PlayerEventType.PLAYER_LEFT, account, "Steve",
                    context.clock.instant().minusSeconds(30));
            assertTrue(context.telemetry.playerEvent(leave));
            assertFalse(context.telemetry.playerEvent(leave));
            assertEquals(Duration.ofSeconds(60), context.telemetry.playtime(owner, account).allTime());

            context.clock.advance(Duration.ofSeconds(61));
            assertFalse(context.telemetry.status("survival-main").online());
            assertTrue(context.telemetry.onlinePlayers("survival-main").isEmpty());

            context.telemetry.playerEvent(new ServerTelemetryService.PlayerEvent(UUID.randomUUID(),
                    "survival-main", first, ServerTelemetryService.PlayerEventType.PLAYER_JOINED,
                    account, "Steve", context.clock.instant()));
            UUID second = UUID.randomUUID();
            context.clock.advance(Duration.ofSeconds(20));
            context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", second, context.clock.instant(), context.clock.instant(), 0, 20));
            assertFalse(context.telemetry.playtime(owner, account).online());
            assertFalse(context.telemetry.heartbeat(new ServerTelemetryService.Heartbeat(
                    "survival-main", first, started, context.clock.instant().plusSeconds(1), 9, 20)));
            assertEquals(second.toString(), context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT server_instance_id FROM server_status WHERE server_id='survival-main'");
                     var result = statement.executeQuery()) { return result.next() ? result.getString(1) : ""; }
            }));
        }
    }

    @Test
    void rejectsWrongServerAndForeignAccountOwnership() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("authz.db"))) {
            long account = context.register(7002L, "Alex");
            assertThrows(WorkflowException.class, () -> context.telemetry.heartbeat(
                    new ServerTelemetryService.Heartbeat("other", UUID.randomUUID(),
                            context.clock.instant(), context.clock.instant(), 0, 20)));
            assertThrows(WorkflowException.class, () -> context.telemetry.playtime(9999L, account));
        }
    }

    private static void assertStatus(BackendTestContext context, UUID instanceId, Instant startedAt,
                                     Instant heartbeatAt, int onlinePlayers, int maxPlayers,
                                     Instant shutdownAt) {
        StoredStatus status = context.database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT server_instance_id, started_at, last_heartbeat_at,
                           online_players, max_players, shutdown_at
                    FROM server_status WHERE server_id=?
                    """)) {
                statement.setString(1, "survival-main");
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    String shutdown = result.getString(6);
                    return new StoredStatus(UUID.fromString(result.getString(1)),
                            Instant.parse(result.getString(2)), Instant.parse(result.getString(3)),
                            result.getInt(4), result.getInt(5),
                            shutdown == null ? null : Instant.parse(shutdown));
                }
            }
        });
        assertEquals(new StoredStatus(instanceId, startedAt, heartbeatAt,
                onlinePlayers, maxPlayers, shutdownAt), status);
    }

    private record StoredStatus(UUID instanceId, Instant startedAt, Instant heartbeatAt,
                                int onlinePlayers, int maxPlayers, Instant shutdownAt) { }
}
