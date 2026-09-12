package dev.example.mcauth.backend;

import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendConfigTest {
    @Test
    void loadsCentralizedEnvironmentAndRejectsPublicBind() {
        Map<String, String> environment = validEnvironment();
        BackendConfig config = BackendConfig.fromEnvironment(environment);
        assertEquals("127.0.0.1", config.httpBindAddress());
        assertEquals("ExampleBot", config.telegramBotUsername());
        assertEquals("survival-main", config.serverId());
        assertEquals(BotLocale.ENGLISH, config.adminLocale());
        assertEquals(BotLocale.ENGLISH, config.defaultUserLocale());

        environment.put("HTTP_BIND_ADDRESS", "0.0.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> BackendConfig.fromEnvironment(environment));
    }

    @Test
    void validatesConfiguredLocales() {
        Map<String, String> environment = validEnvironment();
        environment.put("ADMIN_LOCALE", "ru");
        environment.put("DEFAULT_USER_LOCALE", "ru");
        BackendConfig config = BackendConfig.fromEnvironment(environment);
        assertEquals(BotLocale.RUSSIAN, config.adminLocale());
        assertEquals(BotLocale.RUSSIAN, config.defaultUserLocale());

        environment.put("ADMIN_LOCALE", "xyz");
        assertThrows(IllegalArgumentException.class,
                () -> BackendConfig.fromEnvironment(environment));
    }

    @Test
    void rejectsWeakApiTokenWithoutEchoingIt() {
        Map<String, String> environment = validEnvironment();
        environment.put("AUTH_API_TOKEN", "short-secret");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> BackendConfig.fromEnvironment(environment));
        assertEquals(false, exception.getMessage().contains("short-secret"));
    }

    private static Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TELEGRAM_BOT_TOKEN", "bot-token-value");
        environment.put("TELEGRAM_BOT_USERNAME", "ExampleBot");
        environment.put("TELEGRAM_ADMIN_CHAT_ID", "-1001234567890");
        environment.put("MODERATOR_TELEGRAM_IDS", "123,456");
        environment.put("HOW_TO_PLAY_URL", "https://t.me/example/1");
        environment.put("AUTH_API_TOKEN", "0123456789abcdef0123456789abcdef");
        environment.put("SERVER_ID", "survival-main");
        return environment;
    }
}
