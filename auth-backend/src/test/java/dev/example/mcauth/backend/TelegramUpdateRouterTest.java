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
            assertTrue(telegram.last().markup().toString().contains("Новые обращения"));
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
            assertTrue(telegram.last().markup().toString().contains("New tickets"));

            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", localization.text(
                            BotLocale.ENGLISH, MessageKey.BUTTON_MAIN_MENU)));
            assertTrue(telegram.last().markup().toString().contains("Apply for registration"));
            assertFalse(telegram.last().markup().toString().contains("New tickets"));

            router.handle(groupMessage(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "/menu@ExampleBot"));
            assertEquals(ADMIN_CHAT_ID, telegram.last().chatId());
            assertTrue(telegram.last().markup().has("inline_keyboard"));

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
            assertTrue(telegram.last().markup().toString().contains("New tickets"));

            context.pendingModeratorActions.beginReply(moderator, ticketId);
            router.handle(message(json, BackendTestContext.MODERATOR_ID,
                    "moderator", "en", "/menu"));
            assertTrue(context.pendingModeratorActions.find(BackendTestContext.MODERATOR_ID).isEmpty());
            assertTrue(telegram.last().text().contains("Welcome"));
            assertFalse(telegram.last().markup().toString().contains("New tickets"));
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
                context.pendingModeratorActions, localization, locales, json);
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

        @Override public List<JsonNode> getUpdates(long offset) { return List.of(); }

        @Override public TelegramMembership getChatMembership(String channel, long telegramUserId) throws IOException {
            if (membershipFailure) throw new IOException("simulated Telegram outage");
            return membership;
        }

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
