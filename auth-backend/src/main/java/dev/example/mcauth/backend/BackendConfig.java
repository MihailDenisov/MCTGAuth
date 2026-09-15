package dev.example.mcauth.backend;

import dev.example.mcauth.backend.telegram.i18n.BotLocale;

import java.nio.file.Path;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record BackendConfig(
        Path databasePath,
        String telegramBotToken,
        String telegramBotUsername,
        long telegramAdminChatId,
        Set<Long> moderatorTelegramIds,
        String howToPlayUrl,
        Path telegramFeatureConfigPath,
        String httpBindAddress,
        int httpPort,
        String authApiToken,
        String serverId,
        BotLocale adminLocale,
        BotLocale defaultUserLocale) {

    public BackendConfig {
        moderatorTelegramIds = Set.copyOf(moderatorTelegramIds);
        if (telegramBotToken == null || telegramBotToken.isBlank()) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN is required");
        }
        telegramBotUsername = telegramBotUsername == null ? "" : telegramBotUsername.strip();
        if (telegramBotUsername.startsWith("@")) telegramBotUsername = telegramBotUsername.substring(1);
        if (!telegramBotUsername.matches("[A-Za-z0-9_]{5,32}")) {
            throw new IllegalArgumentException("TELEGRAM_BOT_USERNAME has an invalid format");
        }
        if (telegramAdminChatId == 0) {
            throw new IllegalArgumentException("TELEGRAM_ADMIN_CHAT_ID is required");
        }
        if (moderatorTelegramIds.isEmpty()) {
            throw new IllegalArgumentException("MODERATOR_TELEGRAM_IDS must contain at least one numeric ID");
        }
        howToPlayUrl = optionalHttpsUrl(howToPlayUrl, "HOW_TO_PLAY_URL");
        telegramFeatureConfigPath = telegramFeatureConfigPath.toAbsolutePath().normalize();
        validateLoopback(httpBindAddress);
        if (httpPort < 1 || httpPort > 65_535) {
            throw new IllegalArgumentException("HTTP_PORT must be between 1 and 65535");
        }
        if (authApiToken == null || authApiToken.length() < 32) {
            throw new IllegalArgumentException("AUTH_API_TOKEN must contain at least 32 characters");
        }
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("SERVER_ID has an invalid format");
        }
        if (adminLocale == null || defaultUserLocale == null) {
            throw new IllegalArgumentException("Configured locales are required");
        }
    }

    public static BackendConfig fromEnvironment(Map<String, String> environment) {
        String rawIds = required(environment, "MODERATOR_TELEGRAM_IDS");
        Set<Long> moderatorIds = new LinkedHashSet<>();
        Arrays.stream(rawIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .mapToLong(BackendConfig::parsePositiveId)
                .forEach(moderatorIds::add);

        return new BackendConfig(
                Path.of(environment.getOrDefault("AUTH_DATABASE_PATH", "./data/auth-backend.db")),
                required(environment, "TELEGRAM_BOT_TOKEN"),
                required(environment, "TELEGRAM_BOT_USERNAME"),
                parseChatId(required(environment, "TELEGRAM_ADMIN_CHAT_ID")),
                moderatorIds,
                environment.get("HOW_TO_PLAY_URL"),
                Path.of(environment.getOrDefault(
                        "TELEGRAM_FEATURE_CONFIG_PATH", "./config/telegram-features.yaml")),
                environment.getOrDefault("HTTP_BIND_ADDRESS", "127.0.0.1").trim(),
                Integer.parseInt(environment.getOrDefault("HTTP_PORT", "8080").trim()),
                required(environment, "AUTH_API_TOKEN"),
                required(environment, "SERVER_ID"),
                BotLocale.configured(environment.getOrDefault("ADMIN_LOCALE", "en"), "ADMIN_LOCALE"),
                BotLocale.configured(environment.getOrDefault("DEFAULT_USER_LOCALE", "en"),
                        "DEFAULT_USER_LOCALE"));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private static long parsePositiveId(String value) {
        long id = Long.parseLong(value);
        if (id <= 0) {
            throw new IllegalArgumentException("Moderator Telegram IDs must be positive");
        }
        return id;
    }

    private static long parseChatId(String value) {
        long id = Long.parseLong(value);
        if (id == 0) {
            throw new IllegalArgumentException("Telegram chat ID cannot be zero");
        }
        return id;
    }

    private static String optionalHttpsUrl(String value, String key) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.strip();
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException(key + " must be an absolute HTTPS URL");
            }
            return normalized;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " must be an absolute HTTPS URL");
        }
    }

    private static void validateLoopback(String value) {
        try {
            if (!InetAddress.getByName(value).isLoopbackAddress()) {
                throw new IllegalArgumentException("HTTP_BIND_ADDRESS must be a loopback address");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("HTTP_BIND_ADDRESS is invalid");
        }
    }
}
