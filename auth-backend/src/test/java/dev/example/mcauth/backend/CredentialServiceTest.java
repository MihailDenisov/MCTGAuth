package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialServiceTest {
    private final CredentialService credentials = new CredentialService();

    @Test
    void hashesWithArgon2idAndRejectsInvalidCredentials() {
        char[] password = "correct-horse-battery-staple".toCharArray();
        char[] wrongPassword = "wrong-password".toCharArray();
        try {
            String verifier = credentials.hash(password);

            assertTrue(verifier.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));
            assertNotEquals(new String(password), verifier);
            assertTrue(credentials.verify(password, verifier));
            assertFalse(credentials.verify(wrongPassword, verifier));
            assertFalse(credentials.verify(password, "malformed"));
            assertFalse(credentials.verify(password, verifier.replace("m=65536", "m=32768")));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(wrongPassword, '\0');
        }
    }
}
