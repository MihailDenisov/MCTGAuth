package dev.example.mcauth.protocol;

import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Argon2Verifier implements AutoCloseable {
    private static final Pattern PHC = Pattern.compile(
            "^\\$argon2id\\$v=19\\$m=([1-9][0-9]*),t=([1-9][0-9]*),p=([1-9][0-9]*)\\$([A-Za-z0-9+/]+={0,2})\\$([A-Za-z0-9+/]+={0,2})$");

    private final Argon2Spec spec;
    private final byte[] derivedKey;

    private Argon2Verifier(Argon2Spec spec, byte[] derivedKey) {
        this.spec = spec;
        this.derivedKey = derivedKey.clone();
    }

    public static Argon2Verifier parse(String encoded) {
        if (encoded == null || encoded.length() > 512) {
            throw new IllegalArgumentException("Invalid Argon2id verifier");
        }
        Matcher matcher = PHC.matcher(encoded);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Argon2id verifier");
        }
        try {
            int memory = Integer.parseInt(matcher.group(1));
            int iterations = Integer.parseInt(matcher.group(2));
            int parallelism = Integer.parseInt(matcher.group(3));
            byte[] salt = Base64.getDecoder().decode(matcher.group(4));
            byte[] key = Base64.getDecoder().decode(matcher.group(5));
            try {
                return new Argon2Verifier(new Argon2Spec(memory, iterations, parallelism, key.length, salt), key);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(key, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Argon2id verifier", exception);
        }
    }

    public Argon2Spec spec() {
        return new Argon2Spec(spec.memoryKiB(), spec.iterations(), spec.parallelism(),
                spec.hashLength(), spec.salt());
    }

    public byte[] derivedKey() {
        return derivedKey.clone();
    }

    @Override
    public void close() {
        spec.clear();
        Arrays.fill(derivedKey, (byte) 0);
    }
}
