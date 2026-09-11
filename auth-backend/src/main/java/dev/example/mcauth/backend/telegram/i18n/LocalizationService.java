package dev.example.mcauth.backend.telegram.i18n;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class LocalizationService {
    private static final String DEFAULT_BASE_NAME = "i18n.messages";
    private final ZoneId presentationZone;
    private final String baseName;

    public LocalizationService(ZoneId presentationZone) {
        this(presentationZone, DEFAULT_BASE_NAME);
    }

    public LocalizationService(ZoneId presentationZone, String baseName) {
        this.presentationZone = presentationZone;
        this.baseName = baseName;
    }

    public String text(BotLocale locale, MessageKey key, Object... arguments) {
        String template = lookup(locale, key.key());
        return new MessageFormat(template, locale.javaLocale()).format(arguments);
    }

    public String formatInstant(BotLocale locale, Instant instant) {
        String pattern = locale == BotLocale.RUSSIAN ? "dd.MM.uuuu HH:mm" : "uuuu-MM-dd HH:mm";
        return DateTimeFormatter.ofPattern(pattern, locale.javaLocale())
                .withZone(presentationZone)
                .format(instant);
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
