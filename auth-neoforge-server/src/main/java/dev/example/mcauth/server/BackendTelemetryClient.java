package dev.example.mcauth.server;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Outbound-only authenticated telemetry transport, sharing the existing server bearer credential. */
final class BackendTelemetryClient {
    private final BackendSyncClient backend;

    BackendTelemetryClient(BackendSyncClient backend) { this.backend = backend; }

    void heartbeat(String serverId, UUID instanceId, Instant startedAt, Instant occurredAt,
                   int onlinePlayers, int maxPlayers) throws IOException, InterruptedException {
        JsonObject body = base(serverId, instanceId, occurredAt);
        body.addProperty("startedAt", startedAt.toString());
        body.addProperty("onlinePlayers", onlinePlayers);
        body.addProperty("maxPlayers", maxPlayers);
        backend.send("POST", "/api/v1/servers/heartbeat", bytes(body));
    }

    void playerEvent(TelemetryPlayerEvent event) throws IOException, InterruptedException {
        JsonObject body = base(event.serverId(), event.serverInstanceId(), event.occurredAt());
        body.addProperty("eventId", event.eventId().toString());
        body.addProperty("type", event.type());
        body.addProperty("minecraftAccountId", event.minecraftAccountId());
        body.addProperty("nickname", event.nickname());
        backend.send("POST", "/api/v1/servers/player-events", bytes(body));
    }

    void shutdown(String serverId, UUID instanceId, Instant occurredAt) throws IOException, InterruptedException {
        backend.send("POST", "/api/v1/servers/shutdown", bytes(base(serverId, instanceId, occurredAt)));
    }

    private static JsonObject base(String serverId, UUID instanceId, Instant occurredAt) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("serverInstanceId", instanceId.toString());
        body.addProperty("occurredAt", occurredAt.toString());
        return body;
    }
    private static byte[] bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
