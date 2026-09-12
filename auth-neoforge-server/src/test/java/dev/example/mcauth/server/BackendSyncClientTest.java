package dev.example.mcauth.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendSyncClientTest {
    private HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void fixtureServesEventsReceivesAckAndCanReturnMalformedData() throws Exception {
        AtomicBoolean acknowledged = new AtomicBoolean();
        AtomicReference<String> response = new AtomicReference<>("""
                {"events":[{"eventId":"00000000-0000-4000-8000-000000000011","type":"ACCOUNT_DISABLED",
                "schemaVersion":1,"createdAt":"2026-09-12T12:00:00Z","payload":{"accountId":1}}]}
                """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/server/events", exchange -> {
            assertEquals("Bearer 01234567890123456789012345678901",
                    exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getPath().endsWith("/ack")) acknowledged.set(true);
            respond(exchange, response.get());
        });
        server.start();
        try (BackendSyncClient client = client("01234567890123456789012345678901".toCharArray())) {
            var events = client.pendingEvents();
            assertEquals(1, events.size());
            client.acknowledge(events.getFirst().id());
            assertTrue(acknowledged.get());
            response.set("{\"events\":\"not-an-array\"}");
            assertThrows(IOException.class, client::pendingEvents);
        }
    }

    @Test
    void httpErrorDoesNotExposeBearerToken() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/server/events", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        String token = "abcdefghijklmnopqrstuvwxyz123456";
        try (BackendSyncClient client = client(token.toCharArray())) {
            IOException failure = assertThrows(IOException.class, client::pendingEvents);
            assertTrue(failure.getMessage().contains("401"));
            assertTrue(!failure.getMessage().contains(token));
        }
    }

    private BackendSyncClient client(char[] token) {
        ServerAuthConfig.Values values = new ServerAuthConfig.Values(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(60), 3, 100, true, true);
        return new BackendSyncClient(values, token);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
