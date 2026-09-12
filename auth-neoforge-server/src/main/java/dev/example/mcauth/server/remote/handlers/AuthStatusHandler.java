package dev.example.mcauth.server.remote.handlers;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.BackendStatus;
import dev.example.mcauth.server.AuthAccountStore;
import dev.example.mcauth.server.LoginSessionManager;
import dev.example.mcauth.server.remote.RemoteCommandHandler;

public final class AuthStatusHandler implements RemoteCommandHandler {
    private final AuthAccountStore store;
    private final LoginSessionManager sessions;
    private final BackendStatus backend;

    public AuthStatusHandler(AuthAccountStore store, LoginSessionManager sessions, BackendStatus backend) {
        this.store = store;
        this.sessions = sessions;
        this.backend = backend;
    }
    @Override public RemoteCommandType type() { return RemoteCommandType.AUTH_STATUS; }
    @Override public void validate(JsonObject payload) {
        if (payload.size() != 0) throw new IllegalArgumentException("AUTH_STATUS payload must be empty");
    }
    @Override public CommandResult execute(JsonObject payload) {
        JsonObject result = new JsonObject();
        result.addProperty("localStore", "ok");
        result.addProperty("backendSync", backend.backendHealthy() ? "ok" : "degraded");
        result.addProperty("localAccounts", store.accountCount());
        result.addProperty("authenticatedPlayers", sessions.authenticatedCount());
        result.addProperty("activeUnauthenticatedSessions", sessions.activeUnauthenticated());
        return new CommandResult(true, result, "");
    }
}
