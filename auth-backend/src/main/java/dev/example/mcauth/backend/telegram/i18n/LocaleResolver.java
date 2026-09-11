package dev.example.mcauth.backend.telegram.i18n;

import dev.example.mcauth.backend.BackendDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

public final class LocaleResolver {
    private final BackendDatabase database;
    private final BotLocale adminLocale;
    private final BotLocale defaultUserLocale;

    public LocaleResolver(BackendDatabase database, BotLocale adminLocale, BotLocale defaultUserLocale) {
        this.database = Objects.requireNonNull(database, "database");
        this.adminLocale = Objects.requireNonNull(adminLocale, "adminLocale");
        this.defaultUserLocale = Objects.requireNonNull(defaultUserLocale, "defaultUserLocale");
    }

    public BotLocale userLocale(long telegramId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT locale FROM users WHERE telegram_id = ?")) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? BotLocale.storedOrEnglish(result.getString(1)) : defaultUserLocale;
                }
            }
        });
    }

    public void setUserLocale(long telegramId, BotLocale locale) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET locale = ? WHERE telegram_id = ?")) {
                statement.setString(1, locale.code());
                statement.setLong(2, telegramId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Cannot store locale for unknown Telegram user");
                }
            }
            return null;
        });
    }

    public BotLocale adminLocale() { return adminLocale; }

    public BotLocale defaultUserLocale() { return defaultUserLocale; }
}
