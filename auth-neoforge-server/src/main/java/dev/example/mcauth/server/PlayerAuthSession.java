package dev.example.mcauth.server;

import dev.example.mcauth.protocol.PlayerAuthState;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public final class PlayerAuthSession {
    private final UUID connectionId;
    private final UUID minecraftUuid;
    private final String minecraftName;
    private final String normalizedIdentity;
    private final Instant createdAt;
    private PlayerAuthState state = PlayerAuthState.WAITING_FOR_CLIENT;
    private UUID sessionId;
    private byte[] nonce;
    private Instant expiresAt;
    private int failedAttempts;
    private int credentialVersion;

    PlayerAuthSession(UUID connectionId, UUID minecraftUuid, String minecraftName,
                      String normalizedIdentity, Instant createdAt) {
        this.connectionId = connectionId;
        this.minecraftUuid = minecraftUuid;
        this.minecraftName = minecraftName;
        this.normalizedIdentity = normalizedIdentity;
        this.createdAt = createdAt;
    }

    public synchronized void challenge(UUID sessionId, byte[] nonce, Instant expiresAt, int credentialVersion) {
        clearChallenge();
        this.sessionId = sessionId;
        this.nonce = nonce.clone();
        this.expiresAt = expiresAt;
        this.credentialVersion = credentialVersion;
        this.state = PlayerAuthState.CHALLENGE_SENT;
    }

    public synchronized void consume() {
        clearChallenge();
        state = PlayerAuthState.VERIFYING;
    }

    public synchronized void fail() {
        failedAttempts++;
        state = PlayerAuthState.FAILED;
    }

    public synchronized void authenticate() {
        clearChallenge();
        state = PlayerAuthState.AUTHENTICATED;
    }

    public synchronized void timeout() {
        clearChallenge();
        state = PlayerAuthState.TIMED_OUT;
    }

    public synchronized void clear() {
        clearChallenge();
        state = PlayerAuthState.FAILED;
    }

    private void clearChallenge() {
        if (nonce != null) Arrays.fill(nonce, (byte) 0);
        nonce = null;
        sessionId = null;
        expiresAt = null;
        credentialVersion = 0;
    }

    public UUID connectionId() { return connectionId; }
    public UUID minecraftUuid() { return minecraftUuid; }
    public String minecraftName() { return minecraftName; }
    public String normalizedIdentity() { return normalizedIdentity; }
    public Instant createdAt() { return createdAt; }
    public synchronized PlayerAuthState state() { return state; }
    public synchronized UUID sessionId() { return sessionId; }
    public synchronized byte[] nonce() { return nonce == null ? null : nonce.clone(); }
    public synchronized Instant expiresAt() { return expiresAt; }
    public synchronized int failedAttempts() { return failedAttempts; }
    public synchronized int credentialVersion() { return credentialVersion; }
}
