package dev.example.mcauth.backend.telegram.i18n;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class LocalizationService {
    private static final String DEFAULT_BASE_NAME = "i18n.messages";
    private final String baseName;
    private final TelegramDateTimeFormatter timeFormatter;

    public LocalizationService() {
        this(DEFAULT_BASE_NAME);
    }

    public LocalizationService(String baseName) {
        this.baseName = baseName;
        this.timeFormatter = new TelegramDateTimeFormatter();
    }

    /**
     * Compatibility constructor. Telegram presentation always uses Europe/Moscow;
     * the supplied zone is intentionally ignored.
     */
    public LocalizationService(ZoneId ignoredPresentationZone) {
        this(DEFAULT_BASE_NAME);
    }

    /** Compatibility overload for custom resource bundles used by tests. */
    public LocalizationService(ZoneId ignoredPresentationZone, String baseName) {
        this(baseName);
    }

    public String text(BotLocale locale, MessageKey key, Object... arguments) {
        String template = lookup(locale, key.key());
        return new MessageFormat(template, locale.javaLocale()).format(arguments);
    }

    public String formatInstant(BotLocale locale, Instant instant) {
        return timeFormatter.formatDateTime(instant, locale);
    }

    private String lookup(BotLocale locale, String key) {
        try {
            return bundle(locale).getString(key);
        } catch (MissingResourceException missing) {
            if (locale != BotLocale.ENGLISH) {
                System.err.println("Missing translation key '" + key + "' for locale '"
                        + locale.code() + "'; using 'en'");
                try {
                    return bundle(BotLocale.ENGLISH).getString(key);
                } catch (MissingResourceException ignored) {
                    // Report the non-sensitive key below.
                }
            }
            System.err.println("Missing localization key '" + key + "' in English fallback");
            return "[message unavailable]";
        }
    }

    private ResourceBundle bundle(BotLocale locale) {
        return ResourceBundle.getBundle(baseName, locale.javaLocale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
