package dev.example.mcauth.backend;

public record ModeratorIdentity(long telegramId, String usernameSnapshot) {
    public ModeratorIdentity {
        if (telegramId <= 0) {
            throw new IllegalArgumentException("Moderator Telegram ID must be positive");
        }
        usernameSnapshot = usernameSnapshot == null ? "" : usernameSnapshot;
    }
}
