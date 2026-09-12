package dev.example.mcauth.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFailureReasonTest {
    @Test
    void stableFailureCodesCoverEveryTerminalOutcome() {
        assertArrayEquals(new String[] {
                "INVALID_CREDENTIALS",
                "AUTH_TIMEOUT",
                "TOO_MANY_ATTEMPTS",
                "PROTOCOL_MISMATCH",
                "CLIENT_MISSING",
                "ACCOUNT_DISABLED",
                "ACCOUNT_NOT_SYNCHRONIZED",
                "INTERNAL_AUTH_ERROR"
        }, Arrays.stream(AuthFailureReason.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void payloadDistinguishesSameConnectionRetryFromTerminalFailure() {
        assertFalse(new AuthFailurePayload(null, AuthFailureReason.INVALID_CREDENTIALS, true, 2).terminal());
        assertTrue(new AuthFailurePayload(null, AuthFailureReason.ACCOUNT_NOT_SYNCHRONIZED, false, 0).terminal());
    }
}
