package dev.example.mcauth.server.remote.handlers;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.remote.RemoteCommandHandler;

import java.util.Set;

public final class ResyncPlayerHandler implements RemoteCommandHandler {
    @Override public RemoteCommandType type() { return RemoteCommandType.RESYNC_PLAYER; }
    @Override public void validate(JsonObject payload) {
        if (!Set.of("accountId").equals(payload.keySet()) || !payload.get("accountId").isJsonPrimitive()
                || payload.get("accountId").getAsLong() <= 0) {
            throw new IllegalArgumentException("RESYNC_PLAYER requires one positive accountId");
        }
    }
    @Override public CommandResult execute(JsonObject payload) {
        JsonObject result = new JsonObject();
        result.addProperty("accountId", payload.get("accountId").getAsLong());
        return new CommandResult(false, result, "Per-player resync is unsupported by the current backend API");
    }
}
