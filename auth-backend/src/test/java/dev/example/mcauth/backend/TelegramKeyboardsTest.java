package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramKeyboardsTest {
    @Test
    void submenusAndWizardsExposeLocalizedNavigation() {
        TelegramKeyboards keyboards = new TelegramKeyboards(
                new ObjectMapper(), new LocalizationService(ZoneOffset.UTC));

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
}
