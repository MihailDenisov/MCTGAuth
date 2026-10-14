package dev.example.mcauth.server;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.net.URI;
import java.time.Duration;

public final class ServerAuthConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> BASE_URL;
    private static final ModConfigSpec.ConfigValue<String> SERVER_ID;
    private static final ModConfigSpec.ConfigValue<String> API_TOKEN;
    private static final ModConfigSpec.IntValue POLL_INTERVAL;
    private static final ModConfigSpec.IntValue CONNECT_TIMEOUT;
    private static final ModConfigSpec.IntValue REQUEST_TIMEOUT;
    private static final ModConfigSpec.IntValue AUTH_TIMEOUT;
    private static final ModConfigSpec.IntValue MAX_ATTEMPTS;
    private static final ModConfigSpec.IntValue BATCH_SIZE;
    private static final ModConfigSpec.BooleanValue ALLOW_HTTP;
    private static final ModConfigSpec.BooleanValue DISCONNECT_DISABLED;
    private static final ModConfigSpec.BooleanValue TELEMETRY_ENABLED;
    private static final ModConfigSpec.IntValue HEARTBEAT_INTERVAL;
    private static final ModConfigSpec.IntValue TELEMETRY_QUEUE_LIMIT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("backend");
        BASE_URL = builder.comment("HTTPS URL of auth-backend without a trailing slash")
                .define("baseUrl", "https://auth.example.com");
        SERVER_ID = builder.comment("Backend server identifier")
                .define("serverId", "survival-main");
        API_TOKEN = builder.comment(
                        "Bearer token shared with auth-backend. Stored only in this dedicated-server config.",
                        "MCTGAUTH_API_TOKEN may override this value. Never publish this file.")
                .define("apiToken", "");
        POLL_INTERVAL = builder.defineInRange("pollIntervalSeconds", 10, 5, 3600);
        CONNECT_TIMEOUT = builder.defineInRange("connectTimeoutSeconds", 5, 1, 60);
        REQUEST_TIMEOUT = builder.defineInRange("requestTimeoutSeconds", 15, 1, 120);
        ALLOW_HTTP = builder.comment("Development only. Never enable in production.")
                .define("allowInsecureHttpForDevelopment", false);
        builder.pop().push("authentication");
        AUTH_TIMEOUT = builder.defineInRange("timeoutSeconds", 60, 10, 300);
        MAX_ATTEMPTS = builder.defineInRange("maxAttempts", 3, 1, 10);
        DISCONNECT_DISABLED = builder.define("disconnectPlayerWhenAccountDisabled", true);
        builder.pop().push("sync");
        BATCH_SIZE = builder.defineInRange("batchSize", 100, 1, 100);
        builder.pop().push("telemetry");
        TELEMETRY_ENABLED = builder.define("enabled", true);
        HEARTBEAT_INTERVAL = builder.defineInRange("heartbeatIntervalSeconds", 20, 10, 300);
        TELEMETRY_QUEUE_LIMIT = builder.defineInRange("maxQueuedPlayerEvents", 10000, 100, 100000);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerAuthConfig() {
    }

    public static Values values() {
        return new Values(URI.create(BASE_URL.get()), SERVER_ID.get(), Duration.ofSeconds(POLL_INTERVAL.get()),
                Duration.ofSeconds(CONNECT_TIMEOUT.get()), Duration.ofSeconds(REQUEST_TIMEOUT.get()),
                Duration.ofSeconds(AUTH_TIMEOUT.get()), MAX_ATTEMPTS.get(), BATCH_SIZE.get(), ALLOW_HTTP.get(),
                DISCONNECT_DISABLED.get(), TELEMETRY_ENABLED.get(), Duration.ofSeconds(HEARTBEAT_INTERVAL.get()),
                TELEMETRY_QUEUE_LIMIT.get());
    }

    static String configuredApiToken() {
        return API_TOKEN.get();
    }

    public record Values(URI baseUrl, String serverId, Duration pollInterval, Duration connectTimeout,
                         Duration requestTimeout, Duration authenticationTimeout, int maxAttempts,
                         int batchSize, boolean allowInsecureHttpForDevelopment,
                         boolean disconnectPlayerWhenAccountDisabled, boolean telemetryEnabled,
                         Duration heartbeatInterval, int telemetryQueueLimit) {
        public Values(URI baseUrl, String serverId, Duration pollInterval, Duration connectTimeout,
                      Duration requestTimeout, Duration authenticationTimeout, int maxAttempts,
                      int batchSize, boolean allowInsecureHttpForDevelopment,
                      boolean disconnectPlayerWhenAccountDisabled) {
            this(baseUrl, serverId, pollInterval, connectTimeout, requestTimeout, authenticationTimeout,
                    maxAttempts, batchSize, allowInsecureHttpForDevelopment,
                    disconnectPlayerWhenAccountDisabled, true, Duration.ofSeconds(20), 10000);
        }
        public Values {
            if (baseUrl == null || serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Invalid backend configuration");
            }
            requireRange(pollInterval, 5, 3600, "pollIntervalSeconds");
            requireRange(connectTimeout, 1, 60, "connectTimeoutSeconds");
            requireRange(requestTimeout, 1, 120, "requestTimeoutSeconds");
            requireRange(authenticationTimeout, 10, 300, "authentication.timeoutSeconds");
            requireRange(heartbeatInterval, 10, 300, "telemetry.heartbeatIntervalSeconds");
            if (maxAttempts < 1 || maxAttempts > 10 || batchSize < 1 || batchSize > 100) {
                throw new IllegalArgumentException("Invalid authentication or sync configuration");
            }
            if (telemetryQueueLimit < 100 || telemetryQueueLimit > 100000) {
                throw new IllegalArgumentException("Invalid telemetry queue limit");
            }
            String scheme = baseUrl.getScheme();
            if (!"https".equalsIgnoreCase(scheme)
                    && !(allowInsecureHttpForDevelopment && "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Backend URL must use HTTPS");
            }
            if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
                throw new IllegalArgumentException("Backend URL must not contain credentials, query, or fragment");
            }
            if (!baseUrl.isAbsolute() || baseUrl.getHost() == null || baseUrl.getHost().isBlank()) {
                throw new IllegalArgumentException("Backend URL must be an absolute URL with a host");
            }
        }

        private static void requireRange(Duration value, long minimumSeconds, long maximumSeconds, String name) {
            if (value == null || value.compareTo(Duration.ofSeconds(minimumSeconds)) < 0
                    || value.compareTo(Duration.ofSeconds(maximumSeconds)) > 0) {
                throw new IllegalArgumentException(name + " is outside its allowed range");
            }
        }
    }
}
