package dev.example.mcauth.client;

import dev.example.mcauth.protocol.DeviceSignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceFingerprintCollectorTest {
    @TempDir Path temporary;

    @Test
    void collectsOnlyEnabledSignalsAndCachesWithoutRetainingRawValues() {
        Map<DeviceSignalType, Integer> calls = new EnumMap<>(DeviceSignalType.class);
        DeviceFingerprintCollector collector = new DeviceFingerprintCollector(type -> {
            calls.merge(type, 1, Integer::sum);
            return Optional.of(type == DeviceSignalType.SYSTEM_UUID ? " aa_bb:cc " : "raw-gpu-value");
        });

        var first = collector.collect(EnumSet.of(DeviceSignalType.SYSTEM_UUID));
        var second = collector.collect(EnumSet.of(DeviceSignalType.SYSTEM_UUID));

        assertEquals(1, first.signals().size());
        assertEquals(1, calls.get(DeviceSignalType.SYSTEM_UUID));
        assertFalse(calls.containsKey(DeviceSignalType.GPU), "disabled weak signals must never be queried");
        assertEquals(first.signals().getFirst(), second.signals().getFirst());
        assertEquals(32, first.signals().getFirst().digest().length);
    }

    @Test
    void ignoresPlaceholderIdentifiers() {
        assertTrue(DeviceFingerprintCollector.normalize("00000000-0000-0000-0000-000000000000").isEmpty());
        assertTrue(DeviceFingerprintCollector.normalize(" unknown ").isEmpty());
        assertEquals("AA-BB-CC", DeviceFingerprintCollector.normalize(" aa_bb:cc ").orElseThrow());
    }

    @Test
    void configurationDefaultsStrongSignalsOnAndWeakSignalsOff() throws Exception {
        MCTGAuthClientConfig config = new MCTGAuthClientConfig(temporary.resolve("client.json"));
        config.load();
        assertEquals(EnumSet.of(DeviceSignalType.SYSTEM_UUID, DeviceSignalType.MACHINE_ID,
                DeviceSignalType.MOTHERBOARD_UUID, DeviceSignalType.DISK_ID), config.enabledDeviceSignals());

        Files.writeString(config.path(), """
                {"schemaVersion":1,"password":"","autoAuthenticate":true,
                 "deviceFingerprint":{"enabled":true,"systemUuid":true,"machineId":false,
                 "motherboardUuid":false,"diskId":false,"gpu":true,"macAddress":false}}
                """, StandardCharsets.UTF_8);
        config.load();
        assertEquals(EnumSet.of(DeviceSignalType.SYSTEM_UUID, DeviceSignalType.GPU),
                config.enabledDeviceSignals());
    }
}
