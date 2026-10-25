package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramUiServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void navigationCreatesThenEditsOneMessageIncludingMarkup() throws Exception {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);

        long first = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "Main", markup("main"));
        long second = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "settings", "Settings", markup("back"));

        assertEquals(first, second);
        assertEquals(1, telegram.messages.size());
        assertEquals(1, telegram.edits);
        assertEquals("Settings", telegram.messages.getFirst().text());
        assertEquals("back", telegram.messages.getFirst().markup().path("screen").asText());
    }

    @Test
    void failedEditCreatesExactlyOneReplacementAndUpdatesSession() throws Exception {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);
        long first = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "Main", null);

        telegram.nextEditFailure = new IOException("message was deleted");
        long replacement = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "settings", "Settings", null);

        assertNotEquals(first, replacement);
        assertEquals(2, telegram.messages.size());
        assertEquals(replacement, ui.activeMessageId(10, 10, TelegramUiService.UiType.USER));
    }

    @Test
    void messageNotModifiedIsSuccessfulNoOpWithoutReplacement() throws Exception {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);
        long first = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "Main", null);

        telegram.nextEditFailure = new IOException("Bad Request: message is not modified");
        long unchanged = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "Main", null);

        assertEquals(first, unchanged);
        assertEquals(1, telegram.messages.size());
    }

    @Test
    void userAdminAndModeratorSessionsAreIndependent() throws Exception {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);

        long user = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "User", null);
        long admin = ui.renderNavigation(10, 10, TelegramUiService.UiType.ADMIN,
                "main", "Admin", null);
        long otherModerator = ui.renderNavigation(11, -100, TelegramUiService.UiType.ADMIN,
                "main", "Other", null);

        assertNotEquals(user, admin);
        assertNotEquals(admin, otherModerator);
        assertEquals(10L, ui.navigationOwner(10, admin, TelegramUiService.UiType.ADMIN));
        assertEquals(11L, ui.navigationOwner(-100, otherModerator, TelegramUiService.UiType.ADMIN));
    }

    @Test
    void sensitiveSecretNeverBecomesNavigationOrReceivesMarkup() throws Exception {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);
        long navigation = ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "settings", "Settings", markup("settings"));

        long secret = ui.sendSensitiveSecret(10, "secret");
        ui.renderNavigation(10, 10, TelegramUiService.UiType.USER,
                "main", "Main", markup("main"));

        assertNotEquals(navigation, secret);
        assertEquals(navigation, ui.activeMessageId(10, 10, TelegramUiService.UiType.USER));
        assertEquals("secret", telegram.messages.get(Math.toIntExact(secret - 1)).text());
        assertEquals("Main", telegram.messages.get(Math.toIntExact(navigation - 1)).text());
        assertThrows(IllegalArgumentException.class, () -> ui.sendStandalone(
                BotMessagePurpose.SENSITIVE_SECRET, 10, "secret", markup("unsafe")));
    }

    @Test
    void temporaryAdminInputDeletionFailureDoesNotFailWorkflow() {
        FakeTelegram telegram = new FakeTelegram();
        TelegramUiService ui = new TelegramUiService(telegram);
        telegram.deleteFailure = true;

        ui.deleteTemporaryAdminInput(-100, 42);

        assertEquals(1, telegram.deleteAttempts);
    }

    private static JsonNode markup(String screen) {
        return JSON.createObjectNode().put("screen", screen);
    }

    private static final class FakeTelegram implements TelegramClient {
        private final List<Message> messages = new ArrayList<>();
        private IOException nextEditFailure;
        private boolean deleteFailure;
        private int edits;
        private int deleteAttempts;

        @Override public List<JsonNode> getUpdates(long offset) { return List.of(); }
        @Override public TelegramMembership getChatMembership(String channel, long telegramUserId) {
            return TelegramMembership.SUBSCRIBED;
        }
        @Override public long sendMessage(long chatId, String text, JsonNode replyMarkup) {
            messages.add(new Message(chatId, text, replyMarkup));
            return messages.size();
        }
        @Override public void editMessage(long chatId, long messageId, String text) throws IOException {
            editMessage(chatId, messageId, text, null);
        }
        @Override public void editMessage(long chatId, long messageId, String text, JsonNode replyMarkup)
                throws IOException {
            edits++;
            if (nextEditFailure != null) {
                IOException failure = nextEditFailure;
                nextEditFailure = null;
                throw failure;
            }
            int index = Math.toIntExact(messageId - 1);
            Message old = messages.get(index);
            messages.set(index, new Message(chatId, text,
                    replyMarkup == null ? old.markup() : replyMarkup));
        }
        @Override public void deleteMessage(long chatId, long messageId) throws IOException {
            deleteAttempts++;
            if (deleteFailure) throw new IOException("forbidden");
        }
        @Override public void answerCallback(String callbackQueryId, String text) { }
        @Override public void sendAttachment(long chatId, AttachmentReference attachment) { }
    }

    private record Message(long chatId, String text, JsonNode markup) { }
}
