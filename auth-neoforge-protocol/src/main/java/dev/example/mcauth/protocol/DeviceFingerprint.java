package dev.example.mcauth.protocol;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public record DeviceFingerprint(int fingerprintVersion, List<DeviceSignal> signals) {
    public DeviceFingerprint {
        if (fingerprintVersion <= 0 || fingerprintVersion > 1_000) {
            throw new IllegalArgumentException("Invalid fingerprint version");
        }
        Objects.requireNonNull(signals, "signals");
        if (signals.size() > AuthProtocol.MAX_DEVICE_SIGNALS) {
            throw new IllegalArgumentException("Too many device signals");
        }
        EnumSet<DeviceSignalType> seen = EnumSet.noneOf(DeviceSignalType.class);
        for (DeviceSignal signal : signals) {
            Objects.requireNonNull(signal, "signal");
            if (!seen.add(signal.type())) throw new IllegalArgumentException("Duplicate device signal");
        }
        signals = List.copyOf(signals);
    }
}
