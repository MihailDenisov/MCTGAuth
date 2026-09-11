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
        body.putArray("allowed_updates").add("message").add("callback_query");
        JsonNode result = call("getUpdates", body).path("result");
        if (!result.isArray()) {
            throw new TelegramApiException("Telegram returned an invalid updates response");
        }
        return result.valueStream().toList();
    }

    @Override
    public long sendMessage(long chatId, String text, JsonNode replyMarkup) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyMarkup != null) {
            body.set("reply_markup", replyMarkup);
        }
        JsonNode response = call("sendMessage", body);
        return response.path("result").path("message_id").asLong();
    }

    @Override
    public void editMessage(long chatId, long messageId, String text) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        call("editMessageText", body);
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
        if (response.statusCode() / 100 != 2) {
            throw new TelegramApiException("Telegram request failed with HTTP " + response.statusCode());
        }
        JsonNode parsed = json.readTree(response.body());
        if (!parsed.path("ok").asBoolean(false)) {
            throw new TelegramApiException("Telegram rejected the request");
        }
        return parsed;
    }

    static final class TelegramApiException extends IOException {
        TelegramApiException(String message) {
            super(message);
        }
    }
}
