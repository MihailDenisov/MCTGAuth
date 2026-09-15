package dev.example.mcauth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.api.SyncEventType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BackendSyncClient implements SyncBackend {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final ServerAuthConfig.Values config;
    private final char[] token;
    private final HttpClient http;

    public BackendSyncClient(ServerAuthConfig.Values config, char[] token) {
        this.config = Objects.requireNonNull(config, "config");
        this.token = Objects.requireNonNull(token, "token").clone();
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    public List<SyncEvent> pendingEvents() throws IOException, InterruptedException {
        JsonArray events = requiredArray(send("GET", "/api/v1/server/events", null), "events");
        if (events.size() > config.batchSize()) throw new IOException("Invalid backend event response");
        List<SyncEvent> result = new ArrayList<>(events.size());
        for (var element : events) {
            try {
                JsonObject item = element.getAsJsonObject();
                JsonObject payload = requiredObject(item, "payload");
                result.add(new SyncEvent(UUID.fromString(requiredText(item, "eventId", 64)),
                        SyncEventType.valueOf(requiredText(item, "type", 64)),
                        requiredPositiveInt(item, "schemaVersion"), payload.toString(),
                        Instant.parse(requiredText(item, "createdAt", 64))));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid backend event envelope", exception);
            }
        }
        return List.copyOf(result);
    }

    public void acknowledge(UUID eventId) throws IOException, InterruptedException {
        send("POST", "/api/v1/server/events/" + eventId + "/ack", new byte[0]);
    }

    public List<RemoteCommand> pendingCommands() throws IOException, InterruptedException {
        JsonArray commands = requiredArray(send("GET", "/api/v1/server/commands", null), "commands");
        if (commands.size() > 100) throw new IOException("Invalid backend command response");
        List<RemoteCommand> result = new ArrayList<>(commands.size());
        for (var element : commands) {
            try {
                JsonObject item = element.getAsJsonObject();
                JsonObject payload = requiredObject(item, "payload");
                result.add(new RemoteCommand(UUID.fromString(requiredText(item, "id", 64)),
                        requiredText(item, "serverId", 64),
                        RemoteCommandType.valueOf(requiredText(item, "type", 64)),
                        requiredPositiveInt(item, "schemaVersion"), payload.toString(),
                        RemoteCommandStatus.PENDING, Instant.parse(requiredText(item, "createdAt", 64)),
                        Instant.parse(requiredText(item, "expiresAt", 64))));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid backend command envelope", exception);
            }
        }
        return List.copyOf(result);
    }

    public void markCommandStarted(UUID id) throws IOException, InterruptedException {
        send("POST", "/api/v1/server/commands/" + id + "/started", new byte[0]);
    }

    public void completeCommand(UUID id, RemoteCommandStatus status, JsonObject result, String safeError)
            throws IOException, InterruptedException {
        if (status != RemoteCommandStatus.SUCCESS && status != RemoteCommandStatus.FAILED) {
            throw new IllegalArgumentException("Invalid command completion status");
        }
        JsonObject body = new JsonObject();
        body.addProperty("status", status.name());
        body.add("result", Objects.requireNonNull(result, "result").deepCopy());
        if (safeError != null && !safeError.isBlank()) body.addProperty("errorMessage", safeError);
        send("POST", "/api/v1/server/commands/" + id + "/result",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    JsonObject send(String method, String path, byte[] body) throws IOException, InterruptedException {
        String query = "server_id=" + URLEncoder.encode(config.serverId(), StandardCharsets.UTF_8);
        URI uri = config.baseUrl().resolve(path + "?" + query);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + new String(token))
                .header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Backend response is too large");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Backend request failed with HTTP " + response.statusCode());
        }
        if (bytes.length == 0) return new JsonObject();
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Backend returned invalid JSON", exception);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static JsonArray requiredArray(JsonObject node, String field) throws IOException {
        if (!node.has(field) || !node.get(field).isJsonArray()) throw new IOException("Invalid backend response");
        return node.getAsJsonArray(field);
    }

    private static JsonObject requiredObject(JsonObject node, String field) {
        if (!node.has(field) || !node.get(field).isJsonObject()) throw new IllegalArgumentException("Invalid backend field");
        return node.getAsJsonObject(field);
    }

    private static String requiredText(JsonObject node, String field, int max) {
        if (!node.has(field) || !node.get(field).isJsonPrimitive() || !node.getAsJsonPrimitive(field).isString()) {
            throw new IllegalArgumentException("Invalid backend field");
        }
        String value = node.get(field).getAsString();
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid backend field");
        return value;
    }

    private static int requiredPositiveInt(JsonObject node, String field) {
        try {
            int value = node.get(field).getAsInt();
            if (value <= 0) throw new IllegalArgumentException("Invalid backend field");
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid backend field", exception);
        }
    }

    @Override public void close() { Arrays.fill(token, '\0'); }
}
