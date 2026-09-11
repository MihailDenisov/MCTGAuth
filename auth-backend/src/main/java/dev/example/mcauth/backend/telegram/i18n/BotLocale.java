package dev.example.mcauth.backend.telegram.i18n;

import java.util.Locale;

public enum BotLocale {
    ENGLISH("en", Locale.ENGLISH),
    RUSSIAN("ru", Locale.forLanguageTag("ru"));

    private final String code;
    private final Locale javaLocale;

    BotLocale(String code, Locale javaLocale) {
        this.code = code;
        this.javaLocale = javaLocale;
    }

    public String code() {
        return code;
    }

    public Locale javaLocale() {
        return javaLocale;
    }

    public static BotLocale configured(String value, String variableName) {
        return fromCode(value).orElseThrow(() -> new IllegalArgumentException(
                variableName + " must be one of: en, ru"));
    }

    public static BotLocale storedOrEnglish(String value) {
        return fromCode(value).orElse(ENGLISH);
    }

    public static BotLocale suggested(String languageCode, BotLocale configuredDefault) {
        if (languageCode == null || languageCode.isBlank()) return configuredDefault;
        String primary = languageCode.strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return fromCode(primary).orElse(configuredDefault);
    }

    private static java.util.Optional<BotLocale> fromCode(String value) {
        if (value == null) return java.util.Optional.empty();
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (BotLocale locale : values()) {
            if (locale.code.equals(normalized)) return java.util.Optional.of(locale);
        }
        return java.util.Optional.empty();
    }
}
