package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

record TelegramChatMember(Role role, String tag) {
    enum Role { OWNER, ADMINISTRATOR, MEMBER, NOT_MEMBER }

    boolean isMember() {
        return role != Role.NOT_MEMBER;
    }

    boolean isAdministrator() {
        return role == Role.OWNER || role == Role.ADMINISTRATOR;
    }

    static TelegramChatMember fromTelegram(JsonNode member) throws IOException {
        if (!member.isObject() || !member.path("status").isTextual()) {
            throw new TelegramApiClient.TelegramApiException("Telegram returned an invalid chat member response");
        }
        Role role = switch (member.path("status").asText()) {
            case "creator" -> Role.OWNER;
            case "administrator" -> Role.ADMINISTRATOR;
            case "member" -> Role.MEMBER;
            case "restricted" -> member.path("is_member").asBoolean(false)
                    ? Role.MEMBER : Role.NOT_MEMBER;
            case "left", "kicked" -> Role.NOT_MEMBER;
            default -> throw new TelegramApiClient.TelegramApiException(
                    "Telegram returned an unknown chat member status");
        };
        return new TelegramChatMember(role, member.path("tag").asText(""));
    }
}
