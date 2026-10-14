package dev.example.mcauth.server;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerAuthConfigTest {
    @Test
    void productionBackendRequiresAbsoluteHttpsUrlWithHost() {
        assertDoesNotThrow(() -> values("https://auth.example.com", false));
        assertThrows(IllegalArgumentException.class, () -> values("http://auth.example.com", false));
        assertThrows(IllegalArgumentException.class, () -> values("https:relative", false));
        assertThrows(IllegalArgumentException.class, () -> values("https://user:secret@auth.example.com", false));
        assertThrows(IllegalArgumentException.class, () -> values("https://auth.example.com?secret=value", false));
    }

    @Test
    void insecureHttpRequiresExplicitDevelopmentFlag() {
        assertDoesNotThrow(() -> values("http://127.0.0.1:8080", true));
    }

    @Test
    void serverIdentifierAndTimeoutsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ServerAuthConfig.Values(
                URI.create("https://auth.example.com"), "invalid server id",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(60), 3, 100, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ServerAuthConfig.Values(
                URI.create("https://auth.example.com"), "survival-main",
                Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(60), 3, 100, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ServerAuthConfig.Values(
                URI.create("https://auth.example.com"), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(121),
                Duration.ofSeconds(60), 3, 100, false, true));
    }

    @Test
    void heartbeatIntervalMustStayWithinDocumentedBounds() {
        assertThrows(IllegalArgumentException.class, () -> telemetryValues(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> telemetryValues(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> telemetryValues(Duration.ofSeconds(9)));
        assertDoesNotThrow(() -> telemetryValues(Duration.ofSeconds(20)));
    }

    private static ServerAuthConfig.Values values(String baseUrl, boolean allowHttp) {
        return new ServerAuthConfig.Values(URI.create(baseUrl), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(60), 3, 100, allowHttp, true);
    }

    private static ServerAuthConfig.Values telemetryValues(Duration heartbeatInterval) {
        return new ServerAuthConfig.Values(URI.create("https://auth.example.com"), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(60), 3, 100, false, true,
                true, heartbeatInterval, 10000);
    }
}
