package dev.example.mcauth.server;

import dev.example.mcauth.protocol.AuthProtocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthAccount(long accountId, String minecraftName, String normalizedName, UUID minecraftUuid,
                          String credentialVerifier, int credentialVersion, boolean enabled,
                          boolean banned, boolean licensed, Instant updatedAt) {
    public AuthAccount {
        if (accountId <= 0 || credentialVersion <= 0) throw new IllegalArgumentException("Invalid account version");
        Objects.requireNonNull(minecraftName, "minecraftName");
        Objects.requireNonNull(normalizedName, "normalizedName");
        Objects.requireNonNull(credentialVerifier, "credentialVerifier");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!AuthProtocol.normalizeMinecraftName(minecraftName).equals(normalizedName)) {
            throw new IllegalArgumentException("Account name normalization mismatch");
        }
    }

    public AuthAccount(long accountId, String minecraftName, String normalizedName, UUID minecraftUuid,
                       String credentialVerifier, int credentialVersion, boolean enabled,
                       boolean licensed, Instant updatedAt) {
        this(accountId, minecraftName, normalizedName, minecraftUuid, credentialVerifier,
                credentialVersion, enabled, false, licensed, updatedAt);
    }

    @Override
    public String toString() {
        return "AuthAccount[accountId=" + accountId + ", minecraftName=" + minecraftName
                + ", credentialVersion=" + credentialVersion + ", enabled=" + enabled
                + ", banned=" + banned
                + ", licensed=" + licensed + ", updatedAt=" + updatedAt + "]";
    }
}
