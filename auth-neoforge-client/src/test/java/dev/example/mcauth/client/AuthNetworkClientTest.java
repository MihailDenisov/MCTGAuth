package dev.example.mcauth.client;

import dev.example.mcauth.protocol.Argon2Spec;
import dev.example.mcauth.protocol.AuthChallengePayload;
import dev.example.mcauth.protocol.AuthFailurePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthResponsePayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthNetworkClientTest {
    @Test
    void derivesOffCallerThreadPreventsDuplicateAndResetsOnDisconnect() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> sent = new ArrayBlockingQueue<>(1);
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> sent.add(payload), executor);
        UUID session = UUID.randomUUID();
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 3);
        client.connected("Test_Player");
        assertTrue(client.activate());
        client.challenge(new AuthChallengePayload(session, 1,
                new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60));

        assertTrue(client.submit("temporary password".toCharArray()));
        assertFalse(client.submit("duplicate".toCharArray()));
        AuthResponsePayload response = sent.poll(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(session, response.sessionId());
        assertEquals(ClientAuthState.WAITING_FOR_RESULT, client.state());

        client.failure(new AuthFailurePayload(session, AuthFailureReason.INVALID_CREDENTIALS, true, 2));
        assertEquals(ClientAuthState.FAILED, client.state());
        client.challenge(new AuthChallengePayload(UUID.randomUUID(), 1,
                new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60));
        assertEquals(ClientAuthState.WAITING_FOR_PASSWORD, client.state());
        client.reset();
        assertEquals(ClientAuthState.DISCONNECTED, client.state());
        client.close();
        assertTrue(executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
    }

    @Test
    void storedPasswordAutomaticallyProducesProofWhileMissingOrRejectedPasswordRequestsUi() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> sent = new ArrayBlockingQueue<>(1);
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> sent.add(payload), executor);
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 7);
        client.connected("Test_Player");
        assertTrue(client.activate());
        AuthChallengePayload first = new AuthChallengePayload(UUID.randomUUID(), 1,
                new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60);
        assertEquals(AuthNetworkClient.ChallengeDisposition.AUTO_SUBMITTED,
                client.handleChallenge(first, "stored-password", true));
        assertNotNull(sent.poll(10, TimeUnit.SECONDS));

        client.failure(new AuthFailurePayload(first.sessionId(), AuthFailureReason.INVALID_CREDENTIALS, true, 2));
        AuthChallengePayload retry = new AuthChallengePayload(UUID.randomUUID(), 1,
                new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60);
        assertEquals(AuthNetworkClient.ChallengeDisposition.INTERACTIVE_REQUIRED,
                client.handleChallenge(retry, "stored-password", false));
        assertEquals(ClientAuthState.WAITING_FOR_PASSWORD, client.state());
        client.reset();
        client.connected("Test_Player");
        assertTrue(client.activate());
        assertEquals(AuthNetworkClient.ChallengeDisposition.INTERACTIVE_REQUIRED,
                client.handleChallenge(new AuthChallengePayload(UUID.randomUUID(), 1,
                        new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60), "", true));
        client.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void terminalFailureInvalidatesQueuedWorkAndCannotLeakIntoALaterConnection() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> sent = new ArrayBlockingQueue<>(2);
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            executorOccupied.countDown();
            try {
                releaseExecutor.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(executorOccupied.await(2, TimeUnit.SECONDS));

        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> sent.add(payload), executor);
        client.connected("Test_Player");
        assertTrue(client.activate());
        long firstGeneration = client.connectionGeneration();
        AuthChallengePayload challenge = challenge();
        client.challenge(challenge);
        assertTrue(client.submit("never-sent-password".toCharArray()));
        assertTrue(client.failure(new AuthFailurePayload(challenge.sessionId(),
                AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED, false, 0)));
        assertEquals(ClientAuthState.TERMINAL_FAILURE, client.state());
        assertEquals(AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED, client.terminalFailure().orElseThrow());
        assertTrue(client.connectionGeneration() > firstGeneration);

        client.disconnected();
        assertEquals(ClientAuthState.TERMINAL_FAILURE, client.state());
        client.connected("Test_Player"); // A later manual Minecraft connection starts a fresh lifecycle.
        assertEquals(ClientAuthState.PASSIVE, client.state());
        assertTrue(client.terminalFailure().isEmpty());
        assertTrue(client.activate());
        AuthChallengePayload freshChallenge = challenge();
        client.challenge(freshChallenge);
        assertTrue(client.submit("fresh-connection-password".toCharArray()));

        releaseExecutor.countDown(); // Old-generation work wakes after the new connection exists.
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        AuthResponsePayload freshResponse = sent.poll();
        assertNotNull(freshResponse);
        assertEquals(freshChallenge.sessionId(), freshResponse.sessionId());
        assertNull(sent.poll(), "old-generation work must not emit a response");
        client.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void everyTerminalReasonStopsTheCurrentLifecycleWithoutAutomaticConnectionWork() {
        AuthFailureReason[] terminalReasons = {
                AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED,
                AuthFailureReason.ACCOUNT_DISABLED,
                AuthFailureReason.PROTOCOL_MISMATCH,
                AuthFailureReason.CLIENT_MISSING,
                AuthFailureReason.TOO_MANY_ATTEMPTS,
                AuthFailureReason.AUTH_TIMEOUT,
                AuthFailureReason.INTERNAL_AUTH_ERROR
        };
        for (AuthFailureReason reason : terminalReasons) {
            var executor = Executors.newSingleThreadExecutor();
            AuthNetworkClient client = new AuthNetworkClient((ignored, generation) -> {
                throw new AssertionError("terminal failure must not send authentication work");
            }, executor);
            client.connected("Test_Player");
            assertTrue(client.activate());
            assertTrue(client.failure(new AuthFailurePayload(null, reason, false, 0)));
            assertEquals(ClientAuthState.TERMINAL_FAILURE, client.state());
            assertEquals(reason, client.terminalFailure().orElseThrow());
            assertFalse(client.failure(new AuthFailurePayload(null, reason, false, 0)),
                    "duplicate terminal result must be ignored");
            client.close();
        }
    }

    @Test
    void invalidCredentialsRetryStaysInTheSameConnectionGeneration() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> sent = new ArrayBlockingQueue<>(1);
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, ignored) -> sent.add(payload), executor);
        client.connected("Test_Player");
        assertTrue(client.activate());
        long generation = client.connectionGeneration();
        AuthChallengePayload first = challenge();
        client.challenge(first);
        assertTrue(client.failure(new AuthFailurePayload(first.sessionId(),
                AuthFailureReason.INVALID_CREDENTIALS, true, 2)));
        assertEquals(ClientAuthState.FAILED, client.state());
        assertEquals(generation, client.connectionGeneration());

        AuthChallengePayload retry = challenge();
        client.challenge(retry);
        assertTrue(client.submit("replacement-password".toCharArray()));
        assertNotNull(sent.poll(10, TimeUnit.SECONDS));
        assertEquals(generation, client.connectionGeneration());
        client.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void mainThreadSendCallbackRechecksGenerationAfterTerminalFailure() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> prepared = new ArrayBlockingQueue<>(1);
        AtomicLong preparedGeneration = new AtomicLong(-1);
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> {
            preparedGeneration.set(generation);
            prepared.add(payload);
        }, executor);
        client.connected("Test_Player");
        assertTrue(client.activate());
        AuthChallengePayload challenge = challenge();
        client.challenge(challenge);
        assertTrue(client.submit("temporary-password".toCharArray()));
        assertNotNull(prepared.poll(10, TimeUnit.SECONDS));
        assertTrue(client.maySendForGeneration(preparedGeneration.get()));

        client.failure(new AuthFailurePayload(challenge.sessionId(),
                AuthFailureReason.ACCOUNT_DISABLED, false, 0));
        assertFalse(client.maySendForGeneration(preparedGeneration.get()));
        client.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void singleplayerConnectionNeverActivatesOrAcceptsAChallenge() {
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> {
            throw new AssertionError("singleplayer must not send authentication payloads");
        }, executor);

        client.connected("Test_Player", false);

        assertEquals(ClientAuthState.PASSIVE, client.state());
        assertFalse(client.mayActivate());
        assertFalse(client.activate());
        assertEquals(AuthNetworkClient.ChallengeDisposition.IGNORED,
                client.handleChallenge(challenge(), "stored-password", true));
        assertFalse(client.failure(new AuthFailurePayload(null, AuthFailureReason.AUTH_TIMEOUT, false, 0)));
        assertEquals(ClientAuthState.PASSIVE, client.state());
        client.close();
    }

    @Test
    void thirdPartyConnectionRemainsPassiveWithoutServerCapability() {
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> {
            throw new AssertionError("a third-party connection must not send authentication payloads");
        }, executor);

        client.connected("Test_Player");

        assertEquals(ClientAuthState.PASSIVE, client.state());
        assertTrue(client.mayActivate());
        assertFalse(client.isActive());
        assertEquals(AuthNetworkClient.ChallengeDisposition.IGNORED,
                client.handleChallenge(challenge(), "stored-password", true));
        assertEquals(ClientAuthState.PASSIVE, client.state());
        client.close();
    }

    @Test
    void capabilityActivationDoesNotLeakAcrossConnectionKinds() {
        var executor = Executors.newSingleThreadExecutor();
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> { }, executor);

        client.connected("Test_Player");
        assertTrue(client.activate());
        assertEquals(ClientAuthState.NEGOTIATING, client.state());
        client.disconnected();

        client.connected("Test_Player", false);
        assertEquals(ClientAuthState.PASSIVE, client.state());
        assertFalse(client.activate());
        client.disconnected();

        client.connected("Test_Player");
        assertEquals(ClientAuthState.PASSIVE, client.state());
        client.disconnected();

        client.connected("Test_Player");
        assertEquals(ClientAuthState.PASSIVE, client.state());
        assertTrue(client.activate());
        assertEquals(ClientAuthState.NEGOTIATING, client.state());
        client.close();
    }

    @Test
    void staleCryptoResultCannotAffectLaterPassiveConnection() throws Exception {
        ArrayBlockingQueue<AuthResponsePayload> sent = new ArrayBlockingQueue<>(1);
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            executorOccupied.countDown();
            try {
                releaseExecutor.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(executorOccupied.await(2, TimeUnit.SECONDS));
        AuthNetworkClient client = new AuthNetworkClient((payload, generation) -> sent.add(payload), executor);
        client.connected("Test_Player");
        assertTrue(client.activate());
        client.challenge(challenge());
        assertTrue(client.submit("old-connection-password".toCharArray()));

        client.disconnected();
        client.connected("Test_Player");
        assertEquals(ClientAuthState.PASSIVE, client.state());
        releaseExecutor.countDown();
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);

        assertNull(sent.poll());
        assertEquals(ClientAuthState.PASSIVE, client.state());
        client.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private static AuthChallengePayload challenge() {
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 9);
        return new AuthChallengePayload(UUID.randomUUID(), 1,
                new Argon2Spec(8192, 1, 1, 32, new byte[16]), nonce, 60);
    }
}
