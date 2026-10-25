package dev.example.mcauth.server;

import dev.example.mcauth.protocol.DeviceFingerprint;
import dev.example.mcauth.protocol.DeviceSignal;
import dev.example.mcauth.protocol.DeviceSignalType;

import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

public record LocalDeviceFingerprint(int fingerprintVersion, Map<DeviceSignalType, String> signals) {
    public LocalDeviceFingerprint {
        signals = Map.copyOf(signals);
    }

    public static LocalDeviceFingerprint from(DeviceFingerprint fingerprint) {
        EnumMap<DeviceSignalType, String> values = new EnumMap<>(DeviceSignalType.class);
        for (DeviceSignal signal : fingerprint.signals()) {
            values.put(signal.type(), HexFormat.of().formatHex(signal.digest()));
        }
        return new LocalDeviceFingerprint(fingerprint.fingerprintVersion(), values);
    }
}
