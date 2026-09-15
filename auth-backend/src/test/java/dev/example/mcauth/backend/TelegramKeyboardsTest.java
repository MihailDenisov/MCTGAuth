package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

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
        assertFalse(disabledMenu.contains("Server Map"));
        assertFalse(disabledMenu.contains("Download Modpack"));

        TelegramFeatureConfig features = new TelegramFeatureConfig(null, null, false, null, null,
                "https://map.example.com/", "https://t.me/example/123");
        String enabledMenu = new TelegramKeyboards(new ObjectMapper(), localization, features)
                .userMenu(BotLocale.ENGLISH, true, false).toString();
        assertTrue(enabledMenu.contains("Server Map"));
        assertTrue(enabledMenu.contains("Download Modpack"));
    }
}
