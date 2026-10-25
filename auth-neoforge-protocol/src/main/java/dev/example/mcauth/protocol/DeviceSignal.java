package dev.example.mcauth.protocol;

import java.util.Objects;

public record DeviceSignal(DeviceSignalType type, byte[] digest) {
    public DeviceSignal {
        Objects.requireNonNull(type, "type");
        if (digest == null || digest.length != AuthProtocol.DEVICE_SIGNAL_BYTES) {
            throw new IllegalArgumentException("Invalid device signal digest");
        }
        digest = digest.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }
}
