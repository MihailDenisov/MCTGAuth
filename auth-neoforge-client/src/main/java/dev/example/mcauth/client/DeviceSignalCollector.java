package dev.example.mcauth.client;

import dev.example.mcauth.protocol.DeviceSignalType;

import java.util.Optional;

@FunctionalInterface
public interface DeviceSignalCollector {
    Optional<String> collect(DeviceSignalType type);
}
