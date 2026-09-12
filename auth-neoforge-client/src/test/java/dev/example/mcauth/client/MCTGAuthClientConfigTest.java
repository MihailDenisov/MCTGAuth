package dev.example.mcauth.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCTGAuthClientConfigTest {
    @TempDir Path temporary;

    @Test
    void passwordAndAutomaticAuthenticationPersistAcrossReloadsAndCanBeCleared() {
        Path path = temporary.resolve("mctgauth-client.json");
        MCTGAuthClientConfig config = new MCTGAuthClientConfig(path);
        config.load();
        assertEquals("", config.snapshot().password());
        assertTrue(config.snapshot().autoAuthenticate());

        config.save("fixture-password", false);
        MCTGAuthClientConfig reloaded = new MCTGAuthClientConfig(path);
        reloaded.load();
        assertEquals("fixture-password", reloaded.snapshot().password());
        assertFalse(reloaded.snapshot().autoAuthenticate());

        reloaded.save("replacement-password", true);
        MCTGAuthClientConfig changed = new MCTGAuthClientConfig(path);
        changed.load();
        assertEquals("replacement-password", changed.snapshot().password());
        assertTrue(changed.snapshot().autoAuthenticate());

        changed.save("", true);
        MCTGAuthClientConfig cleared = new MCTGAuthClientConfig(path);
        cleared.load();
        assertEquals("", cleared.snapshot().password());
    }
}
