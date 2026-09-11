package dev.example.mcauth.backend;

public record RegistrationSubmission(
        long telegramId,
        String usernameSnapshot,
        String minecraftNickname,
        boolean licensed,
        int age,
        String experience) {
}
