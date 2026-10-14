package dev.example.mcauth.server;

import dev.example.mcauth.protocol.AuthFailureReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFailureMessageTest {
    @Test
    void everyFailureReasonHasOneCentralTranslationKey() {
        assertTrue(Arrays.stream(AuthFailureReason.values())
                .allMatch(reason -> AuthPayloadHandler.translationKey(reason).startsWith("mctgauth.error.")));
        assertEquals("mctgauth.error.account_not_found",
                AuthPayloadHandler.translationKey(AuthFailureReason.ACCOUNT_NOT_FOUND));
        assertEquals("mctgauth.error.account_not_synchronized",
                AuthPayloadHandler.translationKey(AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED));
        assertEquals("mctgauth.error.credential_outdated",
                AuthPayloadHandler.translationKey(AuthFailureReason.CREDENTIAL_OUTDATED));
        assertEquals("mctgauth.error.verification_failed",
                AuthPayloadHandler.translationKey(AuthFailureReason.REPLAY_DETECTED));
    }
}
