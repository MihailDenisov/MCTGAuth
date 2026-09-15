package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

enum TelegramMembership {
    SUBSCRIBED,
    NOT_SUBSCRIBED;

    static TelegramMembership fromChatMember(JsonNode member) throws IOException {
        if (!member.isObject() || !member.path("status").isTextual()) {
            throw new TelegramApiClient.TelegramApiException("Telegram returned an invalid chat member response");
        }
        return switch (member.path("status").asText()) {
            case "creator", "administrator", "member" -> SUBSCRIBED;
            case "restricted" -> member.path("is_member").asBoolean(false) ? SUBSCRIBED : NOT_SUBSCRIBED;
            case "left", "kicked" -> NOT_SUBSCRIBED;
            default -> throw new TelegramApiClient.TelegramApiException(
                    "Telegram returned an unknown chat member status");
        };
    }
}
