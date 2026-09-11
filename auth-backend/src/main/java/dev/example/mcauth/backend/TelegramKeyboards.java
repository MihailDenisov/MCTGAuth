package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.mcauth.backend.telegram.i18n.BotAction;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import dev.example.mcauth.backend.telegram.i18n.MenuActionRouter;
import dev.example.mcauth.backend.telegram.i18n.MessageKey;

final class TelegramKeyboards {
    private final ObjectMapper json;
    private final LocalizationService localization;

    TelegramKeyboards(ObjectMapper json, LocalizationService localization) {
        this.json = json;
        this.localization = localization;
    }

    ObjectNode userMenu(BotLocale locale, boolean registered, boolean moderator) {
        ObjectNode keyboard = replyKeyboard();
        ArrayNode rows = (ArrayNode) keyboard.get("keyboard");
        if (!registered) {
            rows.add(actions(locale, BotAction.REGISTER));
            rows.add(actions(locale, BotAction.REGISTRATION_STATUS, BotAction.ABOUT));
            rows.add(actions(locale, BotAction.SETTINGS));
        } else {
            rows.add(actions(locale, BotAction.HOW_TO_PLAY));
            rows.add(actions(locale, BotAction.REPORT_PROBLEM, BotAction.REPORT_VIOLATION));
            rows.add(actions(locale, BotAction.MY_TICKETS, BotAction.MY_ACCOUNT));
            rows.add(actions(locale, BotAction.SETTINGS));
        }
        if (moderator) rows.add(actions(locale, BotAction.ADMINISTRATION));
        return keyboard;
    }

    ObjectNode adminMenu(BotLocale locale) {
        ObjectNode keyboard = replyKeyboard();
        ArrayNode rows = (ArrayNode) keyboard.get("keyboard");
        rows.add(actions(locale, BotAction.NEW_TICKETS, BotAction.IN_PROGRESS));
        rows.add(actions(locale, BotAction.REGISTRATIONS, BotAction.PROBLEMS));
        rows.add(actions(locale, BotAction.VIOLATIONS, BotAction.SEARCH));
        rows.add(actions(locale, BotAction.STATISTICS, BotAction.MANUAL_ACCOUNT));
        rows.add(actions(locale, BotAction.SERVER_MANAGEMENT));
        return keyboard;
    }

    ObjectNode accountMenu(BotLocale locale) {
        ObjectNode keyboard = replyKeyboard();
        ((ArrayNode) keyboard.get("keyboard")).add(
                actions(locale, BotAction.CHANGE_PASSWORD, BotAction.ADD_ACCOUNT));
        return keyboard;
    }

    ObjectNode attachmentMenu(BotLocale locale) {
        ObjectNode keyboard = replyKeyboard();
        ArrayNode rows = (ArrayNode) keyboard.get("keyboard");
        rows.add(actions(locale, BotAction.ADD_ATTACHMENT));
        rows.add(actions(locale, BotAction.SUBMIT, BotAction.CANCEL));
        return keyboard;
    }

    ObjectNode yesNo(BotLocale locale) {
        ObjectNode keyboard = replyKeyboard();
        ((ArrayNode) keyboard.get("keyboard")).add(actions(locale, BotAction.YES, BotAction.NO));
        return keyboard;
    }

    ObjectNode serverMenu(BotLocale locale) {
        ObjectNode keyboard = replyKeyboard();
        ArrayNode rows = (ArrayNode) keyboard.get("keyboard");
        rows.add(actions(locale, BotAction.FORCE_SYNC, BotAction.AUTH_STATUS));
        rows.add(actions(locale, BotAction.RESYNC_PLAYER));
        return keyboard;
    }

    ObjectNode languageSelector() {
        return inline(new String[][]{
                {localization.text(BotLocale.ENGLISH, MessageKey.LANGUAGE_ENGLISH), "locale:set:en"},
                {localization.text(BotLocale.RUSSIAN, MessageKey.LANGUAGE_RUSSIAN), "locale:set:ru"}
        });
    }

    ObjectNode inline(String[][] buttons) {
        ObjectNode markup = json.createObjectNode();
        ArrayNode rows = markup.putArray("inline_keyboard");
        for (String[] pair : buttons) {
            ObjectNode button = json.createObjectNode();
            button.put("text", pair[0]);
            if (pair[1].startsWith("https://")) button.put("url", pair[1]);
            else button.put("callback_data", pair[1]);
            rows.add(json.createArrayNode().add(button));
        }
        return markup;
    }

    private ObjectNode replyKeyboard() {
        ObjectNode keyboard = json.createObjectNode();
        keyboard.put("resize_keyboard", true);
        keyboard.put("is_persistent", true);
        keyboard.putArray("keyboard");
        return keyboard;
    }

    private ArrayNode actions(BotLocale locale, BotAction... actions) {
        ArrayNode row = json.createArrayNode();
        for (BotAction action : actions) {
            String label = localization.text(locale, MenuActionRouter.labelKey(action));
            row.add(json.createObjectNode().put("text", label));
        }
        return row;
    }
}
