package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramFeatureConfigTest {
    @TempDir Path temporary;

    @Test
    void loadsUtf8MultilineLocalizedTextAndOptionalFeatures() throws Exception {
        Path file = temporary.resolve("features.yaml");
        Files.writeString(file, """
                howToPlay:
                  en: |
                    First line
                    Second line
                  ru: |
                    Первая строка
                    Вторая строка
                registration:
                  subscriptionCheck:
                    enabled: true
                    channel: "@example_channel"
                    channelUrl: "https://t.me/example_channel"
                serverMap:
                  enabled: true
                  url: "https://map.example.com/"
                modpackDownload:
                  enabled: true
                  url: "https://t.me/example_channel/123"
                memberTags:
                  enabled: true
                  chatId: -1001234567890
                  excludeChatAdministrators: true
                """, StandardCharsets.UTF_8);

        TelegramFeatureConfig config = TelegramFeatureConfig.load(file);
        assertEquals("First line\nSecond line", config.howToPlay(BotLocale.ENGLISH, "fallback"));
        assertEquals("Первая строка\nВторая строка", config.howToPlay(BotLocale.RUSSIAN, "fallback"));
        assertTrue(config.subscriptionCheckAvailable());
        assertEquals("https://map.example.com/", config.serverMapUrl());
        assertEquals("https://t.me/example_channel/123", config.modpackDownloadUrl());
        assertTrue(config.memberTagsEnabled());
        assertEquals(-1001234567890L, config.memberTagsChatId());
        assertTrue(config.excludeChatAdministrators());
    }

    @Test
    void missingOrInvalidConfigurationFallsBackAndHidesBrokenLinks() throws Exception {
        assertEquals("fallback", TelegramFeatureConfig.load(temporary.resolve("missing.yaml"))
                .howToPlay(BotLocale.ENGLISH, "fallback"));
        Path invalid = temporary.resolve("invalid.yaml");
        Files.writeString(invalid, """
                serverMap: { enabled: true, url: "javascript:broken" }
                modpackDownload: { enabled: true, url: "http://insecure.example" }
                registration:
                  subscriptionCheck: { enabled: true, channel: "not-a-channel" }
                memberTags: { enabled: true, chatId: 12345 }
                """, StandardCharsets.UTF_8);
        TelegramFeatureConfig config = TelegramFeatureConfig.load(invalid);
        assertNull(config.serverMapUrl());
        assertNull(config.modpackDownloadUrl());
        assertTrue(config.subscriptionCheckEnabled());
        assertFalse(config.subscriptionCheckAvailable());
        assertFalse(config.memberTagsEnabled());
        assertNull(config.memberTagsChatId());
    }

    @Test
    void classifiesTelegramMembershipStatesStrictly() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String state : new String[]{"creator", "administrator", "member"}) {
            assertEquals(TelegramMembership.SUBSCRIBED,
                    TelegramMembership.fromChatMember(json.readTree("{\"status\":\"" + state + "\"}")));
        }
        assertEquals(TelegramMembership.SUBSCRIBED,
                TelegramMembership.fromChatMember(json.readTree("{\"status\":\"restricted\",\"is_member\":true}")));
        assertEquals(TelegramMembership.NOT_SUBSCRIBED,
                TelegramMembership.fromChatMember(json.readTree("{\"status\":\"restricted\",\"is_member\":false}")));
        for (String state : new String[]{"left", "kicked"}) {
            assertEquals(TelegramMembership.NOT_SUBSCRIBED,
                    TelegramMembership.fromChatMember(json.readTree("{\"status\":\"" + state + "\"}")));
        }
    }

    @Test
    void detailedChatMembershipDistinguishesAdministratorsOwnersAndMembers() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(TelegramChatMember.Role.OWNER,
                TelegramChatMember.fromTelegram(json.readTree("{\"status\":\"creator\"}")).role());
        assertEquals(TelegramChatMember.Role.ADMINISTRATOR,
                TelegramChatMember.fromTelegram(json.readTree("{\"status\":\"administrator\"}")).role());
        TelegramChatMember member = TelegramChatMember.fromTelegram(
                json.readTree("{\"status\":\"member\",\"tag\":\"Player\"}"));
        assertEquals(TelegramChatMember.Role.MEMBER, member.role());
        assertEquals("Player", member.tag());
    }
}
