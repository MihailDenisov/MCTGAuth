package dev.example.mcauth.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.Argon2Verifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;
import dev.example.mcauth.protocol.DeviceSignalType;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class JsonAuthAccountStore implements AuthAccountStore {
    public static final int SCHEMA_VERSION = 2;
    private static final int MAX_PROCESSED_EVENTS = 10_000;
    private static final int MAX_PROCESSED_COMMANDS = 2_000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile boolean open;

    public JsonAuthAccountStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    @Override
    public void open() {
        mutationLock.lock();
        try {
            if (open) return;
            Path parent = path.getParent();
            if (parent == null) throw new IllegalArgumentException("Credential store needs a parent directory");
            Files.createDirectories(parent);
            if (Files.exists(path)) {
                if (!Files.isRegularFile(path)) throw new IllegalStateException("Credential store path is not a regular file");
                snapshot = readSnapshot();
            } else {
                persist(Snapshot.empty());
                snapshot = Snapshot.empty();
            }
            open = true;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Local MCTGAuth credential store cannot be opened; existing data was preserved", exception);
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public Optional<AuthAccount> findByMinecraftName(String name) {
        requireOpen();
        return Optional.ofNullable(snapshot.accounts.get(AuthProtocol.normalizeMinecraftName(name)));
    }

    @Override
    public Optional<AuthAccount> findByAccountId(long accountId) {
        requireOpen();
        return snapshot.accounts.values().stream().filter(account -> account.accountId() == accountId).findFirst();
    }

    @Override
    public <T> Optional<T> withAccountByMinecraftName(String name, Function<AuthAccount, T> reader) {
        Objects.requireNonNull(reader, "reader");
        mutationLock.lock();
        try {
            requireOpen();
            AuthAccount account = snapshot.accounts.get(AuthProtocol.normalizeMinecraftName(name));
            return account == null ? Optional.empty() : Optional.ofNullable(reader.apply(account));
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public Collection<AuthAccount> all() {
        requireOpen();
        return snapshot.accounts.values();
    }

    @Override
    public long accountCount() {
        requireOpen();
        return snapshot.accounts.size();
    }

    @Override
    public SyncApplyResult applySyncEvent(UUID eventId, SyncEventType eventType, Instant processedAt,
                                          AccountMutation mutation) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(processedAt, "processedAt");
        Objects.requireNonNull(mutation, "mutation");
        mutationLock.lock();
        try {
            requireOpen();
            Snapshot current = snapshot;
            if (current.processedEvents.containsKey(eventId)) return SyncApplyResult.ALREADY_PROCESSED;
            LinkedHashMap<String, AuthAccount> accounts = new LinkedHashMap<>(current.accounts);
            mutation.apply(accounts);
            validateAccounts(accounts);
            LinkedHashMap<UUID, ProcessedEvent> events = new LinkedHashMap<>(current.processedEvents);
            events.put(eventId, new ProcessedEvent(eventType, processedAt));
            trimOldest(events, MAX_PROCESSED_EVENTS);
            Snapshot replacement = new Snapshot(accounts, events, current.remoteCommands,
                    current.devices, current.bannedDevices);
            persist(replacement);
            snapshot = replacement;
            return SyncApplyResult.APPLIED;
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public boolean reserveRemoteCommand(UUID commandId, RemoteCommandType type, Instant processedAt) {
        mutationLock.lock();
        try {
            requireOpen();
            if (snapshot.remoteCommands.containsKey(commandId)) return false;
            LinkedHashMap<UUID, StoredCommand> commands = new LinkedHashMap<>(snapshot.remoteCommands);
            commands.put(commandId, new StoredCommand(type, RemoteCommandStatus.RUNNING, new JsonObject(), processedAt));
            trimOldest(commands, MAX_PROCESSED_COMMANDS);
            Snapshot replacement = new Snapshot(snapshot.accounts, snapshot.processedEvents, commands,
                    snapshot.devices, snapshot.bannedDevices);
            persist(replacement);
            snapshot = replacement;
            return true;
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public Optional<RemoteCommandRecord> remoteCommand(UUID commandId) {
        requireOpen();
        StoredCommand command = snapshot.remoteCommands.get(commandId);
        return command == null ? Optional.empty()
                : Optional.of(new RemoteCommandRecord(command.status, command.result));
    }

    @Override
    public void completeRemoteCommand(UUID commandId, RemoteCommandStatus status, JsonObject result, Instant processedAt) {
        if (status != RemoteCommandStatus.SUCCESS && status != RemoteCommandStatus.FAILED) {
            throw new IllegalArgumentException("Remote command result must be terminal");
        }
        mutationLock.lock();
        try {
            requireOpen();
            StoredCommand existing = snapshot.remoteCommands.get(commandId);
            if (existing == null) throw new IllegalStateException("Remote command was not reserved");
            LinkedHashMap<UUID, StoredCommand> commands = new LinkedHashMap<>(snapshot.remoteCommands);
            commands.put(commandId, new StoredCommand(existing.type, status, result.deepCopy(), processedAt));
            Snapshot replacement = new Snapshot(snapshot.accounts, snapshot.processedEvents, commands,
                    snapshot.devices, snapshot.bannedDevices);
            persist(replacement);
            snapshot = replacement;
        } finally {
            mutationLock.unlock();
        }
    }

    private Snapshot readSnapshot() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported local credential store schema");
        }
        LinkedHashMap<String, AuthAccount> accounts = new LinkedHashMap<>();
        JsonObject accountObject = requiredObject(root, "accounts");
        for (Map.Entry<String, JsonElement> entry : accountObject.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            UUID minecraftUuid = optionalString(value, "minecraftUuid").map(UUID::fromString).orElse(null);
            AuthAccount account = new AuthAccount(requiredLong(value, "accountId"), requiredString(value, "minecraftName"),
                    entry.getKey(), minecraftUuid, requiredString(value, "credentialVerifier"),
                    requiredInt(value, "credentialVersion"), requiredBoolean(value, "enabled"),
                    optionalBoolean(value, "banned", false), requiredBoolean(value, "licensed"),
                    Instant.parse(requiredString(value, "updatedAt")));
            try (Argon2Verifier ignored = Argon2Verifier.parse(account.credentialVerifier())) {
                // Loading validates verifier structure without exposing its contents.
            }
            accounts.put(entry.getKey(), account);
        }
        LinkedHashMap<UUID, ProcessedEvent> events = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(root, "processedEvents")) {
            JsonObject value = element.getAsJsonObject();
            events.put(UUID.fromString(requiredString(value, "id")), new ProcessedEvent(
                    SyncEventType.valueOf(requiredString(value, "type")), Instant.parse(requiredString(value, "processedAt"))));
        }
        LinkedHashMap<UUID, StoredCommand> commands = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(root, "processedRemoteCommands")) {
            JsonObject value = element.getAsJsonObject();
            commands.put(UUID.fromString(requiredString(value, "id")), new StoredCommand(
                    RemoteCommandType.valueOf(requiredString(value, "type")),
                    RemoteCommandStatus.valueOf(requiredString(value, "status")),
                    requiredObject(value, "result").deepCopy(), Instant.parse(requiredString(value, "processedAt"))));
        }
        validateAccounts(accounts);
        LinkedHashMap<Long, LocalDeviceFingerprint> devices = new LinkedHashMap<>();
        LinkedHashSet<Long> bannedDevices = new LinkedHashSet<>();
        if (schemaVersion >= 2) {
            for (JsonElement element : requiredArray(root, "devices")) {
                JsonObject value = element.getAsJsonObject();
                long id = requiredLong(value, "id");
                int fingerprintVersion = requiredInt(value, "fingerprintVersion");
                EnumMap<DeviceSignalType, String> signals = new EnumMap<>(DeviceSignalType.class);
                JsonObject signalObject = requiredObject(value, "signals");
                for (Map.Entry<String, JsonElement> entry : signalObject.entrySet()) {
                    String digest = entry.getValue().getAsString();
                    if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid local device digest");
                    signals.put(DeviceSignalType.valueOf(entry.getKey()), digest);
                }
                devices.put(id, new LocalDeviceFingerprint(fingerprintVersion, signals));
            }
            for (JsonElement element : requiredArray(root, "bannedDeviceIds")) {
                long id = element.getAsLong();
                if (id <= 0) throw new IllegalArgumentException("Invalid banned device ID");
                bannedDevices.add(id);
            }
        }
        return new Snapshot(accounts, events, commands, devices, bannedDevices);
    }

    private void persist(Snapshot value) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject accounts = new JsonObject();
        value.accounts.forEach((normalized, account) -> accounts.add(normalized, accountJson(account)));
        root.add("accounts", accounts);
        JsonArray events = new JsonArray();
        value.processedEvents.forEach((id, event) -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", id.toString());
            json.addProperty("type", event.type.name());
            json.addProperty("processedAt", event.processedAt.toString());
            events.add(json);
        });
        root.add("processedEvents", events);
        JsonArray commands = new JsonArray();
        value.remoteCommands.forEach((id, command) -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", id.toString());
            json.addProperty("type", command.type.name());
            json.addProperty("status", command.status.name());
            json.add("result", command.result.deepCopy());
            json.addProperty("processedAt", command.processedAt.toString());
            commands.add(json);
        });
        root.add("processedRemoteCommands", commands);
        JsonArray devices = new JsonArray();
        value.devices.forEach((id, fingerprint) -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("fingerprintVersion", fingerprint.fingerprintVersion());
            JsonObject signals = new JsonObject();
            fingerprint.signals().forEach((type, digest) -> signals.addProperty(type.name(), digest));
            json.add("signals", signals);
            devices.add(json);
        });
        root.add("devices", devices);
        JsonArray banned = new JsonArray();
        value.bannedDevices.forEach(banned::add);
        root.add("bannedDeviceIds", banned);

        byte[] encoded = JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (Files.exists(path)) Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Local MCTGAuth credential store could not be persisted", exception);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next successful write replaces the same bounded temporary file.
            }
        }
    }

    private static JsonObject accountJson(AuthAccount account) {
        JsonObject value = new JsonObject();
        value.addProperty("accountId", account.accountId());
        value.addProperty("minecraftName", account.minecraftName());
        if (account.minecraftUuid() != null) value.addProperty("minecraftUuid", account.minecraftUuid().toString());
        value.addProperty("credentialVerifier", account.credentialVerifier());
        value.addProperty("credentialVersion", account.credentialVersion());
        value.addProperty("enabled", account.enabled());
        value.addProperty("banned", account.banned());
        value.addProperty("licensed", account.licensed());
        value.addProperty("updatedAt", account.updatedAt().toString());
        return value;
    }

    private static void validateAccounts(Map<String, AuthAccount> accounts) {
        List<Long> accountIds = new ArrayList<>();
        accounts.forEach((normalized, account) -> {
            if (!normalized.equals(account.normalizedName())) throw new IllegalArgumentException("Account key mismatch");
            if (accountIds.contains(account.accountId())) throw new IllegalArgumentException("Duplicate account ID");
            accountIds.add(account.accountId());
        });
    }

    private static <K, V> void trimOldest(LinkedHashMap<K, V> values, int maximum) {
        while (values.size() > maximum) values.remove(values.keySet().iterator().next());
    }

    private static JsonObject requiredObject(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonObject()) throw new IllegalArgumentException("Invalid local store field");
        return value.getAsJsonObject(name);
    }
    private static JsonArray requiredArray(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonArray()) throw new IllegalArgumentException("Invalid local store field");
        return value.getAsJsonArray(name);
    }
    private static String requiredString(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive()) throw new IllegalArgumentException("Invalid local store field");
        String result = value.get(name).getAsString();
        if (result.isBlank() || result.length() > 1024) throw new IllegalArgumentException("Invalid local store field");
        return result;
    }
    private static Optional<String> optionalString(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? Optional.of(requiredString(value, name)) : Optional.empty();
    }
    private static int requiredInt(JsonObject value, String name) {
        int result = value.get(name).getAsInt();
        if (result <= 0) throw new IllegalArgumentException("Invalid local store field");
        return result;
    }
    private static long requiredLong(JsonObject value, String name) {
        long result = value.get(name).getAsLong();
        if (result <= 0) throw new IllegalArgumentException("Invalid local store field");
        return result;
    }
    private static boolean requiredBoolean(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive()) throw new IllegalArgumentException("Invalid local store field");
        return value.get(name).getAsBoolean();
    }

    private static boolean optionalBoolean(JsonObject value, String name, boolean fallback) {
        return value.has(name) ? requiredBoolean(value, name) : fallback;
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("Local MCTGAuth credential store is not open");
    }

    @Override
    public void cacheDevice(long deviceId, LocalDeviceFingerprint fingerprint) {
        if (deviceId <= 0 || fingerprint == null) throw new IllegalArgumentException("Invalid cached device");
        mutationLock.lock();
        try {
            requireOpen();
            LinkedHashMap<Long, LocalDeviceFingerprint> devices = new LinkedHashMap<>(snapshot.devices);
            devices.put(deviceId, fingerprint);
            trimOldest(devices, 10_000);
            Snapshot replacement = new Snapshot(snapshot.accounts, snapshot.processedEvents,
                    snapshot.remoteCommands, devices, snapshot.bannedDevices);
            persist(replacement);
            snapshot = replacement;
        } finally { mutationLock.unlock(); }
    }

    @Override
    public Optional<Long> resolveCachedDevice(LocalDeviceFingerprint fingerprint) {
        requireOpen();
        long bestId = -1;
        double bestRatio = 0;
        for (Map.Entry<Long, LocalDeviceFingerprint> entry : snapshot.devices.entrySet()) {
            LocalDeviceFingerprint candidate = entry.getValue();
            if (candidate.fingerprintVersion() != fingerprint.fingerprintVersion()) continue;
            int available = 0;
            int matched = 0;
            boolean strong = false;
            for (Map.Entry<DeviceSignalType, String> signal : fingerprint.signals().entrySet()) {
                int weight = signalWeight(signal.getKey());
                available += weight;
                if (signal.getValue().equals(candidate.signals().get(signal.getKey()))) {
                    matched += weight;
                    strong |= signal.getKey().strong();
                }
            }
            double ratio = available == 0 ? 0 : (double) matched / available;
            if (strong && ratio >= 0.50d && ratio > bestRatio) {
                bestId = entry.getKey();
                bestRatio = ratio;
            }
        }
        return bestId > 0 ? Optional.of(bestId) : Optional.empty();
    }

    @Override public boolean isDeviceBanned(long deviceId) { requireOpen(); return snapshot.bannedDevices.contains(deviceId); }

    @Override
    public SyncApplyResult applyDeviceBanEvent(UUID eventId, SyncEventType eventType, Instant processedAt,
                                               long deviceId, boolean banned) {
        mutationLock.lock();
        try {
            requireOpen();
            if (snapshot.processedEvents.containsKey(eventId)) return SyncApplyResult.ALREADY_PROCESSED;
            LinkedHashSet<Long> bannedDevices = new LinkedHashSet<>(snapshot.bannedDevices);
            if (banned) bannedDevices.add(deviceId); else bannedDevices.remove(deviceId);
            LinkedHashMap<UUID, ProcessedEvent> events = new LinkedHashMap<>(snapshot.processedEvents);
            events.put(eventId, new ProcessedEvent(eventType, processedAt));
            trimOldest(events, MAX_PROCESSED_EVENTS);
            Snapshot replacement = new Snapshot(snapshot.accounts, events, snapshot.remoteCommands,
                    snapshot.devices, bannedDevices);
            persist(replacement);
            snapshot = replacement;
            return SyncApplyResult.APPLIED;
        } finally { mutationLock.unlock(); }
    }

    private static int signalWeight(DeviceSignalType type) {
        return switch (type) {
            case SYSTEM_UUID -> 50;
            case MACHINE_ID -> 45;
            case MOTHERBOARD_UUID -> 35;
            case DISK_ID -> 20;
            case GPU -> 10;
            case MAC_ADDRESS -> 5;
        };
    }

    @Override public Path path() { return path; }

    @Override
    public void close() {
        mutationLock.lock();
        try {
            open = false;
            snapshot = Snapshot.empty();
        } finally {
            mutationLock.unlock();
        }
    }

    private record ProcessedEvent(SyncEventType type, Instant processedAt) { }
    private record StoredCommand(RemoteCommandType type, RemoteCommandStatus status, JsonObject result,
                                 Instant processedAt) { }
    private record Snapshot(Map<String, AuthAccount> accounts, Map<UUID, ProcessedEvent> processedEvents,
                            Map<UUID, StoredCommand> remoteCommands,
                            Map<Long, LocalDeviceFingerprint> devices, Set<Long> bannedDevices) {
        private Snapshot {
            accounts = Collections.unmodifiableMap(new LinkedHashMap<>(accounts));
            processedEvents = Collections.unmodifiableMap(new LinkedHashMap<>(processedEvents));
            remoteCommands = Collections.unmodifiableMap(new LinkedHashMap<>(remoteCommands));
            devices = Collections.unmodifiableMap(new LinkedHashMap<>(devices));
            bannedDevices = Collections.unmodifiableSet(new LinkedHashSet<>(bannedDevices));
        }
        private static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Set.of()); }
    }
}
