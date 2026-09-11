package dev.example.mcauth.backend;

import dev.example.mcauth.api.AccountCreationMethod;

import java.time.Instant;

public record AccountView(
        long id,
        long ownerTelegramId,
        String nickname,
        boolean licensed,
        boolean enabled,
        AccountCreationMethod creationMethod,
        int credentialVersion,
        Instant createdAt) {
}
