package dev.example.mcauth.server;

import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.DeviceFingerprint;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class DeviceAuthenticationService implements AutoCloseable {
    private final BackendSyncClient backend;
    private final AuthAccountStore store;
    private final ServerAuthConfig.Values config;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DeviceAuthenticationService(BackendSyncClient backend, AuthAccountStore store,
                                       ServerAuthConfig.Values config) {
        this.backend = backend;
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
    }

    public DeviceAuthenticationService(AuthAccountStore store, ServerAuthConfig.Values config) {
        this(null, store, config);
    }

    public void evaluate(AuthAccount account, DeviceFingerprint fingerprint, Consumer<Verdict> callback) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(callback, "callback");
        if (account.banned()) {
            callback.accept(Verdict.denied(AuthFailureReason.ACCOUNT_DISABLED, null));
            return;
        }
        if (!config.deviceFingerprintEnabled()) {
            callback.accept(Verdict.allowed(null));
            return;
        }
        AuthFailureReason invalid = validate(fingerprint);
        if (invalid != null) {
            callback.accept(Verdict.denied(invalid, null));
            return;
        }
        executor.execute(() -> evaluateOffThread(account, fingerprint, callback));
    }

    private void evaluateOffThread(AuthAccount account, DeviceFingerprint fingerprint, Consumer<Verdict> callback) {
        LocalDeviceFingerprint local = LocalDeviceFingerprint.from(fingerprint);
        if (backend == null) {
            callback.accept(offline(local));
            return;
        }
        try {
            BackendSyncClient.DeviceVerdict verdict = backend.evaluateDevice(
                    account.accountId(), account.normalizedName(), fingerprint);
            store.cacheDevice(verdict.deviceId(), local);
            callback.accept(switch (verdict.decision()) {
                case ALLOWED -> Verdict.allowed(verdict.deviceId());
                case USER_BANNED -> Verdict.denied(AuthFailureReason.ACCOUNT_DISABLED, verdict.deviceId());
                case DEVICE_BANNED -> Verdict.denied(AuthFailureReason.DEVICE_BANNED, verdict.deviceId());
            });
        } catch (IOException exception) {
            callback.accept(offline(local));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            callback.accept(offline(local));
        } catch (RuntimeException exception) {
            callback.accept(Verdict.denied(AuthFailureReason.INTERNAL_AUTH_ERROR, null));
        }
    }

    private Verdict offline(LocalDeviceFingerprint fingerprint) {
        var cached = store.resolveCachedDevice(fingerprint);
        if (cached.isPresent()) {
            return config.deviceBanEnabled() && store.isDeviceBanned(cached.get())
                    ? Verdict.denied(AuthFailureReason.DEVICE_BANNED, cached.get()) : Verdict.allowed(cached.get());
        }
        return config.failClosedWhenBackendUnavailable()
                ? Verdict.denied(AuthFailureReason.DEVICE_VERIFICATION_UNAVAILABLE, null) : Verdict.allowed(null);
    }

    private AuthFailureReason validate(DeviceFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.fingerprintVersion() != config.fingerprintVersion()
                || fingerprint.signals().isEmpty()
                || fingerprint.signals().size() > AuthProtocol.MAX_DEVICE_SIGNALS) {
            return AuthFailureReason.INVALID_CHALLENGE;
        }
        HashSet<dev.example.mcauth.protocol.DeviceSignalType> seen = new HashSet<>();
        int strong = 0;
        for (var signal : fingerprint.signals()) {
            if (!seen.add(signal.type()) || signal.digest().length != AuthProtocol.DEVICE_SIGNAL_BYTES) {
                return AuthFailureReason.INVALID_CHALLENGE;
            }
            if (signal.type().strong()) strong++;
        }
        return strong < config.minimumStrongSignals() ? AuthFailureReason.INVALID_CHALLENGE : null;
    }

    @Override public void close() { executor.shutdownNow(); }

    public record Verdict(boolean allowed, AuthFailureReason failure, Long deviceId) {
        static Verdict allowed(Long deviceId) { return new Verdict(true, null, deviceId); }
        static Verdict denied(AuthFailureReason reason, Long deviceId) { return new Verdict(false, reason, deviceId); }
    }
}
