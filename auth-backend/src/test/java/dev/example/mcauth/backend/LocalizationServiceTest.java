package dev.example.mcauth.backend;

import dev.example.mcauth.api.TicketStatus;
import dev.example.mcauth.api.TicketType;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import dev.example.mcauth.backend.telegram.i18n.MenuActionRouter;
import dev.example.mcauth.backend.telegram.i18n.BotAction;
import dev.example.mcauth.backend.telegram.i18n.MessageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizationServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void englishAndRussianContainTheSameRequiredKeys() throws Exception {
        Properties english = properties("i18n/messages_en.properties");
        Properties russian = properties("i18n/messages_ru.properties");
        assertEquals(english.stringPropertyNames(), russian.stringPropertyNames());
        for (MessageKey key : MessageKey.values()) {
            assertTrue(english.containsKey(key.key()), "Missing required key: " + key.key());
        }
    }

    @Test
    void formatsParametersTypesStatusesAndDates() {
        LocalizationService service = new LocalizationService(ZoneOffset.UTC);
        assertEquals("Ticket #42 was created successfully.",
                service.text(BotLocale.ENGLISH, MessageKey.TICKET_SUBMITTED, "42"));
        assertEquals("Обращение №42 успешно создано.",
                service.text(BotLocale.RUSSIAN, MessageKey.TICKET_SUBMITTED, "42"));
        assertEquals("Problem", service.text(BotLocale.ENGLISH, typeKey(TicketType.PROBLEM)));
        assertEquals("Проблема", service.text(BotLocale.RUSSIAN, typeKey(TicketType.PROBLEM)));
        assertEquals("In progress", service.text(BotLocale.ENGLISH, statusKey(TicketStatus.IN_PROGRESS)));
        assertEquals("В работе", service.text(BotLocale.RUSSIAN, statusKey(TicketStatus.IN_PROGRESS)));
        var instant = java.time.Instant.parse("2026-09-11T19:42:00Z");
        assertEquals("11.09.2026 22:42 MSK", service.formatInstant(BotLocale.ENGLISH, instant));
        assertEquals("11.09.2026 22:42 МСК", service.formatInstant(BotLocale.RUSSIAN, instant));
    }

    @Test
    void moscowRenderingDoesNotDependOnJvmDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            LocalizationService service = new LocalizationService();
            var instant = java.time.Instant.parse("2026-09-12T11:34:00Z");
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("12.09.2026 14:34 МСК",
                    service.formatInstant(BotLocale.RUSSIAN, instant));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("12.09.2026 14:34 МСК",
                    service.formatInstant(BotLocale.RUSSIAN, instant));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void missingRussianTranslationFallsBackToEnglish() {
        LocalizationService service = new LocalizationService(ZoneOffset.UTC, "i18n.fallback");
        assertEquals("English fallback", service.text(BotLocale.RUSSIAN, MessageKey.MAIN_WELCOME));
    }

    @Test
    void localizedReplyLabelsResolveToStableActions() {
        LocalizationService service = new LocalizationService(ZoneOffset.UTC);
        MenuActionRouter router = new MenuActionRouter(service);
        assertEquals(BotAction.REPORT_PROBLEM,
                router.resolve(service.text(BotLocale.ENGLISH, MessageKey.MENU_REPORT_PROBLEM)).orElseThrow());
        assertEquals(BotAction.REPORT_PROBLEM,
                router.resolve(service.text(BotLocale.RUSSIAN, MessageKey.MENU_REPORT_PROBLEM)).orElseThrow());
        assertEquals(BotAction.YES,
                router.resolve(service.text(BotLocale.RUSSIAN, MessageKey.BUTTON_YES)).orElseThrow());
    }

    @Test
    void selectedLocalePersistsAcrossDatabaseRestart() throws Exception {
        Path databasePath = temporaryDirectory.resolve("locale.db");
        try (BackendTestContext context = new BackendTestContext(databasePath)) {
            context.accounts.touchTelegramUser(7001L, "user", "en");
            LocaleResolver resolver = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            resolver.setUserLocale(7001L, BotLocale.RUSSIAN);
            assertEquals(BotLocale.RUSSIAN, resolver.userLocale(7001L));
        }
        try (BackendDatabase database = BackendDatabase.open(databasePath)) {
            LocaleResolver resolver = new LocaleResolver(
                    database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            assertEquals(BotLocale.RUSSIAN, resolver.userLocale(7001L));
        }
    }

    private static Properties properties(String name) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = LocalizationServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing test resource: " + name);
            properties.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static MessageKey typeKey(TicketType type) {
        return switch (type) {
            case REGISTRATION -> MessageKey.TYPE_REGISTRATION;
            case ADD_MINECRAFT_ACCOUNT -> MessageKey.TYPE_ADD_ACCOUNT;
            case PROBLEM -> MessageKey.TYPE_PROBLEM;
            case VIOLATION -> MessageKey.TYPE_VIOLATION;
        };
    }

    private static MessageKey statusKey(TicketStatus status) {
        return switch (status) {
            case OPEN -> MessageKey.STATUS_OPEN;
            case IN_PROGRESS -> MessageKey.STATUS_IN_PROGRESS;
            case WAITING_USER -> MessageKey.STATUS_WAITING_USER;
            case RESOLVED -> MessageKey.STATUS_RESOLVED;
            case REJECTED -> MessageKey.STATUS_REJECTED;
            case CLOSED -> MessageKey.STATUS_CLOSED;
        };
    }
}
