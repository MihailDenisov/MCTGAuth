package dev.example.mcauth.server;

import com.google.gson.JsonObject;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.server.remote.RemoteCommandDispatcher;
import dev.example.mcauth.server.remote.RemoteCommandHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCommandDispatcherTest {
    @TempDir Path temporary;

    @Test
    void validatesTargetExpiryPayloadAndDoesNotRepeatCompletedHandler() {
        JsonAuthAccountStore store = new JsonAuthAccountStore(temporary.resolve("accounts.json"));
        store.open();
        AtomicInteger executions = new AtomicInteger();
        RemoteCommandHandler handler = new RemoteCommandHandler() {
            @Override public RemoteCommandType type() { return RemoteCommandType.AUTH_STATUS; }
            @Override public void validate(JsonObject payload) {
                if (payload.size() != 0) throw new IllegalArgumentException("payload must be empty");
            }
            @Override public CommandResult execute(JsonObject payload) {
                executions.incrementAndGet();
                JsonObject result = new JsonObject();
                result.addProperty("healthy", true);
                return new CommandResult(true, result, "");
            }
        };
        Instant now = Instant.parse("2026-09-12T12:00:00Z");
        RemoteCommandDispatcher dispatcher = new RemoteCommandDispatcher("survival-main", store,
                Clock.fixed(now, ZoneOffset.UTC), List.of(handler));
        RemoteCommand command = command("survival-main", now.plusSeconds(60), "{}");
        assertTrue(dispatcher.reserve(command));
        assertTrue(dispatcher.dispatch(command).success());
        assertFalse(dispatcher.reserve(command));
        assertTrue(dispatcher.dispatch(command).success());
        assertTrue(executions.get() == 1);

        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.reserve(command("other-server", now.plusSeconds(60), "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.reserve(command("survival-main", now, "{}")));

        RemoteCommand malformed = command("survival-main", now.plusSeconds(60), "{\"unexpected\":true}");
        assertTrue(dispatcher.reserve(malformed));
        assertFalse(dispatcher.dispatch(malformed).success());
        store.close();
    }

    private static RemoteCommand command(String server, Instant expires, String payload) {
        Instant created = Instant.parse("2026-09-12T11:59:00Z");
        return new RemoteCommand(UUID.randomUUID(), server, RemoteCommandType.AUTH_STATUS, 1, payload,
                RemoteCommandStatus.PENDING, created, expires);
    }
}
