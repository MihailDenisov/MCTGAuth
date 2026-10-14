package dev.example.mcauth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Bounded, atomic local journal. All methods are called by the telemetry worker, never a game thread. */
final class TelemetryEventQueue {
    private final Path path;
    private final int maximumSize;
    private final Deque<TelemetryPlayerEvent> events = new ArrayDeque<>();
    private boolean opened;

    TelemetryEventQueue(Path path, int maximumSize) {
        this.path = path.toAbsolutePath().normalize();
        this.maximumSize = maximumSize;
    }

    synchronized void open() throws IOException {
        if (opened) return;
        if (!Files.exists(path)) { opened = true; return; }
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("events");
        if (array == null || array.size() > maximumSize) throw new IOException("Invalid telemetry event journal");
        for (var element : array) {
            JsonObject item = element.getAsJsonObject();
            events.add(new TelemetryPlayerEvent(UUID.fromString(item.get("eventId").getAsString()),
                    item.get("serverId").getAsString(), UUID.fromString(item.get("serverInstanceId").getAsString()),
                    item.get("type").getAsString(), item.get("minecraftAccountId").getAsLong(),
                    item.get("nickname").getAsString(), Instant.parse(item.get("occurredAt").getAsString())));
        }
        opened = true;
    }

    synchronized boolean add(TelemetryPlayerEvent event) throws IOException {
        if (!opened) open();
        if (events.size() >= maximumSize) return false;
        events.addLast(event);
        persist();
        return true;
    }

    synchronized TelemetryPlayerEvent peek() { return events.peekFirst(); }
    synchronized int size() { return events.size(); }

    synchronized void removeFirst() throws IOException {
        if (events.pollFirst() != null) persist();
    }

    private void persist() throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray array = new JsonArray();
        root.add("events", array);
        for (TelemetryPlayerEvent event : events) {
            JsonObject item = new JsonObject();
            item.addProperty("eventId", event.eventId().toString());
            item.addProperty("serverId", event.serverId());
            item.addProperty("serverInstanceId", event.serverInstanceId().toString());
            item.addProperty("type", event.type());
            item.addProperty("minecraftAccountId", event.minecraftAccountId());
            item.addProperty("nickname", event.nickname());
            item.addProperty("occurredAt", event.occurredAt().toString());
            array.add(item);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
