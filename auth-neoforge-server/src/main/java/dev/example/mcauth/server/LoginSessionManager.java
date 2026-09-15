package dev.example.mcauth.server;

import dev.example.mcauth.protocol.Argon2Spec;
import dev.example.mcauth.protocol.Argon2Verifier;
import dev.example.mcauth.protocol.AuthChallengePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.ChallengeProof;
import dev.example.mcauth.protocol.ClientHelloPayload;
import dev.example.mcauth.protocol.PlayerAuthState;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class LoginSessionManager implements AutoCloseable {
    private final AuthAccountStore store;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration timeout;
    private final int maxAttempts;
    private final ConcurrentHashMap<UUID, PlayerAuthSession> sessions = new ConcurrentHashMap<>();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public LoginSessionManager(AuthAccountStore store, Clock clock, SecureRandom random,
                               Duration timeout, int maxAttempts) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Invalid authentication limits");
        }
        this.maxAttempts = maxAttempts;
    }

    public PlayerAuthSession connected(UUID connectionId, UUID minecraftUuid, String minecraftName) {
        String normalized = AuthProtocol.normalizeMinecraftName(minecraftName);
        PlayerAuthSession created = new PlayerAuthSession(connectionId, minecraftUuid, minecraftName,
                normalized, Instant.now(clock));
        PlayerAuthSession previous = sessions.put(connectionId, created);
        if (previous != null) previous.clear();
        return created;
    }

    public Decision hello(UUID connectionId, ClientHelloPayload hello) {
        PlayerAuthSession session = sessions.get(connectionId);
        if (session == null || session.state() != PlayerAuthState.WAITING_FOR_CLIENT) {
            return Decision.failure(null, AuthFailureReason.INTERNAL_AUTH_ERROR, false, 0);
        }
        if (hello.protocolVersion() != AuthProtocol.AUTH_PROTOCOL_VERSION
                || (hello.featureBits() & AuthProtocol.REQUIRED_FEATURES) != AuthProtocol.REQUIRED_FEATURES) {
            session.clear();
            return Decision.failure(null, AuthFailureReason.PROTOCOL_MISMATCH, false, 0);
        }
        return newChallenge(session);
    }

    public Decision verify(UUID connectionId, AuthResponsePayload response) {
        PlayerAuthSession session = sessions.get(connectionId);
        if (session == null) return Decision.failure(null, AuthFailureReason.INTERNAL_AUTH_ERROR, false, 0);
        synchronized (session) {
            if (session.state() != PlayerAuthState.CHALLENGE_SENT) {
                return Decision.failure(response.sessionId(), AuthFailureReason.INTERNAL_AUTH_ERROR, false, 0);
            }
            Instant expiresAt = session.expiresAt();
            UUID expectedSessionId = session.sessionId();
            int challengedVersion = session.credentialVersion();
            byte[] nonce = session.nonce();
            session.consume();
            if (expiresAt == null || !Instant.now(clock).isBefore(expiresAt)) {
                session.timeout();
                failures.increment();
                return Decision.failure(expectedSessionId, AuthFailureReason.AUTH_TIMEOUT, false, 0);
            }
            CredentialCheck check = store.withAccountByMinecraftName(session.normalizedIdentity(), account -> {
                if (!account.enabled()) return CredentialCheck.DISABLED;
                if (!Objects.equals(expectedSessionId, response.sessionId())
                        || challengedVersion != response.credentialVersion()
                        || account.credentialVersion() != challengedVersion) return CredentialCheck.INVALID;
                try (Argon2Verifier verifier = Argon2Verifier.parse(account.credentialVerifier())) {
                    byte[] key = verifier.derivedKey();
                    try {
                        boolean valid = ChallengeProof.verify(key, response.proof(), AuthProtocol.AUTH_PROTOCOL_VERSION,
                                nonce, expectedSessionId, session.normalizedIdentity(), challengedVersion);
                        if (valid) session.authenticate();
                        return valid ? CredentialCheck.VALID : CredentialCheck.INVALID;
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                } catch (IllegalArgumentException exception) {
                    return CredentialCheck.CORRUPT;
                }
            }).orElse(CredentialCheck.INVALID);
            if (check == CredentialCheck.DISABLED) {
                if (nonce != null) Arrays.fill(nonce, (byte) 0);
                session.fail();
                failures.increment();
                return Decision.failure(expectedSessionId, AuthFailureReason.ACCOUNT_DISABLED, false, 0);
            }
            if (check == CredentialCheck.CORRUPT) {
                if (nonce != null) Arrays.fill(nonce, (byte) 0);
                session.fail();
                failures.increment();
                return Decision.failure(expectedSessionId, AuthFailureReason.INTERNAL_AUTH_ERROR, false, 0);
            }
            if (nonce != null) Arrays.fill(nonce, (byte) 0);
            if (check != CredentialCheck.VALID) return failedAttempt(session, expectedSessionId, AuthFailureReason.INVALID_CREDENTIALS);
            successes.increment();
            return Decision.success(expectedSessionId, challengedVersion);
        }
    }

    private Decision failedAttempt(PlayerAuthSession session, UUID sessionId, AuthFailureReason reason) {
        session.fail();
        failures.increment();
        int remaining = maxAttempts - session.failedAttempts();
        if (remaining <= 0) {
            return Decision.failure(sessionId, AuthFailureReason.TOO_MANY_ATTEMPTS, false, 0);
        }
        Decision next = newChallenge(session);
        if (next.challenge() == null) return next;
        return new Decision(false, sessionId, reason, true, remaining, next.challenge(),
                next.challenge().credentialVersion());
    }

    private Decision newChallenge(PlayerAuthSession session) {
        Optional<AuthAccount> account = store.findByMinecraftName(session.normalizedIdentity());
        if (account.isEmpty()) {
            session.fail();
            failures.increment();
            return Decision.failure(null, AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED, false, 0);
        }
        if (!account.get().enabled()) {
            session.fail();
            failures.increment();
            return Decision.failure(null, AuthFailureReason.ACCOUNT_DISABLED, false, 0);
        }
        try (Argon2Verifier verifier = Argon2Verifier.parse(account.get().credentialVerifier())) {
            byte[] nonce = new byte[AuthProtocol.NONCE_BYTES];
            random.nextBytes(nonce);
            UUID sessionId = UUID.randomUUID();
            Instant expiresAt = Instant.now(clock).plus(timeout);
            session.challenge(sessionId, nonce, expiresAt, account.get().credentialVersion());
            Argon2Spec spec = verifier.spec();
            AuthChallengePayload payload = new AuthChallengePayload(sessionId,
                    account.get().credentialVersion(), spec, nonce, Math.toIntExact(timeout.toSeconds()));
            Arrays.fill(nonce, (byte) 0);
            return Decision.challenge(payload);
        } catch (IllegalArgumentException exception) {
            session.fail();
            failures.increment();
            return Decision.failure(null, AuthFailureReason.INTERNAL_AUTH_ERROR, false, 0);
        }
    }

    public boolean isAuthenticated(UUID connectionId) {
        PlayerAuthSession session = sessions.get(connectionId);
        return session != null && session.state() == PlayerAuthState.AUTHENTICATED;
    }

    public Optional<PlayerAuthSession> session(UUID connectionId) {
        return Optional.ofNullable(sessions.get(connectionId));
    }

    public void disconnected(UUID connectionId) {
        PlayerAuthSession removed = sessions.remove(connectionId);
        if (removed != null) removed.clear();
    }

    public int activeUnauthenticated() {
        return (int) sessions.values().stream().filter(s -> s.state() != PlayerAuthState.AUTHENTICATED).count();
    }

    public int authenticatedCount() {
        return (int) sessions.values().stream().filter(s -> s.state() == PlayerAuthState.AUTHENTICATED).count();
    }

    public long successCount() { return successes.sum(); }
    public long failureCount() { return failures.sum(); }

    @Override
    public void close() {
        sessions.values().forEach(PlayerAuthSession::clear);
        sessions.clear();
    }

    public record Decision(boolean authenticated, UUID sessionId, AuthFailureReason failure,
                           boolean retryAllowed, int remainingAttempts, AuthChallengePayload challenge,
                           int credentialVersion) {
        static Decision success(UUID sessionId, int credentialVersion) {
            return new Decision(true, sessionId, null, false, 0, null, credentialVersion);
        }

        static Decision challenge(AuthChallengePayload challenge) {
            return new Decision(false, challenge.sessionId(), null, false, 0, challenge,
                    challenge.credentialVersion());
        }

        static Decision failure(UUID sessionId, AuthFailureReason failure, boolean retry, int remaining) {
            return new Decision(false, sessionId, failure, retry, remaining, null, 0);
        }
    }

    private enum CredentialCheck { VALID, INVALID, DISABLED, CORRUPT }
}
