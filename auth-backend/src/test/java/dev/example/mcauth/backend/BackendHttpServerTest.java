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
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("http.db"));
             BackendHttpServer server = new BackendHttpServer("127.0.0.1", 0, TOKEN,
                     context.database, () -> false,
                     new SyncApiService(context.syncEvents, "survival-main"), context.commands, json)) {
            context.register(4001L, "HttpPlayer");
            var command = context.commands.create(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    RemoteCommandType.AUTH_STATUS, "{}");
            server.start();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> health = send("GET", base + "/health", null, null);
            assertEquals(200, health.statusCode());
            assertEquals("ok", json.readTree(health.body()).path("database").asText());
            assertEquals("stopped", json.readTree(health.body()).path("telegram").asText());
            assertEquals(4, json.readTree(health.body()).path("schemaVersion").asInt());

            HttpResponse<String> version = send("GET", base + "/version", null, null);
            assertEquals(200, version.statusCode());
            assertEquals("minecraft-auth-backend",
                    json.readTree(version.body()).path("application").asText());
            assertEquals(4, json.readTree(version.body()).path("schemaVersion").asInt());

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
            assertEquals(1, json.readTree(send("GET", base + "/api/v1/server/commands?server_id=survival-main", TOKEN, null).body()).path("commands").size());
            assertEquals(200, send("POST", base + "/api/v1/server/commands/" + command.id() + "/started?server_id=survival-main", TOKEN, "{}").statusCode());
            assertEquals(200, send("POST", base + "/api/v1/server/commands/" + command.id() + "/result?server_id=survival-main", TOKEN,
                    "{\"status\":\"SUCCESS\",\"result\":{\"accounts\":1}}").statusCode());
        }
    }

    private static HttpResponse<String> send(String method, String uri, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        else request.GET();
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
