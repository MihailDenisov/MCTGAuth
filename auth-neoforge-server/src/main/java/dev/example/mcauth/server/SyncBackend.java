package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEvent;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

interface SyncBackend extends AutoCloseable {
    List<SyncEvent> pendingEvents() throws IOException, InterruptedException;
    void acknowledge(UUID eventId) throws IOException, InterruptedException;
    @Override void close();
}
