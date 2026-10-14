package dev.example.mcauth.server;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps live connection UUIDs to authoritative synchronized account IDs. */
final class PlayerSessionTracker {
    private final String serverId;
    private final UUID instanceId;
    private final Clock clock;
    private final Map<UUID, TrackedPlayer> active = new HashMap<>();

    PlayerSessionTracker(String serverId, UUID instanceId, Clock clock) {
        this.serverId = serverId; this.instanceId = instanceId; this.clock = clock;
    }

    synchronized TelemetryPlayerEvent joined(UUID connectionId, AuthAccount account) {
        if (active.containsKey(connectionId)) return null;
        active.put(connectionId, new TrackedPlayer(account.accountId(), account.minecraftName()));
        return event("PLAYER_JOINED", account.accountId(), account.minecraftName());
    }

    synchronized TelemetryPlayerEvent left(UUID connectionId) {
        TrackedPlayer player = active.remove(connectionId);
        return player == null ? null : event("PLAYER_LEFT", player.accountId(), player.nickname());
    }

    synchronized List<TelemetryPlayerEvent> closeAll() {
        List<TelemetryPlayerEvent> result = new ArrayList<>();
        for (TrackedPlayer player : active.values()) result.add(event("PLAYER_LEFT", player.accountId(), player.nickname()));
        active.clear();
        return result;
    }

    synchronized int activeCount() { return active.size(); }

    private TelemetryPlayerEvent event(String type, long accountId, String nickname) {
        return new TelemetryPlayerEvent(UUID.randomUUID(), serverId, instanceId, type,
                accountId, nickname, Instant.now(clock));
    }
    private record TrackedPlayer(long accountId, String nickname) { }
}
