package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/** Standalone Java 21 entry point for Telegram bot, ticket service, persistence and sync HTTP API. */
public final class BackendApplication {
    private BackendApplication() {}

    public static void main(String[] args) throws Exception {
        BackendConfig config = BackendConfig.fromEnvironment(System.getenv());
        BackendDatabase database = BackendDatabase.open(config.databasePath());
        Clock clock = Clock.systemUTC();
        ObjectMapper json = new ObjectMapper();
        TelegramFeatureConfig telegramFeatures = TelegramFeatureConfig.load(
                config.telegramFeatureConfigPath());
        ModeratorService moderators = new ModeratorService(database, clock);
        moderators.synchronizeConfiguredModerators(config.moderatorTelegramIds());
        AuditService audit = new AuditService(database, json, clock);
        SyncEventService syncEvents = new SyncEventService(database, json, audit, clock);
        CredentialService credentials = new CredentialService();
        TicketService tickets = new TicketService(
                database, moderators, credentials, json, clock);
        AccountService accounts = new AccountService(
                database, credentials, syncEvents, audit, moderators, clock);
        SupportTicketService supportTickets = new SupportTicketService(
                database, moderators, credentials, syncEvents, audit, clock);
        RemoteCommandService remoteCommands = new RemoteCommandService(
                database, moderators, audit, json, clock, config.serverId());
        ModeratorPendingActionService pendingModeratorActions =
                new ModeratorPendingActionService(database, moderators, clock);
        ServerTelemetryService telemetry = new ServerTelemetryService(database, clock, config.serverId(),
                java.time.Duration.ofSeconds(telegramFeatures.heartbeatTimeoutSeconds()));
        TelegramUserModerationService userModeration = new TelegramUserModerationService(
                database, moderators, audit, clock);
        LocalizationService localization = new LocalizationService();
        LocaleResolver locales = new LocaleResolver(
                database, config.adminLocale(), config.defaultUserLocale());
        TelegramBotService bot = new TelegramBotService(
                config.telegramBotToken(), config.telegramBotUsername(), config.telegramAdminChatId(),
                config.howToPlayUrl(), telegramFeatures,
                config.serverId(), tickets, supportTickets, accounts, moderators, remoteCommands,
                pendingModeratorActions, telemetry, userModeration, localization, locales, json);
        BackendHttpServer http = new BackendHttpServer(
                config.httpBindAddress(), config.httpPort(), config.authApiToken(), database,
                bot::isRunning, new SyncApiService(syncEvents, config.serverId()), remoteCommands, telemetry, json);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            http.close();
            bot.close();
            try {
                database.close();
            } catch (Exception exception) {
                System.err.println("Database shutdown failed (" + exception.getClass().getSimpleName() + ")");
            }
        }));

        bot.start();
        http.start();
        System.out.println("Authentication backend started on "
                + config.httpBindAddress() + ":" + config.httpPort());
        new CountDownLatch(1).await();
    }
}
