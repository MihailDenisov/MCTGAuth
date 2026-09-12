package dev.example.mcauth.server.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.AuthAccountStore;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RemoteCommandDispatcher {
    private final String serverId;
    private final AuthAccountStore store;
    private final Clock clock;
    private final Map<RemoteCommandType, RemoteCommandHandler> handlers;

    public RemoteCommandDispatcher(String serverId, AuthAccountStore store, Clock clock,
                                   List<RemoteCommandHandler> handlers) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        EnumMap<RemoteCommandType, RemoteCommandHandler> map = new EnumMap<>(RemoteCommandType.class);
        for (RemoteCommandHandler handler : handlers) {
            if (map.put(handler.type(), handler) != null) throw new IllegalArgumentException("Duplicate command handler");
        }
        this.handlers = Map.copyOf(map);
    }

    public RemoteCommandHandler.CommandResult dispatch(RemoteCommand command) {
        validateEnvelope(command);
        AuthAccountStore.RemoteCommandRecord cached = store.remoteCommand(command.id()).orElse(null);
        if (cached != null && cached.status() != RemoteCommandStatus.RUNNING) {
            return new RemoteCommandHandler.CommandResult(cached.status() == RemoteCommandStatus.SUCCESS,
                    cached.result(), cached.status() == RemoteCommandStatus.SUCCESS ? "" : "Remote command failed");
        }
        RemoteCommandHandler handler = handlers.get(command.type());
        if (handler == null) return persist(command, failure("Unsupported remote command type"));
        try {
            JsonObject payload = JsonParser.parseString(command.payloadJson()).getAsJsonObject();
            handler.validate(payload);
            return persist(command, handler.execute(payload));
        } catch (Exception exception) {
            return persist(command, failure("Remote command validation or execution failed"));
        }
    }

    public boolean reserve(RemoteCommand command) {
        validateEnvelope(command);
        return store.reserveRemoteCommand(command.id(), command.type(), Instant.now(clock));
    }

    private RemoteCommandHandler.CommandResult persist(RemoteCommand command,
                                                        RemoteCommandHandler.CommandResult result) {
        store.completeRemoteCommand(command.id(), result.success() ? RemoteCommandStatus.SUCCESS
                : RemoteCommandStatus.FAILED, result.result(), Instant.now(clock));
        return result;
    }

    private void validateEnvelope(RemoteCommand command) {
        if (!serverId.equals(command.serverId()) || command.schemaVersion() != 1
                || !Instant.now(clock).isBefore(command.expiresAt())) {
            throw new IllegalArgumentException("Invalid or expired remote command envelope");
        }
    }

    private static RemoteCommandHandler.CommandResult failure(String message) {
        return new RemoteCommandHandler.CommandResult(false, new JsonObject(), message);
    }
}
