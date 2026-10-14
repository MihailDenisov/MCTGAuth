package dev.example.mcauth.backend;

import dev.example.mcauth.backend.telegram.i18n.BotLocale;

import java.time.Duration;

final class LocalizedDurationFormatter {
    private LocalizedDurationFormatter() { }

    static String format(Duration value, BotLocale locale) {
        long minutes = Math.max(0, value.toMinutes());
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long remainder = minutes % 60;
        StringBuilder result = new StringBuilder();
        if (days > 0) result.append(days).append(locale == BotLocale.RUSSIAN ? " д" : " d");
        if (hours > 0) append(result, hours + (locale == BotLocale.RUSSIAN ? " ч" : " h"));
        if (remainder > 0 || result.isEmpty()) append(result, remainder + (locale == BotLocale.RUSSIAN ? " мин" : " min"));
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (!target.isEmpty()) target.append(' ');
        target.append(value);
    }
}
