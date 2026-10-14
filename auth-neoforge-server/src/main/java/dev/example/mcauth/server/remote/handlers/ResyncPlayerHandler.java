package dev.example.mcauth.server.remote.handlers;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.AuthAccount;
import dev.example.mcauth.server.AuthAccountStore;
import dev.example.mcauth.server.SyncWorker;
import dev.example.mcauth.server.remote.RemoteCommandHandler;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ResyncPlayerHandler implements RemoteCommandHandler {
    private final AuthAccountStore store;
    private final Supplier<SyncWorker> worker;
    private final Function<UUID, Optional<String>> connectedName;

    public ResyncPlayerHandler(AuthAccountStore store, Supplier<SyncWorker> worker,
                               Function<UUID, Optional<String>> connectedName) {
        this.store = Objects.requireNonNull(store, "store");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.connectedName = Objects.requireNonNull(connectedName, "connectedName");
    }

    @Override public RemoteCommandType type() { return RemoteCommandType.RESYNC_PLAYER; }
    @Override public void validate(JsonObject payload) {
        if (!Set.of("accountId").equals(payload.keySet()) || !payload.get("accountId").isJsonPrimitive()
                || payload.get("accountId").getAsLong() <= 0) {
            throw new IllegalArgumentException("RESYNC_PLAYER requires one positive accountId");
        }
    }
    @Override public CommandResult execute(JsonObject payload) {
        long accountId = payload.get("accountId").getAsLong();
        JsonObject result = new JsonObject();
        result.addProperty("accountId", accountId);
        Optional<AuthAccount> account = store.findByAccountId(accountId);
        if (account.isEmpty()) {
            boolean scheduled = requestPoll();
            result.addProperty("reason", "ACCOUNT_NOT_FOUND");
            result.addProperty("syncPollScheduled", scheduled);
            return new CommandResult(false, result, "ACCOUNT_NOT_FOUND");
        }
        AuthAccount known = account.get();
        result.addProperty("registeredMinecraftName", known.minecraftName());
        Optional<String> connected = connectedName.apply(known.minecraftUuid());
        if (connected.isPresent() && !known.minecraftName().equals(connected.get())) {
            result.addProperty("connectedMinecraftName", connected.get());
            result.addProperty("reason", "MINECRAFT_NAME_MISMATCH");
            return new CommandResult(false, result, "MINECRAFT_NAME_MISMATCH");
        }
        if (!known.enabled()) {
            result.addProperty("reason", "ACCOUNT_DISABLED");
            return new CommandResult(false, result, "ACCOUNT_DISABLED");
        }
        boolean scheduled = requestPoll();
        result.addProperty("syncPollScheduled", scheduled);
        result.addProperty("reason", scheduled ? "SYNC_EVENT_NOT_FOUND" : "BACKEND_UNAVAILABLE");
        return new CommandResult(false, result,
                scheduled ? "SYNC_EVENT_NOT_FOUND" : "BACKEND_UNAVAILABLE");
    }

    private boolean requestPoll() {
        SyncWorker current = worker.get();
        return current != null && current.requestImmediatePoll();
    }
}
