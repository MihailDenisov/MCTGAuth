package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

interface TelegramClient {
    List<JsonNode> getUpdates(long offset) throws IOException, InterruptedException;
    TelegramMembership getChatMembership(String channel, long telegramUserId)
            throws IOException, InterruptedException;
    default TelegramChatMember getChatMember(long chatId, long telegramUserId)
            throws IOException, InterruptedException {
        TelegramMembership membership = getChatMembership(Long.toString(chatId), telegramUserId);
        return new TelegramChatMember(membership == TelegramMembership.SUBSCRIBED
                ? TelegramChatMember.Role.MEMBER : TelegramChatMember.Role.NOT_MEMBER, "");
    }
    default void setChatMemberTag(long chatId, long telegramUserId, String tag)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Telegram member tags are unavailable");
    }
    long sendMessage(long chatId, String text, JsonNode replyMarkup) throws IOException, InterruptedException;
    default long sendHtmlMessage(long chatId, String html, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        return sendMessage(chatId, html, replyMarkup);
    }
    void editMessage(long chatId, long messageId, String text) throws IOException, InterruptedException;
    default void editMessage(long chatId, long messageId, String text, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        editMessage(chatId, messageId, text);
    }
    default void deleteMessage(long chatId, long messageId) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Telegram message deletion is unavailable");
    }
    void answerCallback(String callbackQueryId, String text) throws IOException, InterruptedException;
    void sendAttachment(long chatId, AttachmentReference attachment) throws IOException, InterruptedException;
}
