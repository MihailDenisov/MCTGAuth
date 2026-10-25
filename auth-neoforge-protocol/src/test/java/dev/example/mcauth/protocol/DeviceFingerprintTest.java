package dev.example.mcauth.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceFingerprintTest {
    @Test
    void rejectsDuplicateTypesAndMalformedDigests() {
        DeviceSignal signal = new DeviceSignal(DeviceSignalType.SYSTEM_UUID, new byte[32]);
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceFingerprint(1, List.of(signal, signal)));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceSignal(DeviceSignalType.SYSTEM_UUID, new byte[31]));
    }

    @Test
    void defensivelyCopiesSignalBytes() {
        byte[] digest = new byte[32];
        DeviceSignal signal = new DeviceSignal(DeviceSignalType.MACHINE_ID, digest);
        assertNotSame(digest, signal.digest());
        signal.digest()[0] = 1;
        org.junit.jupiter.api.Assertions.assertEquals(0, signal.digest()[0]);
    }
}
