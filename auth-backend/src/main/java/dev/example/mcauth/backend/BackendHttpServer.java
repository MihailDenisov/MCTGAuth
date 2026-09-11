package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.SyncEvent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

public final class BackendHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 16_384;

    private final HttpServer server;
    private final ObjectMapper json;
    private final ApiTokenAuthenticator authentication;
    private final BackendDatabase database;
    private final BooleanSupplier telegramRunning;
    private final SyncApiService sync;
    private final RemoteCommandService commands;
    private final ExecutorService executor;
    private final BuildInfo buildInfo;

    public BackendHttpServer(
            String bindAddress,
            int port,
            String apiToken,
            BackendDatabase database,
            BooleanSupplier telegramRunning,
            SyncApiService sync,
            RemoteCommandService commands,
            ObjectMapper json) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 50);
        this.json = json;
        this.authentication = new ApiTokenAuthenticator(apiToken);
        this.database = database;
        this.telegramRunning = telegramRunning;
        this.sync = sync;
        this.commands = commands;
        this.buildInfo = BuildInfo.load();
        server.createContext("/health", this::health);
        server.createContext("/version", this::version);
        server.createContext("/api/v1/server/events", this::events);
        server.createContext("/api/v1/server/commands", this::commands);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("method_not_allowed"));
            return;
        }
        ObjectNode response = json.createObjectNode();
        boolean databaseHealthy = database.isHealthy();
        response.put("status", databaseHealthy ? "ok" : "degraded");
        response.put("database", databaseHealthy ? "ok" : "error");
        response.put("telegram", telegramRunning.getAsBoolean() ? "running" : "stopped");
        response.put("version", buildInfo.version());
        response.put("commit", buildInfo.commit());
        response.put("schemaVersion", database.schemaVersion());
        respond(exchange, databaseHealthy ? 200 : 503, response);
    }

    private void version(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("method_not_allowed"));
            return;
        }
        ObjectNode response = json.createObjectNode();
        response.put("application", buildInfo.application());
        response.put("version", buildInfo.version());
        response.put("commit", buildInfo.commit());
        response.put("buildTime", buildInfo.buildTime());
        response.put("schemaVersion", database.schemaVersion());
        respond(exchange, 200, response);
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!requireAuthentication(exchange)) return;
        String base = "/api/v1/server/events";
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals(base) && "GET".equals(exchange.getRequestMethod())) {
                ArrayNode array = json.createArrayNode();
                for (SyncEvent event : sync.pending(query(exchange).get("server_id"))) {
                    ObjectNode item = json.createObjectNode();
                    item.put("eventId", event.id().toString());
                    item.put("type", event.type().name());
                    item.put("schemaVersion", event.schemaVersion());
                    item.put("createdAt", event.createdAt().toString());
                    item.set("payload", json.readTree(event.payloadJson()));
                    array.add(item);
                }
                ObjectNode response = json.createObjectNode();
                response.set("events", array);
                respond(exchange, 200, response);
                return;
            }
            if (path.startsWith(base + "/") && path.endsWith("/ack")
                    && "POST".equals(exchange.getRequestMethod())) {
                String id = path.substring((base + "/").length(), path.length() - "/ack".length());
                boolean acknowledged = sync.acknowledge(query(exchange).get("server_id"), UUID.fromString(id));
                respond(exchange, acknowledged ? 200 : 404,
                        acknowledged ? status("acknowledged") : error("not_found"));
                return;
            }
            respond(exchange, 404, error("not_found"));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, error("invalid_request"));
        } catch (WorkflowException exception) {
            respond(exchange, exception.code() == WorkflowException.Code.UNAUTHORIZED ? 403 : 409,
                    error("request_rejected"));
        }
    }

    private void commands(HttpExchange exchange) throws IOException {
        if (!requireAuthentication(exchange)) return;
        String base = "/api/v1/server/commands";
        String path = exchange.getRequestURI().getPath();
        String requestedServer = query(exchange).get("server_id");
        try {
            if (path.equals(base) && "GET".equals(exchange.getRequestMethod())) {
                ArrayNode array = json.createArrayNode();
                for (RemoteCommand command : commands.pendingForServer(requestedServer)) {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", command.id().toString());
                    item.put("serverId", command.serverId());
                    item.put("type", command.type().name());
                    item.put("schemaVersion", command.schemaVersion());
                    item.put("createdAt", command.createdAt().toString());
                    item.put("expiresAt", command.expiresAt().toString());
                    item.set("payload", json.readTree(command.payloadJson()));
                    array.add(item);
                }
                ObjectNode response = json.createObjectNode();
                response.set("commands", array);
                respond(exchange, 200, response);
                return;
            }
            if (path.startsWith(base + "/") && path.endsWith("/started")
                    && "POST".equals(exchange.getRequestMethod())) {
                UUID id = pathId(path, base, "/started");
                boolean started = commands.markStarted(requestedServer, id);
                respond(exchange, started ? 200 : 409, started ? status("running") : error("invalid_state"));
                return;
            }
            if (path.startsWith(base + "/") && path.endsWith("/result")
                    && "POST".equals(exchange.getRequestMethod())) {
                UUID id = pathId(path, base, "/result");
                JsonNode request = readJson(exchange);
                RemoteCommandStatus finalStatus = RemoteCommandStatus.valueOf(request.path("status").asText());
                String result = request.has("result") ? json.writeValueAsString(request.path("result")) : "{}";
                boolean completed = commands.complete(requestedServer, id, finalStatus,
                        result, request.path("errorMessage").asText(""));
                respond(exchange, completed ? 200 : 409,
                        completed ? status(finalStatus.name().toLowerCase(java.util.Locale.ROOT))
                                : error("invalid_state"));
                return;
            }
            respond(exchange, 404, error("not_found"));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, error("invalid_request"));
        } catch (WorkflowException exception) {
            respond(exchange, exception.code() == WorkflowException.Code.UNAUTHORIZED ? 403 : 409,
                    error("request_rejected"));
        }
    }

    private boolean requireAuthentication(HttpExchange exchange) throws IOException {
        if (!authentication.authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respond(exchange, 401, error("unauthorized"));
            return false;
        }
        return true;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request is too large");
        return json.readTree(body);
    }

    private static UUID pathId(String path, String base, String suffix) {
        return UUID.fromString(path.substring((base + "/").length(), path.length() - suffix.length()));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private ObjectNode error(String code) {
        ObjectNode response = json.createObjectNode();
        response.put("error", code);
        return response;
    }

    private ObjectNode status(String value) {
        ObjectNode response = json.createObjectNode();
        response.put("status", value);
        return response;
    }

    private void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }
}
