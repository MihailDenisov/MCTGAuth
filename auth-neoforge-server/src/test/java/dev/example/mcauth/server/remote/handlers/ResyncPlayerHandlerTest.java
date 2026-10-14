package dev.example.mcauth.server.remote.handlers;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.server.AuthAccount;
import dev.example.mcauth.server.AuthAccountStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResyncPlayerHandlerTest {
    @Test
    void returnsStructuredAccountNotFoundAndDisabledReasons() {
        JsonObject payload = payload(42);
        var missing = new ResyncPlayerHandler(new StubStore(null), () -> null, ignored -> Optional.empty())
                .execute(payload);
        assertFalse(missing.success());
        assertEquals("ACCOUNT_NOT_FOUND", missing.result().get("reason").getAsString());

        AuthAccount disabled = account(false, null);
        var blocked = new ResyncPlayerHandler(new StubStore(disabled), () -> null, ignored -> Optional.empty())
                .execute(payload(1));
        assertFalse(blocked.success());
        assertEquals("ACCOUNT_DISABLED", blocked.result().get("reason").getAsString());
        assertEquals("RegisteredName", blocked.result().get("registeredMinecraftName").getAsString());
    }

    @Test
    void returnsSafeNicknameMismatchDetailsWhenConnectedIdentityIsKnown() {
        UUID uuid = UUID.randomUUID();
        AuthAccount account = account(true, uuid);
        var result = new ResyncPlayerHandler(new StubStore(account), () -> null,
                requested -> requested.equals(uuid) ? Optional.of("ConnectedName") : Optional.empty())
                .execute(payload(1));

        assertFalse(result.success());
        assertEquals("MINECRAFT_NAME_MISMATCH", result.result().get("reason").getAsString());
        assertEquals(1, result.result().get("accountId").getAsLong());
        assertEquals("RegisteredName", result.result().get("registeredMinecraftName").getAsString());
        assertEquals("ConnectedName", result.result().get("connectedMinecraftName").getAsString());
    }

    private static JsonObject payload(long accountId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId);
        return payload;
    }

    private static AuthAccount account(boolean enabled, UUID uuid) {
        return new AuthAccount(1, "RegisteredName", "registeredname", uuid,
                "verifier", 1, enabled, true, Instant.parse("2026-09-12T12:00:00Z"));
    }

    private static final class StubStore implements AuthAccountStore {
        private final AuthAccount account;
        private StubStore(AuthAccount account) { this.account = account; }
        @Override public void open() { }
        @Override public Optional<AuthAccount> findByMinecraftName(String name) { return Optional.empty(); }
        @Override public Optional<AuthAccount> findByAccountId(long accountId) {
            return account != null && account.accountId() == accountId ? Optional.of(account) : Optional.empty();
        }
        @Override public <T> Optional<T> withAccountByMinecraftName(
                String name, Function<AuthAccount, T> reader) { return Optional.empty(); }
        @Override public Collection<AuthAccount> all() { return account == null ? List.of() : List.of(account); }
        @Override public long accountCount() { return account == null ? 0 : 1; }
        @Override public SyncApplyResult applySyncEvent(UUID eventId, SyncEventType eventType,
                Instant processedAt, AccountMutation mutation) { throw new UnsupportedOperationException(); }
        @Override public boolean reserveRemoteCommand(
                UUID commandId, RemoteCommandType type, Instant processedAt) { return false; }
        @Override public Optional<RemoteCommandRecord> remoteCommand(UUID commandId) { return Optional.empty(); }
        @Override public void completeRemoteCommand(UUID commandId, RemoteCommandStatus status,
                JsonObject result, Instant processedAt) { }
        @Override public Path path() { return Path.of("stub"); }
        @Override public void close() { }
    }
}
