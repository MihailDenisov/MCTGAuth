package dev.example.mcauth.server.remote.handlers;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.SyncWorker;
import dev.example.mcauth.server.remote.RemoteCommandHandler;

import java.util.function.Supplier;

public final class ForceAuthSyncHandler implements RemoteCommandHandler {
    private final Supplier<SyncWorker> worker;
    public ForceAuthSyncHandler(Supplier<SyncWorker> worker) {
        this.worker = worker;
    }
    @Override public RemoteCommandType type() { return RemoteCommandType.FORCE_AUTH_SYNC; }
    @Override public void validate(JsonObject payload) {
        if (payload.size() != 0) throw new IllegalArgumentException("FORCE_AUTH_SYNC payload must be empty");
    }
    @Override public CommandResult execute(JsonObject payload) {
        boolean scheduled = worker.get() != null && worker.get().requestImmediatePoll();
        JsonObject result = new JsonObject();
        result.addProperty("scheduled", scheduled);
        return new CommandResult(scheduled, result, scheduled ? "" : "Synchronization is unavailable");
    }
}
