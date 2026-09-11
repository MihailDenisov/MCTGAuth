package dev.example.mcauth.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

final class ApiTokenAuthenticator {
    private static final String PREFIX = "Bearer ";
    private final byte[] expectedToken;

    ApiTokenAuthenticator(String token) {
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] actual = authorizationHeader.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.isEqual(expectedToken, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }
}
