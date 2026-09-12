package dev.example.mcauth.server.remote;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandType;

public interface RemoteCommandHandler {
    RemoteCommandType type();
    void validate(JsonObject payload);
    CommandResult execute(JsonObject payload);

    record CommandResult(boolean success, JsonObject result, String safeError) { }
}
