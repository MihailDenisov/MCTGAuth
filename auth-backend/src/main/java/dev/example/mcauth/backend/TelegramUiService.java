package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class TelegramUiService {
    enum UiType { USER, ADMIN }

    private final TelegramClient telegram;
    private final Map<SessionKey, UiSession> sessions = new ConcurrentHashMap<>();
    private final Map<MessageKey, Long> navigationOwners = new ConcurrentHashMap<>();
    private final Map<SessionKey, Object> sessionLocks = new ConcurrentHashMap<>();

    TelegramUiService(TelegramClient telegram) {
        this.telegram = Objects.requireNonNull(telegram, "telegram");
    }

    long renderNavigation(
            long telegramUserId, long chatId, UiType uiType, String screenId,
            String text, JsonNode replyMarkup) throws IOException, InterruptedException {
        SessionKey key = new SessionKey(telegramUserId, chatId, uiType);
        Object lock = sessionLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            UiSession existing = sessions.get(key);
            if (existing != null) {
                try {
                    telegram.editMessage(chatId, existing.messageId(), text, replyMarkup);
                    sessions.put(key, new UiSession(existing.messageId(), screenId));
                    return existing.messageId();
                } catch (IOException exception) {
                    if (isMessageNotModified(exception)) {
                        sessions.put(key, new UiSession(existing.messageId(), screenId));
                        return existing.messageId();
                    }
                }
            }
            long replacementId = telegram.sendMessage(chatId, text, replyMarkup);
            sessions.put(key, new UiSession(replacementId, screenId));
            navigationOwners.put(new MessageKey(chatId, replacementId, uiType), telegramUserId);
            return replacementId;
        }
    }

    long sendPersistentNotice(long chatId, String text, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        return sendStandalone(BotMessagePurpose.PERSISTENT_NOTICE, chatId, text, replyMarkup);
    }

    long sendSensitiveSecret(long chatId, String html) throws IOException, InterruptedException {
        return sendStandalone(BotMessagePurpose.SENSITIVE_SECRET, chatId, html, null);
    }

    long sendStandalone(BotMessagePurpose purpose, long chatId, String text, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        return switch (Objects.requireNonNull(purpose, "purpose")) {
            case PERSISTENT_NOTICE -> telegram.sendMessage(chatId, text, replyMarkup);
            case SENSITIVE_SECRET -> {
                if (replyMarkup != null) {
                    throw new IllegalArgumentException("Sensitive messages cannot have navigation markup");
                }
                yield telegram.sendHtmlMessage(chatId, text, null);
            }
            case NAVIGATION, TRANSIENT_NOTICE -> throw new IllegalArgumentException(
                    "Navigation and transient messages require a UI session");
        };
    }

    void deleteTemporaryAdminInput(long chatId, long messageId) {
        if (messageId <= 0) return;
        try {
            telegram.deleteMessage(chatId, messageId);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Optional cleanup must never change the completed workflow result.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void clear(long telegramUserId, long chatId, UiType uiType) {
        sessions.remove(new SessionKey(telegramUserId, chatId, uiType));
    }

    boolean owns(
            long telegramUserId, long chatId, UiType uiType, long messageId) {
        UiSession session = sessions.get(new SessionKey(telegramUserId, chatId, uiType));
        return session != null && session.messageId() == messageId;
    }

    Long activeMessageId(long telegramUserId, long chatId, UiType uiType) {
        UiSession session = sessions.get(new SessionKey(telegramUserId, chatId, uiType));
        return session == null ? null : session.messageId();
    }

    boolean hasSession(long telegramUserId, long chatId, UiType uiType) {
        return sessions.containsKey(new SessionKey(telegramUserId, chatId, uiType));
    }

    Long navigationOwner(long chatId, long messageId, UiType uiType) {
        return navigationOwners.get(new MessageKey(chatId, messageId, uiType));
    }

    private static boolean isMessageNotModified(IOException exception) {
        if (exception instanceof TelegramApiClient.TelegramApiException telegramException) {
            return telegramException.isMessageNotModified();
        }
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("message is not modified");
    }

    private record SessionKey(long telegramUserId, long chatId, UiType uiType) {
        private SessionKey {
            if (telegramUserId <= 0) throw new IllegalArgumentException("Invalid Telegram user ID");
            if (chatId == 0) throw new IllegalArgumentException("Invalid chat ID");
            Objects.requireNonNull(uiType, "uiType");
        }
    }

    private record UiSession(long messageId, String screenId) {
        private UiSession {
            if (messageId <= 0) throw new IllegalArgumentException("Invalid message ID");
            Objects.requireNonNull(screenId, "screenId");
        }
    }

    private record MessageKey(long chatId, long messageId, UiType uiType) { }
}
