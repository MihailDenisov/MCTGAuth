package dev.example.mcauth.backend;

public record ChatTagPreference(long telegramUserId, Long accountId, String minecraftName) {
    public boolean hasTag() {
        return accountId != null && minecraftName != null;
    }
}
