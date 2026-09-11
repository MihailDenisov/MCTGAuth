package dev.example.mcauth.backend;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class CredentialService {
    private static final int MEMORY_KIB = 65_536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PASSWORD_RANDOM_BYTES = 18;

    private final SecureRandom secureRandom;

    public CredentialService() {
        this(new SecureRandom());
    }

    CredentialService(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public char[] generatePassword() {
        byte[] random = new byte[PASSWORD_RANDOM_BYTES];
        secureRandom.nextBytes(random);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        try {
            return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                    + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt)
                    + "$" + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
        }
    }

    public boolean verify(char[] password, String encodedVerifier) {
        try {
            String[] parts = encodedVerifier.split("\\$");
            if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
                return false;
            }
            String[] parameters = parts[3].split(",");
            int memory = Integer.parseInt(parameters[0].substring(2));
            int iterations = Integer.parseInt(parameters[1].substring(2));
            int parallelism = Integer.parseInt(parameters[2].substring(2));
            if (memory != MEMORY_KIB || iterations != ITERATIONS || parallelism != PARALLELISM) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            byte[] actual = derive(password, salt, memory, iterations, parallelism);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                Arrays.fill(actual, (byte) 0);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);
        byte[] output = new byte[HASH_BYTES];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memory)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(passwordBytes, output);
            return output;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }
}
