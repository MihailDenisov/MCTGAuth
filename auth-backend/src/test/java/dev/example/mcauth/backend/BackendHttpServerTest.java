package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.api.RemoteCommandType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendHttpServerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    @TempDir Path temporaryDirectory;

    @Test
    void healthSyncAndCommandEndpointsAreAuthenticatedAndIdempotent() throws Exception {
        ObjectMapper json = new ObjectMapper();
        DeviceFingerprintService deviceFingerprints;
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("http.db"));
             BackendHttpServer server = new BackendHttpServer("127.0.0.1", 0, TOKEN,
                     context.database, () -> false,
                     new SyncApiService(context.syncEvents, "survival-main"), context.commands,
                     context.telemetry,
                     deviceFingerprints = new DeviceFingerprintService(context.database, context.clock,
                             "survival-main", "http-test-device-secret-00000000000000",
                             DeviceFingerprintConfig.defaults()), json)) {
            long accountId = context.register(4001L, "HttpPlayer");
            var command = context.commands.create(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.AUTH_STATUS, "{}");
            server.start();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> health = send("GET", base + "/health", null, null);
            assertEquals(200, health.statusCode());
            assertEquals("ok", json.readTree(health.body()).path("database").asText());
            assertEquals("stopped", json.readTree(health.body()).path("telegram").asText());
            assertEquals(8, json.readTree(health.body()).path("schemaVersion").asInt());

            HttpResponse<String> version = send("GET", base + "/version", null, null);
            assertEquals(200, version.statusCode());
            assertEquals("minecraft-auth-backend",
                    json.readTree(version.body()).path("application").asText());
            assertEquals(8, json.readTree(version.body()).path("schemaVersion").asInt());

            assertEquals(401, send("GET", base + "/api/v1/server/events?server_id=survival-main", null, null).statusCode());
            HttpResponse<String> pending = send("GET", base + "/api/v1/server/events?server_id=survival-main", TOKEN, null);
            assertEquals(200, pending.statusCode());
            JsonNode events = json.readTree(pending.body()).path("events");
            assertEquals(1, events.size());
            String eventId = events.get(0).path("eventId").asText();
            assertEquals(200, send("POST", base + "/api/v1/server/events/" + eventId + "/ack?server_id=survival-main", TOKEN, "{}").statusCode());
            assertEquals(200, send("POST", base + "/api/v1/server/events/" + eventId + "/ack?server_id=survival-main", TOKEN, "{}").statusCode());
            assertEquals(0, json.readTree(send("GET", base + "/api/v1/server/events?server_id=survival-main", TOKEN, null).body()).path("events").size());

            assertEquals(403, send("GET", base + "/api/v1/server/commands?server_id=other", TOKEN, null).statusCode());
            String digest = java.util.Base64.getEncoder().encodeToString(new byte[32]);
            String observation = """
                    {"serverId":"survival-main","accountId":%d,"normalizedMinecraftName":"httpplayer",
                     "fingerprintVersion":1,"signals":[{"type":"SYSTEM_UUID","digest":"%s"}]}
                    """.formatted(accountId, digest);
            assertEquals(401, send("POST", base + "/api/v1/server/device-observations", null,
                    observation).statusCode());
            assertEquals(400, send("POST", base + "/api/v1/server/device-observations", TOKEN,
                    observation.replace("SYSTEM_UUID", "UNKNOWN_TYPE")).statusCode());
            HttpResponse<String> device = send("POST", base + "/api/v1/server/device-observations", TOKEN,
                    observation);
            assertEquals(200, device.statusCode());
            assertEquals("ALLOWED", json.readTree(device.body()).path("decision").asText());
            String heartbeat = heartbeat("2026-09-11T12:00:00Z", 1, 20);
            assertEquals(401, send("POST", base + "/api/v1/servers/heartbeat", null, heartbeat).statusCode());
            assertEquals(200, send("POST", base + "/api/v1/servers/heartbeat", TOKEN, heartbeat).statusCode());
            assertEquals(200, send("POST", base + "/api/v1/servers/heartbeat", TOKEN,
                    heartbeat("2026-09-11T12:00:20Z", 2, 30)).statusCode());
            assertEquals(200, send("POST", base + "/api/v1/servers/heartbeat", TOKEN,
                    heartbeat("2026-09-11T12:00:40Z", 3, 40)).statusCode());
            assertEquals(409, send("POST", base + "/api/v1/servers/heartbeat", TOKEN,
                    heartbeat("2026-09-11T12:00:20Z", 9, 90)).statusCode());
            context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT last_heartbeat_at, online_players, max_players
                        FROM server_status WHERE server_id=?
                        """)) {
                    statement.setString(1, "survival-main");
                    try (var result = statement.executeQuery()) {
                        assertEquals(true, result.next());
                        assertEquals("2026-09-11T12:00:40Z", result.getString(1));
                        assertEquals(3, result.getInt(2));
                        assertEquals(40, result.getInt(3));
                    }
                }
                return null;
            });
            assertEquals(403, send("POST", base + "/api/v1/servers/heartbeat", TOKEN,
                    heartbeat.replace("survival-main", "other")).statusCode());
            assertEquals(1, json.readTree(send("GET", base + "/api/v1/server/commands?server_id=survival-main", TOKEN, null).body()).path("commands").size());
            assertEquals(200, send("POST", base + "/api/v1/server/commands/" + command.id() + "/started?server_id=survival-main", TOKEN, "{}").statusCode());
            assertEquals(200, send("POST", base + "/api/v1/server/commands/" + command.id() + "/result?server_id=survival-main", TOKEN,
                    "{\"status\":\"SUCCESS\",\"result\":{\"accounts\":1}}").statusCode());
        }
    }

    @Test
    void heartbeatDoesNotReturnAcceptedWhenPersistenceFails() throws Exception {
        ObjectMapper json = new ObjectMapper();
        BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("http-failure.db"));
        try (context;
             BackendHttpServer server = new BackendHttpServer("127.0.0.1", 0, TOKEN,
                     context.database, () -> false,
                     new SyncApiService(context.syncEvents, "survival-main"), context.commands,
                     context.telemetry, json)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            context.database.close();

            HttpResponse<String> response = send("POST", base + "/api/v1/servers/heartbeat", TOKEN,
                    heartbeat("2026-09-11T12:00:00Z", 1, 20));

            assertEquals(500, response.statusCode());
            assertEquals("persistence_failed", json.readTree(response.body()).path("error").asText());
        }
    }

    private static String heartbeat(String occurredAt, int onlinePlayers, int maxPlayers) {
        return """
                {"serverId":"survival-main","serverInstanceId":"00000000-0000-4000-8000-000000000010",
                 "startedAt":"2026-09-11T11:00:00Z","occurredAt":"%s",
                 "onlinePlayers":%d,"maxPlayers":%d}
                """.formatted(occurredAt, onlinePlayers, maxPlayers);
    }

    private static HttpResponse<String> send(String method, String uri, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        else request.GET();
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
