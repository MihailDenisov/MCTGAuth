package dev.example.mcauth.client;

import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.DeviceFingerprint;
import dev.example.mcauth.protocol.DeviceSignal;
import dev.example.mcauth.protocol.DeviceSignalType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DeviceFingerprintCollector {
    private static final Set<String> PLACEHOLDERS = Set.of("UNKNOWN", "DEFAULT", "NONE", "N/A", "NOT SPECIFIED",
            "00000000-0000-0000-0000-000000000000", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    private final DeviceSignalCollector provider;
    private final Map<DeviceSignalType, Optional<DeviceSignal>> cache = new EnumMap<>(DeviceSignalType.class);

    public DeviceFingerprintCollector(DeviceSignalCollector provider) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
    }

    public synchronized DeviceFingerprint collect(Set<DeviceSignalType> enabled) {
        EnumSet<DeviceSignalType> requested = enabled.isEmpty()
                ? EnumSet.noneOf(DeviceSignalType.class) : EnumSet.copyOf(enabled);
        ArrayList<DeviceSignal> signals = new ArrayList<>();
        for (DeviceSignalType type : DeviceSignalType.values()) {
            if (!requested.contains(type)) continue;
            Optional<DeviceSignal> signal = cache.computeIfAbsent(type, this::collectOne);
            signal.ifPresent(signals::add);
        }
        return new DeviceFingerprint(AuthProtocol.FINGERPRINT_VERSION, signals);
    }

    private Optional<DeviceSignal> collectOne(DeviceSignalType type) {
        return provider.collect(type).flatMap(DeviceFingerprintCollector::normalize)
                .map(value -> new DeviceSignal(type, digest(type, value)));
    }

    static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String value = raw.strip().replaceAll("[\\s:_-]+", "-").toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 4096 || PLACEHOLDERS.contains(value)
                || value.matches("[0F-]{8,}")) return Optional.empty();
        return Optional.of(value);
    }

    private static byte[] digest(DeviceSignalType type, String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("mctgauth-device-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(type.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            return digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
