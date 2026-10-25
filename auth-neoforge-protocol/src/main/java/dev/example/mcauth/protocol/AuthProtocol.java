package dev.example.mcauth.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AuthProtocol {
    public static final int AUTH_PROTOCOL_VERSION = 2;
    /** Stable NeoForge channel bootstrap; wire compatibility is negotiated by AUTH_PROTOCOL_VERSION. */
    public static final String NETWORK_VERSION = "bootstrap-1";
    public static final String NAMESPACE = "mctgauth";
    public static final String CLIENT_MOD_ID = "mctgauth_client";
    public static final String SERVER_MOD_ID = "mctgauth_server";
    public static final String MOD_VERSION = "0.6.5";
    public static final long FEATURE_AUTH_CHALLENGE_V1 = 1L;
    public static final long FEATURE_LOCALIZED_FAILURES = 1L << 1;
    public static final long FEATURE_DEVICE_FINGERPRINT_V1 = 1L << 2;
    public static final long REQUIRED_FEATURES = FEATURE_AUTH_CHALLENGE_V1 | FEATURE_LOCALIZED_FAILURES
            | FEATURE_DEVICE_FINGERPRINT_V1;
    public static final int PROOF_BYTES = 32;
    public static final int NONCE_BYTES = 32;
    public static final int MAX_MOD_VERSION_CHARS = 64;
    public static final int FINGERPRINT_VERSION = 1;
    public static final int DEVICE_SIGNAL_BYTES = 32;
    public static final int MAX_DEVICE_SIGNALS = 6;
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private AuthProtocol() {
    }

    public static String normalizeMinecraftName(String name) {
        if (name == null || !MINECRAFT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Minecraft account name");
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
