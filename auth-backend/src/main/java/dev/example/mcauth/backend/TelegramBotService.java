package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TelegramBotService implements AutoCloseable {
    private final TelegramClient telegram;
    private final TelegramUpdateRouter router;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread pollingThread;

    public TelegramBotService(
            String botToken,
            String botUsername,
            long adminChatId,
            String howToPlayUrl,
            String serverId,
            TicketService registrationTickets,
            SupportTicketService supportTickets,
            AccountService accounts,
            ModeratorService moderators,
            RemoteCommandService remoteCommands,
            ModeratorPendingActionService pendingModeratorActions,
            LocalizationService localization,
            LocaleResolver locales,
            ObjectMapper json) {
        this(new TelegramApiClient(botToken, json), botUsername, adminChatId, howToPlayUrl, serverId,
                registrationTickets, supportTickets, accounts, moderators, remoteCommands,
                pendingModeratorActions, localization, locales, json);
    }

    TelegramBotService(
            TelegramClient telegram,
            String botUsername,
            long adminChatId,
            String howToPlayUrl,
            String serverId,
            TicketService registrationTickets,
            SupportTicketService supportTickets,
            AccountService accounts,
            ModeratorService moderators,
            RemoteCommandService remoteCommands,
            ModeratorPendingActionService pendingModeratorActions,
            LocalizationService localization,
            LocaleResolver locales,
            ObjectMapper json) {
        this.telegram = Objects.requireNonNull(telegram, "telegram");
        this.router = new TelegramUpdateRouter(telegram, botUsername, adminChatId, howToPlayUrl, serverId,
                registrationTickets, supportTickets, accounts, moderators, remoteCommands,
                pendingModeratorActions, localization, locales, json);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        pollingThread = Thread.ofPlatform().name("telegram-long-poll").start(this::poll);
    }

    public boolean isRunning() {
        return running.get() && pollingThread != null && pollingThread.isAlive();
    }

    private void poll() {
        long offset = 0;
        while (running.get()) {
            try {
                for (JsonNode update : telegram.getUpdates(offset)) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    router.handle(update);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // Do not print exception messages: HTTP failures can contain sensitive request metadata.
                System.err.println("Telegram polling error (" + exception.getClass().getSimpleName() + ")");
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
    }
}
