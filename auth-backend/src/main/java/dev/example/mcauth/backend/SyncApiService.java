package dev.example.mcauth.backend;

import dev.example.mcauth.api.SyncEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SyncApiService {
    private final SyncEventService events;
    private final String serverId;

    public SyncApiService(SyncEventService events, String serverId) {
        this.events = Objects.requireNonNull(events, "events");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public List<SyncEvent> pending(String requestedServerId) {
        requireServer(requestedServerId);
        return events.pendingEvents();
    }

    public boolean acknowledge(String requestedServerId, UUID eventId) {
        requireServer(requestedServerId);
        return events.acknowledge(eventId);
    }

    private void requireServer(String requestedServerId) {
        if (!serverId.equals(requestedServerId)) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Server is not authorized");
        }
    }
}
