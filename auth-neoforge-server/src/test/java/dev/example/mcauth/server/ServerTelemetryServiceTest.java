package dev.example.mcauth.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTelemetryServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void startSendsImmediateAndThreePeriodicHeartbeatsWithZeroPlayers() throws Exception {
        try (HeartbeatEndpoint endpoint = new HeartbeatEndpoint(0, 4)) {
            runService(endpoint, () -> {
                assertTrue(endpoint.successes.await(3, TimeUnit.SECONDS));
                assertTrue(endpoint.heartbeatBodies.stream().limit(4)
                        .allMatch(body -> body.contains("\"onlinePlayers\":0")));
            });
        }
    }

    @Test
    void temporaryBackendFailureDoesNotCancelPeriodicHeartbeats() throws Exception {
        try (HeartbeatEndpoint endpoint = new HeartbeatEndpoint(1, 3)) {
            runService(endpoint, () -> {
                assertTrue(endpoint.successes.await(3, TimeUnit.SECONDS));
                assertTrue(endpoint.heartbeatAttempts.get() >= 4);
            });
        }
    }

    private void runService(HeartbeatEndpoint endpoint, CheckedAssertion assertion) throws Exception {
        ServerAuthConfig.Values config = new ServerAuthConfig.Values(endpoint.baseUri(), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(60), 3, 100, true, true,
                true, Duration.ofSeconds(20), 10000);
        BackendSyncClient client = new BackendSyncClient(config,
                "01234567890123456789012345678901".toCharArray());
        ServerTelemetryService service = new ServerTelemetryService(config, client,
                temporaryDirectory.resolve("telemetry-events.json"), Clock.systemUTC(), 20,
                Duration.ofMillis(25));
        try {
            service.start();
            assertion.run();
        } finally {
            service.close();
            client.close();
        }
    }

    @FunctionalInterface
    private interface CheckedAssertion {
        void run() throws Exception;
    }

    private static final class HeartbeatEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final int failuresBeforeSuccess;
        private final AtomicInteger heartbeatAttempts = new AtomicInteger();
        private final CountDownLatch successes;
        private final List<String> heartbeatBodies = new CopyOnWriteArrayList<>();

        private HeartbeatEndpoint(int failuresBeforeSuccess, int expectedSuccesses) throws IOException {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.successes = new CountDownLatch(expectedSuccesses);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/servers/heartbeat", this::heartbeat);
            server.createContext("/api/v1/servers/shutdown", exchange -> respond(exchange, 200));
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void heartbeat(HttpExchange exchange) throws IOException {
            heartbeatBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int attempt = heartbeatAttempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                respond(exchange, 503);
                return;
            }
            successes.countDown();
            respond(exchange, 200);
        }

        private static void respond(HttpExchange exchange, int status) throws IOException {
            try {
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
