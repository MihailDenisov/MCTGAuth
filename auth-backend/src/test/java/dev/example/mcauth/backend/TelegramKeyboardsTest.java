package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelegramKeyboardsTest {
    @Test
    void submenusAndWizardsExposeLocalizedNavigation() {
        TelegramKeyboards keyboards = new TelegramKeyboards(
                new ObjectMapper(), new LocalizationService(ZoneOffset.UTC), TelegramFeatureConfig.defaults());

        String userWizard = keyboards.wizard(BotLocale.ENGLISH, false).toString();
        assertTrue(userWizard.contains("⬅️ Back"));
        assertTrue(userWizard.contains("❌ Cancel"));
        assertTrue(userWizard.contains("🏠 Main menu"));

        String adminWizard = keyboards.wizard(BotLocale.RUSSIAN, true).toString();
        assertTrue(adminWizard.contains("⬅️ Назад"));
        assertTrue(adminWizard.contains("❌ Отмена"));
        assertTrue(adminWizard.contains("🏠 Меню администратора"));

        assertTrue(keyboards.accountMenu(BotLocale.ENGLISH).toString().contains("Main menu"));
        assertTrue(keyboards.serverMenu(BotLocale.RUSSIAN).toString().contains("Меню администратора"));
        assertTrue(keyboards.attachmentMenu(BotLocale.ENGLISH).toString().contains("Back"));
        assertTrue(keyboards.yesNo(BotLocale.RUSSIAN).toString().contains("Главное меню"));
        assertTrue(keyboards.languageSelector(BotLocale.RUSSIAN).toString().contains("Главное меню"));
    }

    @Test
    void optionalUserLinksAppearOnlyWhenConfigured() {
        LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
        TelegramKeyboards disabled = new TelegramKeyboards(
                new ObjectMapper(), localization, TelegramFeatureConfig.defaults());
        String disabledMenu = disabled.userMenu(BotLocale.ENGLISH, true, false).toString();
        assertFalse(disabledMenu.contains("Web Map"));
        assertFalse(disabledMenu.contains("Download Modpack"));

        TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                "https://map.example.com/", "https://t.me/example/123");
        TelegramKeyboards enabled = new TelegramKeyboards(new ObjectMapper(), localization, features);
        String enabledMenu = enabled.userMenu(BotLocale.ENGLISH, true, false).toString();
        assertTrue(enabledMenu.contains("Server Information"));
        assertFalse(enabledMenu.contains("Web Map"));
        assertFalse(enabledMenu.contains("Download Modpack"));
        String submenu = enabled.serverInformationMenu(BotLocale.ENGLISH).toString();
        assertTrue(submenu.contains("Web Map"));
        assertTrue(submenu.contains("Download Modpack"));
    }

    @Test
    void serverInformationButtonsUseLocalizedEmojiAndStableCallbacks() {
        LocalizationService localization = new LocalizationService(ZoneOffset.UTC);
        TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                "https://map.example.com/", "https://example.com/modpack.zip");
        TelegramKeyboards keyboards = new TelegramKeyboards(new ObjectMapper(), localization, features);

        var english = keyboards.serverInformationMenu(BotLocale.ENGLISH).path("inline_keyboard");
        assertEquals(Set.of("📖 How to Play", "📦 Download Modpack", "🗺 Web Map",
                "📡 Server Status", "⬅️ Back"), labels(english));
        assertEquals(Set.of("server_info:how_to_play", "server_info:modpack", "server_info:map",
                "server_info:status", "server_info:back"), callbacks(english));

        var russian = keyboards.serverInformationMenu(BotLocale.RUSSIAN).path("inline_keyboard");
        assertEquals(Set.of("📖 Как играть", "📦 Скачать сборку", "🗺 Веб-карта",
                "📡 Статус сервера", "⬅️ Назад"), labels(russian));
        assertEquals(callbacks(english), callbacks(russian));
    }

    @Test
    void disabledOptionalServerInformationButtonsStayHidden() {
        TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                null, null, false, 60, true, true, true);
        TelegramKeyboards keyboards = new TelegramKeyboards(
                new ObjectMapper(), new LocalizationService(ZoneOffset.UTC), features);

        var menu = keyboards.serverInformationMenu(BotLocale.ENGLISH).path("inline_keyboard");
        assertEquals(Set.of("📖 How to Play", "⬅️ Back"), labels(menu));
        assertEquals(Set.of("server_info:how_to_play", "server_info:back"), callbacks(menu));
    }

    private static Set<String> labels(com.fasterxml.jackson.databind.JsonNode keyboard) {
        Set<String> result = new HashSet<>();
        keyboard.forEach(row -> result.add(row.get(0).path("text").asText()));
        return result;
    }

    private static Set<String> callbacks(com.fasterxml.jackson.databind.JsonNode keyboard) {
        Set<String> result = new HashSet<>();
        keyboard.forEach(row -> result.add(row.get(0).path("callback_data").asText()));
        return result;
    }
}
