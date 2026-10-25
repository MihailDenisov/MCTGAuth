package dev.example.mcauth.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.EnumSet;
import java.util.Set;
import dev.example.mcauth.protocol.DeviceSignalType;

/** Client-only plaintext credential configuration. Password values are never logged. */
public final class MCTGAuthClientConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "mctgauth-client.json";

    private final Path path;
    private String password = "";
    private boolean autoAuthenticate = true;
    private boolean fingerprintEnabled = true;
    private boolean systemUuidEnabled = true;
    private boolean machineIdEnabled = true;
    private boolean motherboardUuidEnabled = true;
    private boolean diskIdEnabled = true;
    private boolean gpuEnabled;
    private boolean macAddressEnabled;

    public MCTGAuthClientConfig(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public synchronized void load() {
        if (!Files.exists(path)) {
            password = "";
            autoAuthenticate = true;
            resetFingerprintDefaults();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported MCTGAuth client configuration schema");
            }
            String loadedPassword = root.has("password") ? root.get("password").getAsString() : "";
            if (loadedPassword.length() > 256) throw new IllegalStateException("MCTGAuth password is too long");
            password = loadedPassword;
            autoAuthenticate = !root.has("autoAuthenticate") || root.get("autoAuthenticate").getAsBoolean();
            JsonObject fingerprint = root.has("deviceFingerprint") && root.get("deviceFingerprint").isJsonObject()
                    ? root.getAsJsonObject("deviceFingerprint") : new JsonObject();
            fingerprintEnabled = bool(fingerprint, "enabled", true);
            systemUuidEnabled = bool(fingerprint, "systemUuid", true);
            machineIdEnabled = bool(fingerprint, "machineId", true);
            motherboardUuidEnabled = bool(fingerprint, "motherboardUuid", true);
            diskIdEnabled = bool(fingerprint, "diskId", true);
            gpuEnabled = bool(fingerprint, "gpu", false);
            macAddressEnabled = bool(fingerprint, "macAddress", false);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("MCTGAuth client configuration cannot be loaded", exception);
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(password, autoAuthenticate);
    }

    public synchronized void save(String newPassword, boolean newAutoAuthenticate) {
        Objects.requireNonNull(newPassword, "newPassword");
        if (newPassword.length() > 256) throw new IllegalArgumentException("MCTGAuth password is too long");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("password", newPassword);
        root.addProperty("autoAuthenticate", newAutoAuthenticate);
        JsonObject fingerprint = new JsonObject();
        fingerprint.addProperty("enabled", fingerprintEnabled);
        fingerprint.addProperty("systemUuid", systemUuidEnabled);
        fingerprint.addProperty("machineId", machineIdEnabled);
        fingerprint.addProperty("motherboardUuid", motherboardUuidEnabled);
        fingerprint.addProperty("diskId", diskIdEnabled);
        fingerprint.addProperty("gpu", gpuEnabled);
        fingerprint.addProperty("macAddress", macAddressEnabled);
        root.add("deviceFingerprint", fingerprint);
        byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            password = newPassword;
            autoAuthenticate = newAutoAuthenticate;
        } catch (IOException exception) {
            throw new IllegalStateException("MCTGAuth client configuration cannot be saved", exception);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A failed temporary-file cleanup is retried by the next save.
            }
        }
    }

    public Path path() {
        return path;
    }

    public synchronized Set<DeviceSignalType> enabledDeviceSignals() {
        if (!fingerprintEnabled) return Set.of();
        EnumSet<DeviceSignalType> enabled = EnumSet.noneOf(DeviceSignalType.class);
        if (systemUuidEnabled) enabled.add(DeviceSignalType.SYSTEM_UUID);
        if (machineIdEnabled) enabled.add(DeviceSignalType.MACHINE_ID);
        if (motherboardUuidEnabled) enabled.add(DeviceSignalType.MOTHERBOARD_UUID);
        if (diskIdEnabled) enabled.add(DeviceSignalType.DISK_ID);
        if (gpuEnabled) enabled.add(DeviceSignalType.GPU);
        if (macAddressEnabled) enabled.add(DeviceSignalType.MAC_ADDRESS);
        return Set.copyOf(enabled);
    }

    private void resetFingerprintDefaults() {
        fingerprintEnabled = true;
        systemUuidEnabled = true;
        machineIdEnabled = true;
        motherboardUuidEnabled = true;
        diskIdEnabled = true;
        gpuEnabled = false;
        macAddressEnabled = false;
    }

    private static boolean bool(JsonObject object, String name, boolean defaultValue) {
        return object.has(name) ? object.get(name).getAsBoolean() : defaultValue;
    }

    public static final class Snapshot {
        private final String password;
        private final boolean autoAuthenticate;

        private Snapshot(String password, boolean autoAuthenticate) {
            this.password = password;
            this.autoAuthenticate = autoAuthenticate;
        }

        public String password() { return password; }
        public boolean autoAuthenticate() { return autoAuthenticate; }

        @Override public String toString() {
            return "Snapshot[password=<redacted>, autoAuthenticate=" + autoAuthenticate + "]";
        }
    }
}
