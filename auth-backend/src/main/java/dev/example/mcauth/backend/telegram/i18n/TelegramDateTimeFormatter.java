package dev.example.mcauth.backend.telegram.i18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class TelegramDateTimeFormatter {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withZone(DISPLAY_ZONE);

    public String formatDateTime(Instant instant, BotLocale locale) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(locale, "locale");
        return DATE_TIME.format(instant) + (locale == BotLocale.RUSSIAN ? " МСК" : " MSK");
    }
}
