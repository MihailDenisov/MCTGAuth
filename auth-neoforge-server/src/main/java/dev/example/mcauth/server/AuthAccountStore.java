package dev.example.mcauth.server;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.api.SyncEventType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface AuthAccountStore extends AutoCloseable {
    void open();
    Optional<AuthAccount> findByMinecraftName(String name);
    Optional<AuthAccount> findByAccountId(long accountId);
    <T> Optional<T> withAccountByMinecraftName(String name, Function<AuthAccount, T> reader);
    Collection<AuthAccount> all();
    long accountCount();
    SyncApplyResult applySyncEvent(UUID eventId, SyncEventType eventType, Instant processedAt,
                                   AccountMutation mutation);
    boolean reserveRemoteCommand(UUID commandId, RemoteCommandType type, Instant processedAt);
    Optional<RemoteCommandRecord> remoteCommand(UUID commandId);
    void completeRemoteCommand(UUID commandId, RemoteCommandStatus status, JsonObject result, Instant processedAt);
    default void cacheDevice(long deviceId, LocalDeviceFingerprint fingerprint) { }
    default Optional<Long> resolveCachedDevice(LocalDeviceFingerprint fingerprint) { return Optional.empty(); }
    default boolean isDeviceBanned(long deviceId) { return false; }
    default SyncApplyResult applyDeviceBanEvent(UUID eventId, SyncEventType eventType, Instant processedAt,
                                                long deviceId, boolean banned) {
        throw new UnsupportedOperationException("Device-ban cache is not supported");
    }
    Path path();
    @Override void close();

    @FunctionalInterface
    interface AccountMutation {
        void apply(Map<String, AuthAccount> accounts);
    }

    enum SyncApplyResult { APPLIED, ALREADY_PROCESSED }

    record RemoteCommandRecord(RemoteCommandStatus status, JsonObject result) {
        public RemoteCommandRecord {
            result = result.deepCopy();
        }
        @Override public JsonObject result() { return result.deepCopy(); }
    }
}
