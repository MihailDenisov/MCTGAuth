package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.protocol.Argon2Spec;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.ChallengeProof;
import dev.example.mcauth.protocol.ClientHelloPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationAndSyncTest {
    @TempDir Path temporary;
    private JsonAuthAccountStore store;
    private SyncEventProcessor processor;
    private String verifier;

    @BeforeEach
    void setUp() {
        store = new JsonAuthAccountStore(temporary.resolve("accounts.json"));
        store.open();
        processor = new SyncEventProcessor(store);
        char[] password = "correct password".toCharArray();
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] key = ChallengeProof.deriveKey(password, new Argon2Spec(8192, 1, 1, 32, salt));
        verifier = "$argon2id$v=19$m=8192,t=1,p=1$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(key);
        Arrays.fill(password, '\0');
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(key, (byte) 0);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void syncIsTransactionalIdempotentAndTombstonesRemoval() {
        SyncEvent created = accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_CREATED, 1, true, true);
        assertEquals(SyncEventProcessor.ApplyResult.APPLIED, processor.apply(created));
        assertEquals(SyncEventProcessor.ApplyResult.ALREADY_PROCESSED, processor.apply(created));
        assertEquals(1, store.accountCount());
        assertTrue(store.findByMinecraftName("Test_Player").orElseThrow().enabled());

        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_DISABLED, 1, false, false));
        assertFalse(store.findByMinecraftName("test_player").orElseThrow().enabled());
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_ENABLED, 1, true, false));
        assertTrue(store.findByMinecraftName("TEST_PLAYER").orElseThrow().enabled());
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_REMOVED, 1, false, false));
        assertFalse(store.findByMinecraftName("test_player").orElseThrow().enabled());
    }

    @Test
    void validLocalProofWorksWithoutBackendAndReplayFails() {
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_CREATED, 1, true, true));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-12T12:00:00Z"));
        LoginSessionManager manager = new LoginSessionManager(store, clock, new SecureRandom(),
                Duration.ofSeconds(60), 3);
        UUID player = UUID.randomUUID();
        manager.connected(player, player, "Test_Player");
        var challengeDecision = manager.hello(player, new ClientHelloPayload(1, "0.3", AuthProtocol.REQUIRED_FEATURES));
        var challenge = challengeDecision.challenge();
        char[] password = "correct password".toCharArray();
        byte[] key = ChallengeProof.deriveKey(password, challenge.argon2());
        byte[] proof = ChallengeProof.create(key, 1, challenge.nonce(), challenge.sessionId(), "test_player", 1);
        AuthResponsePayload response = new AuthResponsePayload(challenge.sessionId(), 1, proof);
        var success = manager.verify(player, response);
        assertTrue(success.authenticated());
        assertEquals(1, success.credentialVersion());
        assertFalse(manager.verify(player, response).authenticated(), "a consumed proof must not authenticate twice");
        assertTrue(manager.isAuthenticated(player));
        Arrays.fill(password, '\0');
        Arrays.fill(key, (byte) 0);
        Arrays.fill(proof, (byte) 0);
        manager.close();
    }

    @Test
    void expiredAndWrongProofFailAndUnknownAccountNeverFallsBack() {
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_CREATED, 1, true, true));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-12T12:00:00Z"));
        LoginSessionManager manager = new LoginSessionManager(store, clock, new SecureRandom(),
                Duration.ofSeconds(30), 3);
        UUID player = UUID.randomUUID();
        manager.connected(player, player, "Test_Player");
        var challenge = manager.hello(player, new ClientHelloPayload(1, "0.3", AuthProtocol.REQUIRED_FEATURES)).challenge();
        byte[] wrong = new byte[32];
        assertTrue(manager.verify(player, new AuthResponsePayload(challenge.sessionId(), 1, wrong)).retryAllowed());
        var renewed = manager.session(player).orElseThrow();
        clock.advance(Duration.ofSeconds(31));
        assertEquals(dev.example.mcauth.protocol.AuthFailureReason.AUTH_TIMEOUT,
                manager.verify(player, new AuthResponsePayload(renewed.sessionId(), 1, wrong)).failure());

        UUID unknown = UUID.randomUUID();
        manager.connected(unknown, unknown, "NoAccount");
        assertEquals(dev.example.mcauth.protocol.AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED,
                manager.hello(unknown, new ClientHelloPayload(1, "0.3", AuthProtocol.REQUIRED_FEATURES)).failure());
        manager.close();
    }

    @Test
    void credentialRotationInvalidatesOutstandingChallengeAndIssuesCurrentVersion() {
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.ACCOUNT_CREATED, 1, true, true));
        LoginSessionManager manager = new LoginSessionManager(store, Clock.systemUTC(), new SecureRandom(),
                Duration.ofSeconds(60), 3);
        UUID player = UUID.randomUUID();
        manager.connected(player, player, "Test_Player");
        var oldChallenge = manager.hello(player,
                new ClientHelloPayload(1, "0.3", AuthProtocol.REQUIRED_FEATURES)).challenge();
        char[] password = "correct password".toCharArray();
        byte[] key = ChallengeProof.deriveKey(password, oldChallenge.argon2());
        byte[] proof = ChallengeProof.create(key, 1, oldChallenge.nonce(), oldChallenge.sessionId(), "test_player", 1);
        processor.apply(accountEvent(UUID.randomUUID(), SyncEventType.CREDENTIAL_CHANGED, 2, true, false));

        var decision = manager.verify(player, new AuthResponsePayload(oldChallenge.sessionId(), 1, proof));
        assertFalse(decision.authenticated());
        assertTrue(decision.retryAllowed());
        assertEquals(2, decision.challenge().credentialVersion());
        Arrays.fill(password, '\0');
        Arrays.fill(key, (byte) 0);
        Arrays.fill(proof, (byte) 0);
        manager.close();
    }

    private SyncEvent accountEvent(UUID id, SyncEventType type, int version, boolean enabled, boolean full) {
        String payload = switch (type) {
            case ACCOUNT_CREATED -> """
                    {"accountId":1,"nickname":"Test_Player","normalizedNickname":"test_player",
                    "licensed":false,"enabled":%s,"credentialVersion":%d,"credentialVerifier":"%s"}
                    """.formatted(enabled, version, verifier);
            case CREDENTIAL_CHANGED -> """
                    {"accountId":1,"nickname":"Test_Player","enabled":%s,
                    "credentialVersion":%d,"credentialVerifier":"%s"}
                    """.formatted(enabled, version, verifier);
            default -> "{\"accountId\":1}";
        };
        return new SyncEvent(id, type, 1, payload, Instant.parse("2026-09-12T12:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
