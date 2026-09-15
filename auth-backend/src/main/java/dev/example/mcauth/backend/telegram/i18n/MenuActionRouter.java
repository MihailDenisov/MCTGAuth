package dev.example.mcauth.backend.telegram.i18n;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class MenuActionRouter {
    private static final Map<BotAction, MessageKey> LABEL_KEYS = new EnumMap<>(BotAction.class);

    static {
        LABEL_KEYS.put(BotAction.REGISTER, MessageKey.MENU_APPLY);
        LABEL_KEYS.put(BotAction.REGISTRATION_STATUS, MessageKey.MENU_REGISTRATION_STATUS);
        LABEL_KEYS.put(BotAction.ABOUT, MessageKey.MENU_ABOUT);
        LABEL_KEYS.put(BotAction.HOW_TO_PLAY, MessageKey.MENU_HOW_TO_PLAY);
        LABEL_KEYS.put(BotAction.SERVER_MAP, MessageKey.MENU_SERVER_MAP);
        LABEL_KEYS.put(BotAction.MODPACK_DOWNLOAD, MessageKey.MENU_MODPACK_DOWNLOAD);
        LABEL_KEYS.put(BotAction.REPORT_PROBLEM, MessageKey.MENU_REPORT_PROBLEM);
        LABEL_KEYS.put(BotAction.REPORT_VIOLATION, MessageKey.MENU_REPORT_VIOLATION);
        LABEL_KEYS.put(BotAction.MY_TICKETS, MessageKey.MENU_MY_TICKETS);
        LABEL_KEYS.put(BotAction.MY_ACCOUNT, MessageKey.MENU_MY_ACCOUNT);
        LABEL_KEYS.put(BotAction.SETTINGS, MessageKey.MENU_SETTINGS);
        LABEL_KEYS.put(BotAction.ADMINISTRATION, MessageKey.MENU_ADMINISTRATION);
        LABEL_KEYS.put(BotAction.ADD_ACCOUNT, MessageKey.MENU_ADD_ACCOUNT);
        LABEL_KEYS.put(BotAction.CHANGE_PASSWORD, MessageKey.MENU_CHANGE_PASSWORD);
        LABEL_KEYS.put(BotAction.NEW_TICKETS, MessageKey.MENU_NEW_TICKETS);
        LABEL_KEYS.put(BotAction.IN_PROGRESS, MessageKey.MENU_IN_PROGRESS);
        LABEL_KEYS.put(BotAction.MY_ASSIGNED, MessageKey.MENU_MY_ASSIGNED);
        LABEL_KEYS.put(BotAction.REGISTRATIONS, MessageKey.MENU_REGISTRATIONS);
        LABEL_KEYS.put(BotAction.PROBLEMS, MessageKey.MENU_PROBLEMS);
        LABEL_KEYS.put(BotAction.VIOLATIONS, MessageKey.MENU_VIOLATIONS);
        LABEL_KEYS.put(BotAction.SEARCH, MessageKey.MENU_SEARCH);
        LABEL_KEYS.put(BotAction.STATISTICS, MessageKey.MENU_STATISTICS);
        LABEL_KEYS.put(BotAction.MANUAL_ACCOUNT, MessageKey.MENU_MANUAL_ACCOUNT);
        LABEL_KEYS.put(BotAction.SERVER_MANAGEMENT, MessageKey.MENU_SERVER_MANAGEMENT);
        LABEL_KEYS.put(BotAction.FORCE_SYNC, MessageKey.MENU_FORCE_SYNC);
        LABEL_KEYS.put(BotAction.AUTH_STATUS, MessageKey.MENU_AUTH_STATUS);
        LABEL_KEYS.put(BotAction.RESYNC_PLAYER, MessageKey.MENU_RESYNC_PLAYER);
        LABEL_KEYS.put(BotAction.ADD_ATTACHMENT, MessageKey.BUTTON_ADD_ATTACHMENT);
        LABEL_KEYS.put(BotAction.SUBMIT, MessageKey.BUTTON_SUBMIT);
        LABEL_KEYS.put(BotAction.BACK, MessageKey.BUTTON_BACK);
        LABEL_KEYS.put(BotAction.CANCEL, MessageKey.BUTTON_CANCEL);
        LABEL_KEYS.put(BotAction.MAIN_MENU, MessageKey.BUTTON_MAIN_MENU);
        LABEL_KEYS.put(BotAction.ADMIN_MENU, MessageKey.BUTTON_ADMIN_MENU);
        LABEL_KEYS.put(BotAction.YES, MessageKey.BUTTON_YES);
        LABEL_KEYS.put(BotAction.NO, MessageKey.BUTTON_NO);
        LABEL_KEYS.put(BotAction.UNKNOWN, MessageKey.BUTTON_UNKNOWN);
    }

    private final Map<String, BotAction> byLabel;

    public MenuActionRouter(LocalizationService localization) {
        Map<String, BotAction> labels = new HashMap<>();
        for (BotLocale locale : BotLocale.values()) {
            LABEL_KEYS.forEach((action, key) -> labels.put(localization.text(locale, key), action));
        }
        byLabel = Map.copyOf(labels);
    }

    public Optional<BotAction> resolve(String visibleLabel) {
        return Optional.ofNullable(byLabel.get(visibleLabel));
    }

    public static MessageKey labelKey(BotAction action) {
        MessageKey key = LABEL_KEYS.get(action);
        if (key == null) throw new IllegalArgumentException("Action has no reply-keyboard label: " + action);
        return key;
    }
}
