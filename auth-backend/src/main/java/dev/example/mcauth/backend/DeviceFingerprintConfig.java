package dev.example.mcauth.backend;

import java.util.EnumMap;
import java.util.Map;

public record DeviceFingerprintConfig(boolean enabled, int fingerprintVersion,
                                      int observationRetentionDays,
                                      Map<SignalType, Integer> weights,
                                      boolean deviceBanEnabled) {
    public DeviceFingerprintConfig {
        if (fingerprintVersion != 1 || observationRetentionDays < 1 || observationRetentionDays > 3650) {
            throw new IllegalArgumentException("Invalid device fingerprint configuration");
        }
        EnumMap<SignalType, Integer> normalized = new EnumMap<>(SignalType.class);
        for (SignalType type : SignalType.values()) {
            int weight = weights.getOrDefault(type, type.defaultWeight());
            if (weight < 1 || weight > 100) throw new IllegalArgumentException("Invalid device signal weight");
            normalized.put(type, weight);
        }
        weights = Map.copyOf(normalized);
    }

    public static DeviceFingerprintConfig defaults() {
        return new DeviceFingerprintConfig(true, 1, 365, Map.of(), true);
    }

    public enum SignalType {
        SYSTEM_UUID(true, 50), MACHINE_ID(true, 45), MOTHERBOARD_UUID(true, 35),
        DISK_ID(true, 20), GPU(false, 10), MAC_ADDRESS(false, 5);
        private final boolean strong;
        private final int defaultWeight;
        SignalType(boolean strong, int defaultWeight) { this.strong = strong; this.defaultWeight = defaultWeight; }
        public boolean strong() { return strong; }
        public int defaultWeight() { return defaultWeight; }
    }
}
