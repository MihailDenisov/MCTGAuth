package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import dev.example.mcauth.backend.telegram.i18n.MessageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramUpdateRouterTest {
    private static final long ADMIN_CHAT_ID = -1001L;
    @TempDir Path temporaryDirectory;

    @Test
    void newUserCanSelectRussianAndPreferencePersists() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("telegram.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(
                    telegram, json, context, localization, locales);

            router.handle(message(json, 5001L, "new_user", "ru", "/start"));
            assertTrue(telegram.last().text().contains("Выберите язык"));
            assertTrue(telegram.last().markup().toString().contains("Русский"));

            router.handle(callback(json, 5001L, "new_user", "locale:set:ru"));
            assertEquals(BotLocale.RUSSIAN, locales.userLocale(5001L));
            assertTrue(telegram.lastForChat(5001L).markup().toString().contains("Подать заявку"));

            router.handle(message(json, 5001L, "new_user", "en", "/start"));
            assertTrue(telegram.last().text().contains("Добро пожаловать"));
            assertEquals(BotLocale.RUSSIAN, locales.userLocale(5001L));
        }
    }

    @Test
    void englishAndRussianRegisteredMenusRenderWithoutTelegramApi() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("menus.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(
                    telegram, json, context, localization, locales);

            context.register(5101L, "EnglishPlayer");
            router.handle(message(json, 5101L, "english", "en", "/start"));
            assertTrue(telegram.last().markup().toString().contains("Report a problem"));
            assertTrue(telegram.last().markup().toString().contains("Settings"));

            context.register(5102L, "RussianPlayer");
            locales.setUserLocale(5102L, BotLocale.RUSSIAN);
            router.handle(message(json, 5102L, "russian", "en", "/start"));
            assertTrue(telegram.last().markup().toString().contains("Сообщить о проблеме"));
            assertTrue(telegram.last().markup().toString().contains("Настройки"));
        }
    }

    @Test
    void russianRegistrationFlowUsesButtonsWhileAdminGroupKeepsConfiguredLocale() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("registration.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(
                    telegram, json, context, localization, locales);

            router.handle(message(json, 5201L, "applicant", "ru", "/start"));
            router.handle(callback(json, 5201L, "applicant", "locale:set:ru"));
            router.handle(message(json, 5201L, "applicant", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.MENU_APPLY)));
            assertTrue(telegram.last().text().contains("Введите игровой ник"));
            router.handle(message(json, 5201L, "applicant", "ru", "LocalizedPlayer"));
            assertTrue(telegram.last().markup().toString().contains("Да"));
            router.handle(message(json, 5201L, "applicant", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.BUTTON_YES)));
            router.handle(message(json, 5201L, "applicant", "ru", "25"));
            router.handle(message(json, 5201L, "applicant", "ru", "Большой опыт"));
            router.handle(message(json, 5201L, "applicant", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.BUTTON_YES)));

            assertTrue(telegram.lastForChat(5201L).text().contains("Заявка на регистрацию"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Ticket #"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Type: Registration"));
        }
    }

    @Test
    void adminGroupUsesRussianIndependentlyOfEnglishUserLocale() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("admin-locale.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.RUSSIAN, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(
                    telegram, json, context, localization, locales);

            router.handle(message(json, 5301L, "applicant", "en", "/register"));
            router.handle(message(json, 5301L, "applicant", "en", "EnglishApplicant"));
            router.handle(message(json, 5301L, "applicant", "en", "Yes"));
            router.handle(message(json, 5301L, "applicant", "en", "30"));
            router.handle(message(json, 5301L, "applicant", "en", "Experienced"));
            router.handle(message(json, 5301L, "applicant", "en", "Yes"));

            assertEquals(BotLocale.ENGLISH, locales.userLocale(5301L));
            assertTrue(telegram.lastForChat(5301L).text().contains("Registration ticket"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Обращение №"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Тип: Регистрация"));
        }
    }

    @Test
    void moderatorPrivateAdministrationMenuUsesStoredRussianLocale() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("moderator-menu.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "ru", "/start"));
            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator", "locale:set:ru"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en", "/admin"));

            assertTrue(telegram.last().text().contains("Администрирование"));
            assertTrue(telegram.last().markup().toString().contains("Новые обращения"));
            assertTrue(telegram.last().markup().toString().contains("Управление сервером"));
        }
    }

    private static TelegramUpdateRouter router(
            TelegramClient telegram, ObjectMapper json, BackendTestContext context,
            LocalizationService localization, LocaleResolver locales) {
        return new TelegramUpdateRouter(telegram, ADMIN_CHAT_ID,
                "https://t.me/example/1", "survival-main", context.registrations,
                context.tickets, context.accounts, context.moderators, context.commands,
                localization, locales, json);
    }

    private static JsonNode message(
            ObjectMapper json, long id, String username, String languageCode, String text) {
        var update = json.createObjectNode();
        var message = update.putObject("message");
        message.putObject("from").put("id", id).put("username", username)
                .put("language_code", languageCode);
        message.putObject("chat").put("id", id).put("type", "private");
        message.put("text", text);
        return update;
    }

    private static JsonNode callback(
            ObjectMapper json, long id, String username, String data) {
        var update = json.createObjectNode();
        var callback = update.putObject("callback_query");
        callback.put("id", "callback-" + id);
        callback.putObject("from").put("id", id).put("username", username);
        callback.putObject("message").put("message_id", 10)
                .putObject("chat").put("id", id).put("type", "private");
        callback.put("data", data);
        return update;
    }

    private static final class FakeTelegramClient implements TelegramClient {
        private final List<SentMessage> sent = new ArrayList<>();

        @Override public List<JsonNode> getUpdates(long offset) { return List.of(); }

        @Override public long sendMessage(long chatId, String text, JsonNode replyMarkup) {
            sent.add(new SentMessage(chatId, text, replyMarkup));
            return sent.size();
        }

        @Override public void editMessage(long chatId, long messageId, String text) { }

        @Override public void answerCallback(String callbackQueryId, String text) { }

        @Override public void sendAttachment(long chatId, AttachmentReference attachment) { }

        SentMessage last() { return sent.getLast(); }

        SentMessage lastForChat(long chatId) {
            return sent.reversed().stream()
                    .filter(message -> message.chatId() == chatId)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private record SentMessage(long chatId, String text, JsonNode markup) { }
}
