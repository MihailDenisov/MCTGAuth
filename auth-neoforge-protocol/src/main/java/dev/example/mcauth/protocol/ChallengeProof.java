package dev.example.mcauth.protocol;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

public final class ChallengeProof {
    private ChallengeProof() {
    }

    public static byte[] deriveKey(char[] password, Argon2Spec spec) {
        if (password == null || password.length == 0 || password.length > 256) {
            throw new IllegalArgumentException("Invalid password length");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);
        byte[] output = new byte[spec.hashLength()];
        byte[] salt = spec.salt();
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(spec.memoryKiB())
                    .withIterations(spec.iterations())
                    .withParallelism(spec.parallelism())
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(passwordBytes, output);
            return output;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    public static byte[] create(byte[] key, int protocolVersion, byte[] nonce, UUID sessionId,
                                String normalizedIdentity, int credentialVersion) {
        if (key == null || key.length < Argon2Spec.MIN_HASH_LENGTH
                || nonce == null || nonce.length != AuthProtocol.NONCE_BYTES
                || sessionId == null || credentialVersion <= 0) {
            throw new IllegalArgumentException("Invalid proof context");
        }
        String identity = AuthProtocol.normalizeMinecraftName(normalizedIdentity);
        byte[] context = context(protocolVersion, nonce, sessionId, identity, credentialVersion);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(context);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        } finally {
            Arrays.fill(context, (byte) 0);
        }
    }

    public static boolean verify(byte[] expectedKey, byte[] submittedProof, int protocolVersion,
                                 byte[] nonce, UUID sessionId, String normalizedIdentity,
                                 int credentialVersion) {
        byte[] expected = create(expectedKey, protocolVersion, nonce, sessionId,
                normalizedIdentity, credentialVersion);
        try {
            return submittedProof != null && MessageDigest.isEqual(expected, submittedProof);
        } finally {
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static byte[] context(int protocolVersion, byte[] nonce, UUID sessionId,
                                  String identity, int credentialVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(protocolVersion);
                output.writeInt(nonce.length);
                output.write(nonce);
                output.writeLong(sessionId.getMostSignificantBits());
                output.writeLong(sessionId.getLeastSignificantBits());
                byte[] name = identity.getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.writeInt(credentialVersion);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
