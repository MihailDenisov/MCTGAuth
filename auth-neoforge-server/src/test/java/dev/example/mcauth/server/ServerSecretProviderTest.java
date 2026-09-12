package dev.example.mcauth.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSecretProviderTest {
    private static final String CONFIG_TOKEN = "config-token-01234567890123456789";
    private static final String ENV_TOKEN = "environment-token-012345678901234";

    @Test
    void configTokenWorksWithoutEnvironmentVariable() {
        char[] token = ServerSecretProvider.apiToken(CONFIG_TOKEN, ignored -> null).orElseThrow();
        try {
            assertArrayEquals(CONFIG_TOKEN.toCharArray(), token);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    @Test
    void environmentVariableOverridesConfigAndBlankOverrideFallsBack() {
        char[] overridden = ServerSecretProvider.apiToken(CONFIG_TOKEN,
                name -> Map.of("MCTGAUTH_API_TOKEN", ENV_TOKEN).get(name)).orElseThrow();
        char[] fallback = ServerSecretProvider.apiToken(CONFIG_TOKEN, ignored -> "  ").orElseThrow();
        try {
            assertArrayEquals(ENV_TOKEN.toCharArray(), overridden);
            assertArrayEquals(CONFIG_TOKEN.toCharArray(), fallback);
        } finally {
            Arrays.fill(overridden, '\0');
            Arrays.fill(fallback, '\0');
        }
    }

    @Test
    void missingTokenDisablesSynchronizationWithoutProducingASecret() {
        assertTrue(ServerSecretProvider.apiToken("", ignored -> null).isEmpty());
        assertTrue(ServerSecretProvider.apiToken("  ", ignored -> null).isEmpty());
    }

    @Test
    void invalidTokenIsRejectedWithoutEchoingItsValue() {
        String invalid = "short secret";
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ServerSecretProvider.apiToken(invalid, ignored -> null));
        assertFalse(failure.getMessage().contains(invalid));
    }
}
