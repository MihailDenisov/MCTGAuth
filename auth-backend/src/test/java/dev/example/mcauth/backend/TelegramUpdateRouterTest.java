package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import dev.example.mcauth.backend.telegram.i18n.MessageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramUpdateRouterTest {
    private static final long ADMIN_CHAT_ID = -1001L;
    @TempDir Path temporaryDirectory;

    @Test
    void normalUserScreensReuseOneNavigationMessage() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("single-user-ui.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long userId = 4901L;
            context.register(userId, "SingleMsgPlayer");

            router.handle(message(json, userId, "single", "en", "/menu"));
            router.handle(message(json, userId, "single", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SETTINGS)));
            router.handle(message(json, userId, "single", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_MY_ACCOUNT)));
            router.handle(message(json, userId, "single", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SERVER_INFORMATION)));
            router.handle(message(json, userId, "single", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_BACK)));

            assertEquals(1, telegram.sent.size());
            assertEquals(4, telegram.edits);
            assertTrue(telegram.lastForChat(userId).markup().toString().contains("My account"));
        }
    }

    @Test
    void passwordSecretIsStandaloneAndNeverBecomesNavigation() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("secret-ui.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long userId = 4902L;
            long accountId = context.register(userId, "SecretPlayer");

            router.handle(message(json, userId, "secret", "en", "/menu"));
            router.handle(message(json, userId, "secret", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_CHANGE_PASSWORD)));
            router.handle(callback(json, userId, "secret", "account:" + accountId + ":password"));
            router.handle(callback(json, userId, "secret", "account:" + accountId + ":password_confirm"));
            assertEquals(2, telegram.sent.size());
            String secretText = telegram.sent.get(1).text();

            router.handle(message(json, userId, "secret", "en", "/menu"));

            assertEquals(2, telegram.sent.size());
            assertEquals(secretText, telegram.sent.get(1).text());
            assertNotEquals(secretText, telegram.sent.getFirst().text());
        }
    }

    @Test
    void moderatorUserAndAdminNavigationAreIndependentPerModerator() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("admin-ui-sessions.db"))) {
            long secondModerator = 1002L;
            context.moderators.synchronizeConfiguredModerators(
                    java.util.Set.of(BackendTestContext.MODERATOR_ID, secondModerator));
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en", "/menu"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en", "/adm"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en", "/menu"));
            router.handle(message(json, secondModerator, "moderator2", "en", "/adm"));

            assertEquals(3, telegram.sent.size());
            assertTrue(telegram.sent.getFirst().markup().toString().contains("Apply for registration"));
            assertTrue(telegram.sent.get(1).markup().toString().contains("Requests"));
            assertTrue(telegram.sent.get(2).markup().toString().contains("Requests"));
        }
    }

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
            assertTrue(telegram.last().text().contains("Ваш ник в Minecraft"));
            router.handle(message(json, 5201L, "applicant", "ru", "LocalizedPlayer"));
            assertTrue(telegram.last().markup().toString().contains("Да"));
            router.handle(message(json, 5201L, "applicant", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.BUTTON_YES)));
            router.handle(message(json, 5201L, "applicant", "ru", "25"));
            assertTrue(telegram.last().text().contains("Откуда вы узнали о сервере"));
            router.handle(message(json, 5201L, "applicant", "ru", "Большой опыт"));
            assertTrue(telegram.last().text().contains("Minecraft: LocalizedPlayer"));
            assertTrue(telegram.last().text().contains("Откуда узнали / игровой опыт"));
            router.handle(message(json, 5201L, "applicant", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.BUTTON_YES)));

            assertTrue(telegram.lastForChat(5201L).text().contains("Заявка на регистрацию"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Ticket #"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("Type: Registration"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("11.09.2026 15:00 MSK"));
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
            router.handle(message(json, 5301L, "applicant", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_YES)));
            router.handle(message(json, 5301L, "applicant", "en", "30"));
            router.handle(message(json, 5301L, "applicant", "en", "Experienced"));
            router.handle(message(json, 5301L, "applicant", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_YES)));

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
            assertTrue(telegram.last().markup().toString().contains("Обращения"));
            assertTrue(telegram.last().markup().toString().contains("Управление сервером"));
        }
    }

    @Test
    void menuAndAdmCommandsRespectChatContextMentionsAndModeratorIdentity() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("commands.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            context.register(6001L, "MenuPlayer");
            router.handle(message(json, 6001L, "ordinary", "en", "/menu"));
            assertTrue(telegram.last().markup().toString().contains("My account"));

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/menu@ExampleBot"));
            assertTrue(telegram.last().markup().toString().contains("Apply for registration"));
            assertFalse(telegram.last().markup().toString().contains("New tickets"));

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/adm"));
            assertTrue(telegram.last().markup().toString().contains("Requests"));

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", localization.text(
                            BotLocale.ENGLISH, MessageKey.BUTTON_MAIN_MENU)));
            assertTrue(telegram.sent.get(1).markup().toString().contains("Apply for registration"));
            assertFalse(telegram.sent.get(1).markup().toString().contains("New tickets"));

            router.handle(groupMessage(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "/menu@ExampleBot"));
            assertEquals(ADMIN_CHAT_ID, telegram.last().chatId());
            assertTrue(telegram.last().markup().toString().contains("Apply for registration"));
            assertFalse(telegram.last().markup().toString().contains("Requests"));

            router.handle(groupMessage(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "/adm@ExampleBot"));
            assertEquals(ADMIN_CHAT_ID, telegram.last().chatId());

            int beforeOtherBot = telegram.sent.size();
            router.handle(groupMessage(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "/menu@AnotherBot"));
            assertEquals(beforeOtherBot, telegram.sent.size());

            router.handle(message(json, 6001L, "ordinary", "en", "/adm"));
            assertTrue(telegram.last().text().contains("not authorized"));
        }
    }

    @Test
    void serverInformationBackAlwaysReturnsToUserMenuIncludingForModerator() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("server-information-back.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            long ordinaryUser = 6002L;
            context.register(ordinaryUser, "OrdinaryPlayer");
            router.handle(message(json, ordinaryUser, "ordinary", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SERVER_INFORMATION)));
            assertTrue(telegram.last().markup().toString().contains("server_info:back"));
            router.handle(callback(json, ordinaryUser, "ordinary", "server_info:back"));
            assertTrue(telegram.last().markup().toString().contains("My account"));
            assertFalse(telegram.last().markup().toString().contains("Requests"));

            context.register(BackendTestContext.MODERATOR_ID, "ModeratorPlayer");
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SERVER_INFORMATION)));
            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator", "server_info:back"));
            assertTrue(telegram.last().markup().toString().contains("My account"));
            assertTrue(telegram.last().markup().toString().contains("Administration"));
            assertFalse(telegram.last().markup().toString().contains("Requests"));

            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en", "/adm"));
            assertTrue(telegram.last().markup().toString().contains("Requests"));
            assertFalse(telegram.last().markup().toString().contains("My account"));
        }
    }

    @Test
    void legacyServerInformationBackLabelAlsoReturnsModeratorToUserMenu() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("server-information-legacy-back.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            context.register(BackendTestContext.MODERATOR_ID, "ModeratorPlayer");

            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SERVER_INFORMATION)));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_BACK)));

            assertTrue(telegram.last().markup().toString().contains("My account"));
            assertFalse(telegram.last().markup().toString().contains("Requests"));
        }
    }

    @Test
    void moderatorSearchesDisablesAndEnablesOneAccountWithExplicitConfirmation() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("account-admin.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService();
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.RUSSIAN, BotLocale.RUSSIAN);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long ownerId = 6002L;
            long accountId = context.register(ownerId, "StatePlayer");
            locales.setUserLocale(ownerId, BotLocale.RUSSIAN);
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "ru", "/adm"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "ru",
                    localization.text(BotLocale.RUSSIAN, MessageKey.MENU_SEARCH)));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "ru", "stateplayer"));
            assertTrue(telegram.last().text().contains("Minecraft: StatePlayer"));
            assertTrue(telegram.last().text().contains("11.09.2026 15:00 МСК"));
            assertTrue(telegram.last().markup().toString().contains("Отключить аккаунт"));

            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "admin-account:" + accountId + ":disable"));
            assertTrue(telegram.last().text().contains("Укажите причину"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "ru",
                    "Нарушение правил"));
            assertTrue(telegram.last().text().contains("Игрок больше не сможет авторизоваться"));
            assertTrue(context.accounts.accountsFor(ownerId).getFirst().enabled());

            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "admin-account:" + accountId + ":disable_confirm"));
            assertFalse(context.accounts.accountsFor(ownerId).getFirst().enabled());
            assertTrue(telegram.lastForChat(BackendTestContext.MODERATOR_ID).text().contains("15:00 МСК"));
            assertTrue(telegram.lastForChat(ownerId).text().contains("отключён"));

            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "admin-account:" + accountId + ":enable"));
            assertTrue(context.accounts.accountsFor(ownerId).getFirst().enabled());
            assertTrue(telegram.lastForChat(ownerId).text().contains("включён"));
        }
    }

    @Test
    void groupReplyPersistsAndCompletesInModeratorPrivateChat() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("reply.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long ownerId = 6101L;
            long accountId = context.register(ownerId, "ReplyPlayer");
            long ticketId = context.tickets.createProblem(
                    ownerId, "ticket_owner", accountId, "Need help", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticketId, moderator);

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":reply"));
            assertEquals(ticketId, context.pendingModeratorActions
                    .find(BackendTestContext.MODERATOR_ID).orElseThrow().ticketId());
            assertTrue(telegram.lastForChat(BackendTestContext.MODERATOR_ID).text()
                    .contains("ticket #" + ticketId));

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "Please try again now."));

            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
            TicketDetail detail = context.tickets.ticketForModerator(moderator, ticketId);
            assertEquals(1, detail.messages().size());
            assertEquals("Please try again now.", detail.messages().getFirst().text());
            assertTrue(telegram.lastForChat(ownerId).text().contains("Please try again now."));
            assertTrue(telegram.lastForChat(BackendTestContext.MODERATOR_ID).text().contains("Reply sent"));
        }
    }

    @Test
    void cancelClearsPersistedModeratorReply() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("cancel.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long accountId = context.register(6201L, "CancelPlayer");
            long ticketId = context.tickets.createProblem(
                    6201L, "owner", accountId, "Problem", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticketId, moderator);
            context.pendingModeratorActions.beginReply(moderator, ticketId);

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/cancel"));

            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
            assertTrue(telegram.last().text().contains("cancelled"));
            assertTrue(telegram.last().markup().toString().contains("Requests"));

            context.pendingModeratorActions.beginReply(moderator, ticketId);
            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/menu"));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
            assertTrue(telegram.last().text().contains("Welcome"));
            assertFalse(telegram.last().markup().toString().contains("New tickets"));

            context.pendingModeratorActions.beginClosureComment(
                    moderator, ticketId, dev.example.mcauth.api.TicketStatus.RESOLVED);
            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/cancel"));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
            assertEquals(dev.example.mcauth.api.TicketStatus.IN_PROGRESS,
                    context.tickets.ticketForUser(6201L, ticketId).ticket().status());
        }
    }

    @Test
    void moderatorCanResolveWithPublicClosingComment() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("closure-router.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long ownerId = 6210L;
            long accountId = context.register(ownerId, "ClosurePlayer");
            long ticketId = context.tickets.createProblem(
                    ownerId, "owner", accountId, "Problem", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticketId, moderator);

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":resolve"));
            SentMessage choice = telegram.lastForChat(ADMIN_CHAT_ID);
            assertTrue(choice.text().contains("Add a closing comment"));
            assertTrue(choice.markup().toString().contains("resolve_comment"));
            assertEquals(dev.example.mcauth.api.TicketStatus.IN_PROGRESS,
                    context.tickets.ticketForUser(ownerId, ticketId).ticket().status());

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":resolve_comment"));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isPresent());
            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "   "));
            assertTrue(telegram.lastForChat(BackendTestContext.MODERATOR_ID).text()
                    .contains("comment is required"));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isPresent());
            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "Resolved after reviewing the logs."));

            TicketDetail resolved = context.tickets.ticketForUser(ownerId, ticketId);
            assertEquals(dev.example.mcauth.api.TicketStatus.RESOLVED, resolved.ticket().status());
            assertEquals("CLOSURE_COMMENT", resolved.messages().getFirst().messageType());
            assertEquals("PUBLIC", resolved.messages().getFirst().visibility());
            assertTrue(telegram.lastForChat(ownerId).text().contains("Administrator comment"));
            assertTrue(telegram.lastForChat(ownerId).text().contains("Resolved after reviewing the logs."));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
        }
    }

    @Test
    void moderatorCanResolveWithoutClosingComment() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(
                temporaryDirectory.resolve("closure-router-no-comment.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long ownerId = 6211L;
            long accountId = context.register(ownerId, "NoCommentPlayer");
            long ticketId = context.tickets.createProblem(
                    ownerId, "owner", accountId, "Problem", List.of());
            context.tickets.claim(ticketId, new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator"));

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":resolve_now"));

            TicketDetail resolved = context.tickets.ticketForUser(ownerId, ticketId);
            assertEquals(dev.example.mcauth.api.TicketStatus.RESOLVED, resolved.ticket().status());
            assertTrue(resolved.messages().isEmpty());
            assertFalse(telegram.lastForChat(ownerId).text().contains("Administrator comment"));

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":resolve_now"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("already been closed"));
            assertTrue(context.tickets.ticketForUser(ownerId, ticketId).messages().isEmpty());
        }
    }

    @Test
    void ticketListAndDetailsRenderMoscowTime() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("ticket-time.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService();
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long ownerId = 6202L;
            context.register(ownerId, "TimePlayer");

            router.handle(message(json, ownerId, "time_user", "en", "/tickets"));
            assertTrue(telegram.last().text().contains("11.09.2026 15:00 MSK"));
            router.handle(callback(json, ownerId, "time_user", "ticket:1:view"));
            assertTrue(telegram.last().text().contains("11.09.2026 15:00 MSK"));
        }
    }

    @Test
    void staleModeratorCallbackOffersRefresh() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("stale.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long accountId = context.register(6251L, "StalePlayer");
            long ticketId = context.tickets.createProblem(
                    6251L, "owner", accountId, "Problem", List.of());
            ModeratorIdentity moderator = new ModeratorIdentity(
                    BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticketId, moderator);
            context.tickets.transition(ticketId, moderator, dev.example.mcauth.api.TicketStatus.RESOLVED);

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + ticketId + ":reply"));

            SentMessage stale = telegram.lastForChat(ADMIN_CHAT_ID);
            assertTrue(stale.text().contains("already been processed"));
            assertTrue(stale.markup().toString().contains("ticket:" + ticketId + ":refresh"));
        }
    }

    @Test
    void callbacksForDeletedRecordsAreSafeLocalizedNoOps() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("deleted-callback.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator", "ticket:999999:approve"));
            assertTrue(telegram.last().text().contains("already been processed"));

            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "admin-account:999999:disable"));
            assertTrue(telegram.last().text().contains("no longer available"));
            assertTrue(telegram.last().markup().toString().contains("nav:admin"));
        }
    }

    @Test
    void violationWizardAcceptsUnknownAndKnownOffenderNicknames() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("violations.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            context.register(6301L, "ReporterOne");
            router.handle(message(json, 6301L, "reporter", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_REPORT_VIOLATION)));
            assertTrue(telegram.last().text().contains("Do you know"));
            router.handle(message(json, 6301L, "reporter", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_UNKNOWN)));
            router.handle(message(json, 6301L, "reporter", "en", "Unknown offender report"));
            router.handle(message(json, 6301L, "reporter", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_SUBMIT)));
            TicketDetail unknown = context.tickets.ticketForUser(6301L,
                    context.tickets.ticketsForUser(6301L, 0).getFirst().id());
            assertEquals("", unknown.fields().get("offender_nickname"));

            context.register(6302L, "ReporterTwo");
            router.handle(message(json, 6302L, "reporter2", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_REPORT_VIOLATION)));
            router.handle(message(json, 6302L, "reporter2", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_YES)));
            router.handle(message(json, 6302L, "reporter2", "en", "SuspectName"));
            router.handle(message(json, 6302L, "reporter2", "en", "Known offender report"));
            router.handle(message(json, 6302L, "reporter2", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.BUTTON_SUBMIT)));
            TicketDetail known = context.tickets.ticketForUser(6302L,
                    context.tickets.ticketsForUser(6302L, 0).getFirst().id());
            assertEquals("SuspectName", known.fields().get("offender_nickname"));
        }
    }

    @Test
    void moderatorTicketViewsRenderUsernameOrLocalizedFallbackBesideNumericId() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("identity.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);
            long account = context.register(6401L, "IdentityPlayer");
            long withUsername = context.tickets.createProblem(
                    6401L, "visible_name", account, "One", List.of());
            long withoutUsername = context.tickets.createProblem(
                    6401L, "", account, "Two", List.of());

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + withUsername + ":refresh"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("6401 (@visible_name)"));

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "ticket:" + withoutUsername + ":refresh"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).text().contains("6401 (no username)"));
        }
    }

    @Test
    void subscriptionGateBlocksWithoutDraftAndRetryContinuesRegistration() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("subscription.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            telegram.membership = TelegramMembership.NOT_SUBSCRIBED;
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, true,
                    "@example_channel", "https://t.me/example_channel", null, null);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales, features);

            router.handle(message(json, 6501L, "subscriber", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_APPLY)));
            assertTrue(telegram.last().text().contains("must subscribe"));
            assertTrue(telegram.last().markup().toString().contains("registration:subscription:retry"));
            assertTrue(context.tickets.pendingRegistration(6501L).isEmpty());

            telegram.membership = TelegramMembership.SUBSCRIBED;
            router.handle(callback(json, 6501L, "subscriber", "registration:subscription:retry"));
            assertTrue(telegram.last().text().contains("nickname"));
        }
    }

    @Test
    void subscriptionFailureIsRetryableAndDisabledGateStartsNormally() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("subscription-error.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramFeatureConfig enabled = new TelegramFeatureConfig(null, null, true,
                    "@example_channel", "https://t.me/example_channel", null, null);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales, enabled);
            telegram.membershipFailure = true;

            router.handle(message(json, 6502L, "network", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_APPLY)));
            assertTrue(telegram.last().text().contains("temporarily unavailable"));
            assertTrue(context.tickets.pendingRegistration(6502L).isEmpty());

            telegram.membershipFailure = false;
            router.handle(callback(json, 6502L, "network", "registration:subscription:retry"));
            assertTrue(telegram.last().text().contains("nickname"));

            FakeTelegramClient disabledTelegram = new FakeTelegramClient();
            TelegramUpdateRouter disabled = router(disabledTelegram, json, context,
                    localization, locales, TelegramFeatureConfig.defaults());
            disabled.handle(message(json, 6503L, "ungated", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_APPLY)));
            assertTrue(disabledTelegram.last().text().contains("nickname"));
            assertTrue(context.tickets.pendingRegistration(6503L).isEmpty());
        }
    }

    @Test
    void configuredHowToPlayAndExternalLinksAreRendered() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("features.db"))) {
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(
                    context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramFeatureConfig features = new TelegramFeatureConfig(
                    "Configured English guide", "Настроенная русская инструкция", false, null, null,
                    "https://map.example.com/", "https://t.me/example/123");
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales, features);

            router.handle(message(json, 6504L, "features", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_HOW_TO_PLAY)));
            assertEquals("Configured English guide", telegram.last().text());
            router.handle(message(json, 6504L, "features", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SERVER_MAP)));
            assertTrue(telegram.last().markup().toString().contains("https://map.example.com/"));
            router.handle(message(json, 6504L, "features", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_MODPACK_DOWNLOAD)));
            assertTrue(telegram.last().markup().toString().contains("https://t.me/example/123"));
        }
    }

    @Test
    void blockedUserReceivesOnlyRestrictedResponseForCommandsAndWorkflowButtons() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("blocked-router.db"))) {
            long user = 6601L;
            context.register(user, "BlockedPlayer");
            context.userModeration.setBlocked(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "moderator"), user, true, "spam");
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            router.handle(message(json, user, "blocked", "en", "/menu"));
            assertEquals(localization.text(BotLocale.ENGLISH, MessageKey.BOT_ACCESS_RESTRICTED),
                    telegram.last().text());
            router.handle(message(json, user, "blocked", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_REPORT_PROBLEM)));
            assertEquals(localization.text(BotLocale.ENGLISH, MessageKey.BOT_ACCESS_RESTRICTED),
                    telegram.last().text());
        }
    }

    @Test
    void chatTagSelectionUsesOwnedEnabledAccountAndSurvivesTelegramFailure() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("chat-tags.db"))) {
            long userId = 6701L;
            long accountId = context.register(userId, "TagPlayer");
            context.register(6702L, "OtherPlayer");
            FakeTelegramClient telegram = new FakeTelegramClient();
            TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                    null, null, true, 60, true, true, true,
                    true, -1001234567890L, true);
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales, features);

            router.handle(message(json, userId, "tag_user", "en",
                    localization.text(BotLocale.ENGLISH, MessageKey.MENU_SETTINGS)));
            assertTrue(telegram.last().markup().toString().contains("Chat Tag"));
            router.handle(callback(json, userId, "tag_user", "chat_tag:open"));
            assertTrue(telegram.last().markup().toString().contains("TagPlayer"));
            assertFalse(telegram.last().markup().toString().contains("OtherPlayer"));

            telegram.tagFailure = true;
            router.handle(callback(json, userId, "tag_user", "chat_tag:account:" + accountId));
            assertEquals(accountId, context.accounts.chatTagPreference(userId).accountId());

            telegram.tagFailure = false;
            router.handle(callback(json, userId, "tag_user", "chat_tag:none"));
            assertFalse(context.accounts.chatTagPreference(userId).hasTag());
            assertEquals("", telegram.tag);
        }
    }

    @Test
    void chatTagSkipsTelegramAdministratorsButAllowsMctgAuthModerator() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("chat-tag-admin.db"))) {
            long accountId = context.register(BackendTestContext.MODERATOR_ID, "ModeratorTag");
            FakeTelegramClient telegram = new FakeTelegramClient();
            TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                    null, null, true, 60, true, true, true,
                    true, -1001234567890L, true);
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales, features);

            telegram.chatMemberRole = TelegramChatMember.Role.ADMINISTRATOR;
            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "chat_tag:account:" + accountId));
            assertEquals(accountId,
                    context.accounts.chatTagPreference(BackendTestContext.MODERATOR_ID).accountId());
            assertEquals(0, telegram.tagUpdates);

            telegram.chatMemberRole = TelegramChatMember.Role.MEMBER;
            router.handle(callback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "chat_tag:account:" + accountId));
            assertEquals("ModeratorTag", telegram.tag);
            assertEquals(1, telegram.tagUpdates);
        }
    }

    @Test
    void registrationCanBeRejectedWithPersistedLocalizedReason() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("reject-reason.db"))) {
            long userId = 6801L;
            long ticketId = context.registrations.submitRegistration(new RegistrationSubmission(
                    userId, "applicant", "RejectedPlayer", true, 25, "Experience"));
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "moderator");
            context.tickets.claim(ticketId, moderator);
            FakeTelegramClient telegram = new FakeTelegramClient();
            LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
            LocaleResolver locales = new LocaleResolver(context.database, BotLocale.ENGLISH, BotLocale.ENGLISH);
            TelegramUpdateRouter router = router(telegram, json, context, localization, locales);

            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "ticket:" + ticketId + ":reject"));
            assertTrue(telegram.lastForChat(ADMIN_CHAT_ID).markup().toString().contains("reject_reason"));
            router.handle(groupCallback(json, BackendTestContext.MODERATOR_ID, "moderator",
                    "ticket:" + ticketId + ":reject_reason"));
            router.handle(message(json, BackendTestContext.MODERATOR_ID, "moderator", "en",
                    "Minecraft profile does not match the request."));

            String comment = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT comment FROM ticket_decisions WHERE ticket_id = ?")) {
                    statement.setLong(1, ticketId);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        return result.getString(1);
                    }
                }
            });
            assertEquals("Minecraft profile does not match the request.", comment);
            assertTrue(telegram.lastForChat(userId).text().contains("Reason:"));
            assertTrue(telegram.lastForChat(userId).text().contains(comment));
        }
    }

    private static TelegramUpdateRouter router(
            TelegramClient telegram, ObjectMapper json, BackendTestContext context,
            LocalizationService localization, LocaleResolver locales) {
        return router(telegram, json, context, localization, locales, TelegramFeatureConfig.defaults());
    }

    private static TelegramUpdateRouter router(
            TelegramClient telegram, ObjectMapper json, BackendTestContext context,
            LocalizationService localization, LocaleResolver locales, TelegramFeatureConfig features) {
        return new TelegramUpdateRouter(telegram, "ExampleBot", ADMIN_CHAT_ID,
                "https://t.me/example/1", features, "survival-main", context.registrations,
                context.tickets, context.accounts, context.moderators, context.commands,
                context.pendingModeratorActions, context.telemetry, context.userModeration,
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

    private static JsonNode groupMessage(
            ObjectMapper json, long id, String username, String text) {
        var update = message(json, id, username, "en", text).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.path("message").path("chat"))
                .put("id", ADMIN_CHAT_ID).put("type", "supergroup");
        return update;
    }

    private static JsonNode groupCallback(
            ObjectMapper json, long id, String username, String data) {
        var update = callback(json, id, username, data).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.path("callback_query")
                .path("message").path("chat"))
                .put("id", ADMIN_CHAT_ID).put("type", "supergroup");
        return update;
    }

    private static final class FakeTelegramClient implements TelegramClient {
        private final List<SentMessage> sent = new ArrayList<>();
        private TelegramMembership membership = TelegramMembership.SUBSCRIBED;
        private boolean membershipFailure;
        private TelegramChatMember.Role chatMemberRole = TelegramChatMember.Role.MEMBER;
        private boolean tagFailure;
        private String tag = "";
        private int tagUpdates;
        private int edits;
        private int deletes;
        private SentMessage lastTouched;
        private final java.util.Map<Long, SentMessage> lastTouchedByChat = new java.util.HashMap<>();

        @Override public List<JsonNode> getUpdates(long offset) { return List.of(); }

        @Override public TelegramMembership getChatMembership(String channel, long telegramUserId) throws IOException {
            if (membershipFailure) throw new IOException("simulated Telegram outage");
            return membership;
        }

        @Override public TelegramChatMember getChatMember(long chatId, long telegramUserId) throws IOException {
            if (tagFailure) throw new IOException("simulated tag outage");
            return new TelegramChatMember(chatMemberRole, tag);
        }

        @Override public void setChatMemberTag(long chatId, long telegramUserId, String value) throws IOException {
            if (tagFailure) throw new IOException("simulated tag outage");
            tag = value;
            tagUpdates++;
        }

        @Override public long sendMessage(long chatId, String text, JsonNode replyMarkup) {
            SentMessage message = new SentMessage(chatId, text, replyMarkup);
            sent.add(message);
            lastTouched = message;
            lastTouchedByChat.put(chatId, message);
            return sent.size();
        }

        @Override public void editMessage(long chatId, long messageId, String text) {
            editMessage(chatId, messageId, text, null);
        }

        @Override public void editMessage(long chatId, long messageId, String text, JsonNode replyMarkup) {
            edits++;
            int index = Math.toIntExact(messageId - 1);
            if (index >= 0 && index < sent.size() && sent.get(index).chatId() == chatId) {
                SentMessage current = sent.get(index);
                SentMessage edited = new SentMessage(chatId, text,
                        replyMarkup == null ? current.markup() : replyMarkup);
                sent.set(index, edited);
                lastTouched = edited;
                lastTouchedByChat.put(chatId, edited);
            }
        }

        @Override public void deleteMessage(long chatId, long messageId) { deletes++; }

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
