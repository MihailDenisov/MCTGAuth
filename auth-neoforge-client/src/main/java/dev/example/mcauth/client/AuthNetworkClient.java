package dev.example.mcauth.client;

import dev.example.mcauth.protocol.AuthChallengePayload;
import dev.example.mcauth.protocol.AuthFailurePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.ChallengeProof;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AuthNetworkClient implements AutoCloseable {
    private final ExecutorService derivationExecutor;
    private final BiConsumer<AuthResponsePayload, Long> sender;
    private final CredentialStore credentials = new CredentialStore();
    private final AtomicReference<ClientAuthState> state = new AtomicReference<>(ClientAuthState.DISCONNECTED);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile AuthChallengePayload challenge;
    private volatile String normalizedIdentity;
    private volatile AuthFailureReason terminalFailure;
    private volatile Consumer<ClientAuthState> stateListener = ignored -> { };
    private volatile Consumer<AuthFailurePayload> failureListener = ignored -> { };

    public AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender) {
        this(sender, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-argon2");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender, ExecutorService derivationExecutor) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.derivationExecutor = Objects.requireNonNull(derivationExecutor, "derivationExecutor");
    }

    public void connected(String minecraftName) {
        connectionGeneration.incrementAndGet();
        clearTransient();
        terminalFailure = null;
        normalizedIdentity = AuthProtocol.normalizeMinecraftName(minecraftName);
        transition(ClientAuthState.NEGOTIATING);
    }

    public void challenge(AuthChallengePayload value) {
        if (state.get() != ClientAuthState.NEGOTIATING && state.get() != ClientAuthState.FAILED
                && state.get() != ClientAuthState.WAITING_FOR_RESULT) return;
        challenge = value;
        transition(ClientAuthState.WAITING_FOR_PASSWORD);
    }

    public ChallengeDisposition handleChallenge(AuthChallengePayload value, String storedPassword,
                                                 boolean autoAuthenticate) {
        Objects.requireNonNull(storedPassword, "storedPassword");
        challenge(value);
        if (autoAuthenticate && !storedPassword.isEmpty() && submit(storedPassword.toCharArray())) {
            return ChallengeDisposition.AUTO_SUBMITTED;
        }
        return ChallengeDisposition.INTERACTIVE_REQUIRED;
    }

    public boolean submit(char[] password) {
        Objects.requireNonNull(password, "password");
        if (!state.compareAndSet(ClientAuthState.WAITING_FOR_PASSWORD, ClientAuthState.COMPUTING)) {
            Arrays.fill(password, '\0');
            return false;
        }
        notifyState();
        AuthChallengePayload current = challenge;
        if (current == null || normalizedIdentity == null) {
            Arrays.fill(password, '\0');
            transition(ClientAuthState.FAILED);
            return false;
        }
        long capturedGeneration = connectionGeneration.get();
        String capturedIdentity = normalizedIdentity;
        credentials.replace(capturedGeneration, password);
        Arrays.fill(password, '\0');
        derivationExecutor.execute(() -> deriveAndSend(current, capturedIdentity, capturedGeneration));
        return true;
    }

    private void deriveAndSend(AuthChallengePayload current, String identity, long capturedGeneration) {
        char[] password = credentials.take(capturedGeneration);
        byte[] key = null;
        byte[] proof = null;
        try {
            if (password == null) return;
            key = ChallengeProof.deriveKey(password, current.argon2());
            proof = ChallengeProof.create(key, AuthProtocol.AUTH_PROTOCOL_VERSION, current.nonce(),
                    current.sessionId(), identity, current.credentialVersion());
            if (capturedGeneration != connectionGeneration.get()
                    || challenge != current || state.get() != ClientAuthState.COMPUTING) return;
            sender.accept(new AuthResponsePayload(current.sessionId(), current.credentialVersion(), proof),
                    capturedGeneration);
            if (capturedGeneration == connectionGeneration.get()) {
                transition(ClientAuthState.WAITING_FOR_RESULT);
            }
        } catch (RuntimeException exception) {
            if (capturedGeneration == connectionGeneration.get()
                    && state.get() != ClientAuthState.TERMINAL_FAILURE) {
                transition(ClientAuthState.FAILED);
            }
        } finally {
            Arrays.fill(password, '\0');
            if (key != null) Arrays.fill(key, (byte) 0);
            if (proof != null) Arrays.fill(proof, (byte) 0);
        }
    }

    public boolean success(UUID sessionId) {
        AuthChallengePayload current = challenge;
        if (current != null && current.sessionId().equals(sessionId)) {
            clearTransient();
            transition(ClientAuthState.AUTHENTICATED);
            return true;
        }
        return false;
    }

    public boolean failure(AuthFailurePayload failure) {
        if (state.get() == ClientAuthState.TERMINAL_FAILURE) return false;
        credentials.clear();
        if (failure.terminal()) {
            connectionGeneration.incrementAndGet();
            clearTransient();
            terminalFailure = failure.reason();
            transition(ClientAuthState.TERMINAL_FAILURE);
        } else {
            transition(ClientAuthState.FAILED);
        }
        failureListener.accept(failure);
        return true;
    }

    public void disconnected() {
        connectionGeneration.incrementAndGet();
        clearTransient();
        normalizedIdentity = null;
        if (terminalFailure == null) transition(ClientAuthState.DISCONNECTED);
    }

    public void reset() {
        connectionGeneration.incrementAndGet();
        clearTransient();
        normalizedIdentity = null;
        terminalFailure = null;
        transition(ClientAuthState.DISCONNECTED);
    }

    private void clearTransient() {
        credentials.clear();
        challenge = null;
    }

    private void transition(ClientAuthState next) {
        state.set(next);
        notifyState();
    }

    private void notifyState() {
        stateListener.accept(state.get());
    }

    public ClientAuthState state() { return state.get(); }
    public long connectionGeneration() { return connectionGeneration.get(); }
    public boolean maySendForGeneration(long generation) {
        ClientAuthState current = state.get();
        return generation == connectionGeneration.get() && terminalFailure == null
                && (current == ClientAuthState.COMPUTING || current == ClientAuthState.WAITING_FOR_RESULT);
    }
    public Optional<AuthFailureReason> terminalFailure() {
        return Optional.ofNullable(terminalFailure);
    }
    public void onStateChanged(Consumer<ClientAuthState> listener) { stateListener = Objects.requireNonNull(listener); }
    public void onFailure(Consumer<AuthFailurePayload> listener) { failureListener = Objects.requireNonNull(listener); }

    @Override
    public void close() {
        reset();
        derivationExecutor.shutdownNow();
    }

    public enum ChallengeDisposition { AUTO_SUBMITTED, INTERACTIVE_REQUIRED }
}
