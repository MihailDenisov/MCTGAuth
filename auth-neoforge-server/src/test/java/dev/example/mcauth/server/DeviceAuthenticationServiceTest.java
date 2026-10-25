package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.DeviceFingerprint;
import dev.example.mcauth.protocol.DeviceSignal;
import dev.example.mcauth.protocol.DeviceSignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceAuthenticationServiceTest {
    @TempDir Path temporary;

    @Test
    void offlineKnownBannedDeviceIsDeniedButUnknownDeviceUsesConfiguredPolicy() throws Exception {
        JsonAuthAccountStore store = new JsonAuthAccountStore(temporary.resolve("accounts.json"));
        store.open();
        DeviceFingerprint known = fingerprint(1, 2);
        store.cacheDevice(91L, LocalDeviceFingerprint.from(known));
        store.applyDeviceBanEvent(UUID.randomUUID(), SyncEventType.DEVICE_BANNED,
                Instant.parse("2026-01-01T00:00:00Z"), 91L, true);

        try (DeviceAuthenticationService permissive = new DeviceAuthenticationService(store, config(false))) {
            CompletableFuture<DeviceAuthenticationService.Verdict> bannedAccountResult = new CompletableFuture<>();
            permissive.evaluate(account(true), known, bannedAccountResult::complete);
            assertEquals(AuthFailureReason.ACCOUNT_DISABLED,
                    bannedAccountResult.get(5, TimeUnit.SECONDS).failure());

            var banned = evaluate(permissive, known);
            assertFalse(banned.allowed());
            assertEquals(AuthFailureReason.DEVICE_BANNED, banned.failure());

            var unknown = evaluate(permissive, fingerprint(8, 9));
            assertTrue(unknown.allowed(), "default outage policy preserves valid cached-account login");
        }
        try (DeviceAuthenticationService strict = new DeviceAuthenticationService(store, config(true))) {
            var unknown = evaluate(strict, fingerprint(8, 9));
            assertFalse(unknown.allowed());
            assertEquals(AuthFailureReason.DEVICE_VERIFICATION_UNAVAILABLE, unknown.failure());
        }
        store.close();
    }

    @Test
    void malformedOrWeakOnlyFingerprintIsRejectedBeforeAsyncBackendWork() throws Exception {
        JsonAuthAccountStore store = new JsonAuthAccountStore(temporary.resolve("invalid.json"));
        store.open();
        try (DeviceAuthenticationService service = new DeviceAuthenticationService(store, config(false))) {
            DeviceFingerprint weak = new DeviceFingerprint(1,
                    List.of(new DeviceSignal(DeviceSignalType.GPU, digest(4))));
            var verdict = evaluate(service, weak);
            assertFalse(verdict.allowed());
            assertEquals(AuthFailureReason.INVALID_CHALLENGE, verdict.failure());
        }
        store.close();
    }

    private static DeviceAuthenticationService.Verdict evaluate(DeviceAuthenticationService service,
                                                                  DeviceFingerprint fingerprint) throws Exception {
        CompletableFuture<DeviceAuthenticationService.Verdict> result = new CompletableFuture<>();
        service.evaluate(account(false), fingerprint, result::complete);
        return result.get(5, TimeUnit.SECONDS);
    }

    private static AuthAccount account(boolean banned) {
        return new AuthAccount(1L, "PlayerOne", "playerone", null,
                "unused", 1, true, banned, false, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static DeviceFingerprint fingerprint(int system, int machine) {
        return new DeviceFingerprint(1, List.of(
                new DeviceSignal(DeviceSignalType.SYSTEM_UUID, digest(system)),
                new DeviceSignal(DeviceSignalType.MACHINE_ID, digest(machine))));
    }

    private static byte[] digest(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static ServerAuthConfig.Values config(boolean failClosed) {
        return new ServerAuthConfig.Values(URI.create("https://backend.example.test"), "survival-main",
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(60), 3, 100, false, true, true,
                Duration.ofSeconds(20), 1000, true, 1, 1, true, failClosed);
    }
}
