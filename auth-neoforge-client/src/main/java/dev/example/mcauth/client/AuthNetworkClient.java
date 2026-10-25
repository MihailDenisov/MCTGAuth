package dev.example.mcauth.client;

import dev.example.mcauth.protocol.AuthChallengePayload;
import dev.example.mcauth.protocol.AuthFailurePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.ChallengeProof;
import dev.example.mcauth.protocol.DeviceFingerprint;

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
import java.util.function.Supplier;

public final class AuthNetworkClient implements AutoCloseable {
    private final ExecutorService derivationExecutor;
    private final BiConsumer<AuthResponsePayload, Long> sender;
    private final CredentialStore credentials = new CredentialStore();
    private final Supplier<DeviceFingerprint> fingerprintSupplier;
    private final AtomicReference<ClientAuthState> state = new AtomicReference<>(ClientAuthState.DISCONNECTED);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile AuthChallengePayload challenge;
    private volatile String normalizedIdentity;
    private volatile boolean authenticationApplicable;
    private volatile AuthFailureReason terminalFailure;
    private volatile Consumer<ClientAuthState> stateListener = ignored -> { };
    private volatile Consumer<AuthFailurePayload> failureListener = ignored -> { };

    public AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender) {
        this(sender, () -> new DeviceFingerprint(AuthProtocol.FINGERPRINT_VERSION, java.util.List.of()),
                Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-argon2");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender,
                             Supplier<DeviceFingerprint> fingerprintSupplier) {
        this(sender, fingerprintSupplier, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-auth-worker");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender, ExecutorService derivationExecutor) {
        this(sender, () -> new DeviceFingerprint(AuthProtocol.FINGERPRINT_VERSION, java.util.List.of()),
                derivationExecutor);
    }

    AuthNetworkClient(BiConsumer<AuthResponsePayload, Long> sender,
                      Supplier<DeviceFingerprint> fingerprintSupplier,
                      ExecutorService derivationExecutor) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.fingerprintSupplier = Objects.requireNonNull(fingerprintSupplier, "fingerprintSupplier");
        this.derivationExecutor = Objects.requireNonNull(derivationExecutor, "derivationExecutor");
    }

    public void connected(String minecraftName) {
        connected(minecraftName, true);
    }

    public void connected(String minecraftName, boolean authenticationApplicable) {
        connectionGeneration.incrementAndGet();
        clearTransient();
        terminalFailure = null;
        normalizedIdentity = AuthProtocol.normalizeMinecraftName(minecraftName);
        this.authenticationApplicable = authenticationApplicable;
        transition(ClientAuthState.PASSIVE);
    }

    public boolean activate() {
        if (!authenticationApplicable || !state.compareAndSet(ClientAuthState.PASSIVE, ClientAuthState.NEGOTIATING)) {
            return false;
        }
        notifyState();
        return true;
    }

    public boolean challenge(AuthChallengePayload value) {
        if (state.get() != ClientAuthState.NEGOTIATING && state.get() != ClientAuthState.FAILED
                && state.get() != ClientAuthState.WAITING_FOR_RESULT) return false;
        challenge = value;
        transition(ClientAuthState.WAITING_FOR_PASSWORD);
        return true;
    }

    public ChallengeDisposition handleChallenge(AuthChallengePayload value, String storedPassword,
                                                 boolean autoAuthenticate) {
        Objects.requireNonNull(storedPassword, "storedPassword");
        if (!challenge(value)) return ChallengeDisposition.IGNORED;
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
            DeviceFingerprint fingerprint = Objects.requireNonNull(fingerprintSupplier.get(), "device fingerprint");
            sender.accept(new AuthResponsePayload(current.sessionId(), current.credentialVersion(), proof, fingerprint),
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
        ClientAuthState current = state.get();
        if (current == ClientAuthState.DISCONNECTED || current == ClientAuthState.PASSIVE
                || current == ClientAuthState.TERMINAL_FAILURE) return false;
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
        authenticationApplicable = false;
        if (terminalFailure == null) transition(ClientAuthState.DISCONNECTED);
    }

    public void reset() {
        connectionGeneration.incrementAndGet();
        clearTransient();
        normalizedIdentity = null;
        authenticationApplicable = false;
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
    public boolean mayActivate() {
        return authenticationApplicable && state.get() == ClientAuthState.PASSIVE;
    }
    public boolean isActive() {
        ClientAuthState current = state.get();
        return current != ClientAuthState.DISCONNECTED && current != ClientAuthState.PASSIVE;
    }
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

    public enum ChallengeDisposition { AUTO_SUBMITTED, INTERACTIVE_REQUIRED, IGNORED }
}
