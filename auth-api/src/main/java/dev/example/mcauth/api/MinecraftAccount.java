package dev.example.mcauth.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MinecraftAccount(
        long id,
        long ownerTelegramId,
        UUID minecraftUuid,
        String nickname,
        String normalizedNickname,
        boolean licensed,
        boolean enabled,
        int credentialVersion,
        Instant createdAt) {

    public MinecraftAccount {
        if (id <= 0 || ownerTelegramId <= 0 || credentialVersion <= 0) {
            throw new IllegalArgumentException("IDs and credential version must be positive");
        }
        Objects.requireNonNull(nickname, "nickname");
        Objects.requireNonNull(normalizedNickname, "normalizedNickname");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public Optional<UUID> uuid() {
        return Optional.ofNullable(minecraftUuid);
    }
}
