package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

final class TelegramApiClient implements TelegramClient {
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final String apiBase;

    TelegramApiClient(String botToken, ObjectMapper json) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = json;
        this.apiBase = "https://api.telegram.org/bot" + botToken + "/";
    }

    @Override
    public List<JsonNode> getUpdates(long offset) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", 30);
        body.putArray("allowed_updates").add("message").add("callback_query").add("chat_member");
        JsonNode result = call("getUpdates", body).path("result");
        if (!result.isArray()) {
            throw new TelegramApiException("Telegram returned an invalid updates response");
        }
        return result.valueStream().toList();
    }

    @Override
    public TelegramMembership getChatMembership(String channel, long telegramUserId)
            throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", channel);
        body.put("user_id", telegramUserId);
        return TelegramMembership.fromChatMember(call("getChatMember", body).path("result"));
    }

    @Override
    public TelegramChatMember getChatMember(long chatId, long telegramUserId)
            throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("user_id", telegramUserId);
        return TelegramChatMember.fromTelegram(call("getChatMember", body).path("result"));
    }

    @Override
    public void setChatMemberTag(long chatId, long telegramUserId, String tag)
            throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("user_id", telegramUserId);
        body.put("tag", tag == null ? "" : tag);
        call("setChatMemberTag", body);
    }

    @Override
    public long sendMessage(long chatId, String text, JsonNode replyMarkup) throws IOException, InterruptedException {
        return sendMessage(chatId, text, replyMarkup, null);
    }

    @Override
    public long sendHtmlMessage(long chatId, String text, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        return sendMessage(chatId, text, replyMarkup, "HTML");
    }

    private long sendMessage(long chatId, String text, JsonNode replyMarkup, String parseMode)
            throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (parseMode != null) body.put("parse_mode", parseMode);
        if (replyMarkup != null) {
            body.set("reply_markup", replyMarkup);
        }
        JsonNode response = call("sendMessage", body);
        return response.path("result").path("message_id").asLong();
    }

    @Override
    public void editMessage(long chatId, long messageId, String text) throws IOException, InterruptedException {
        editMessage(chatId, messageId, text, null);
    }

    @Override
    public void editMessage(long chatId, long messageId, String text, JsonNode replyMarkup)
            throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (replyMarkup != null) body.set("reply_markup", replyMarkup);
        call("editMessageText", body);
    }

    @Override
    public void deleteMessage(long chatId, long messageId) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        call("deleteMessage", body);
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("callback_query_id", callbackQueryId);
        body.put("text", text);
        call("answerCallbackQuery", body);
    }

    @Override
    public void sendAttachment(long chatId, AttachmentReference attachment)
            throws IOException, InterruptedException {
        String method = switch (attachment.fileType()) {
            case "PHOTO" -> "sendPhoto";
            case "VIDEO" -> "sendVideo";
            case "DOCUMENT" -> "sendDocument";
            case "ANIMATION" -> "sendAnimation";
            default -> throw new IllegalArgumentException("Unsupported Telegram attachment type");
        };
        String field = attachment.fileType().toLowerCase(java.util.Locale.ROOT);
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put(field, attachment.fileId());
        if (attachment.caption() != null && !attachment.caption().isBlank()) {
            body.put("caption", attachment.caption());
        }
        call(method, body);
    }

    private JsonNode call(String method, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + method))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = json.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new TelegramApiException(response.statusCode(),
                    parsed.path("description").asText("Telegram request failed"));
        }
        if (!parsed.path("ok").asBoolean(false)) {
            throw new TelegramApiException(parsed.path("error_code").asInt(0),
                    parsed.path("description").asText("Telegram rejected the request"));
        }
        return parsed;
    }

    static final class TelegramApiException extends IOException {
        private final int errorCode;

        TelegramApiException(String message) {
            this(0, message);
        }

        TelegramApiException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        int errorCode() {
            return errorCode;
        }

        boolean isMessageNotModified() {
            return getMessage() != null
                    && getMessage().toLowerCase(java.util.Locale.ROOT).contains("message is not modified");
        }
    }
}
