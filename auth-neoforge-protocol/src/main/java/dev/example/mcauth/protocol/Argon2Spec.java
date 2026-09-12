package dev.example.mcauth.protocol;

import java.util.Arrays;

public record Argon2Spec(int memoryKiB, int iterations, int parallelism, int hashLength, byte[] salt) {
    public static final int MIN_MEMORY_KIB = 8 * 1024;
    public static final int MAX_MEMORY_KIB = 256 * 1024;
    public static final int MAX_ITERATIONS = 10;
    public static final int MAX_PARALLELISM = 8;
    public static final int MIN_HASH_LENGTH = 16;
    public static final int MAX_HASH_LENGTH = 64;
    public static final int MIN_SALT_LENGTH = 8;
    public static final int MAX_SALT_LENGTH = 64;

    public Argon2Spec {
        if (memoryKiB < MIN_MEMORY_KIB || memoryKiB > MAX_MEMORY_KIB
                || iterations < 1 || iterations > MAX_ITERATIONS
                || parallelism < 1 || parallelism > MAX_PARALLELISM
                || hashLength < MIN_HASH_LENGTH || hashLength > MAX_HASH_LENGTH
                || salt == null || salt.length < MIN_SALT_LENGTH || salt.length > MAX_SALT_LENGTH) {
            throw new IllegalArgumentException("Unsafe Argon2id parameters");
        }
        salt = salt.clone();
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    public void clear() {
        Arrays.fill(salt, (byte) 0);
    }
}
