package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

interface TelegramClient {
    List<JsonNode> getUpdates(long offset) throws IOException, InterruptedException;
    long sendMessage(long chatId, String text, JsonNode replyMarkup) throws IOException, InterruptedException;
    void editMessage(long chatId, long messageId, String text) throws IOException, InterruptedException;
    void answerCallback(String callbackQueryId, String text) throws IOException, InterruptedException;
    void sendAttachment(long chatId, AttachmentReference attachment) throws IOException, InterruptedException;
}
