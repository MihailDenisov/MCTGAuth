package dev.example.mcauth.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeProofTest {
    @Test
    void backendCompatibleVerifierProducesValidBoundProof() {
        char[] password = "phase-four-test-password".toCharArray();
        byte[] salt = "fixed-test-salt!".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[AuthProtocol.NONCE_BYTES];
        Arrays.fill(nonce, (byte) 0x5a);
        Argon2Spec spec = new Argon2Spec(65_536, 3, 1, 32, salt);
        byte[] key = ChallengeProof.deriveKey(password, spec);
        String phc = "$argon2id$v=19$m=65536,t=3,p=1$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(key);
        UUID session = UUID.fromString("00000000-0000-4000-8000-000000000001");
        try (Argon2Verifier parsed = Argon2Verifier.parse(phc)) {
            byte[] serverKey = parsed.derivedKey();
            byte[] proof = ChallengeProof.create(key, 1, nonce, session, "test_player", 7);
            try {
                assertTrue(ChallengeProof.verify(serverKey, proof, 1, nonce, session, "test_player", 7));
                assertFalse(ChallengeProof.verify(serverKey, proof, 1, nonce,
                        UUID.fromString("00000000-0000-4000-8000-000000000002"), "test_player", 7));
                assertFalse(ChallengeProof.verify(serverKey, proof, 1, nonce, session, "other_player", 7));
                assertFalse(ChallengeProof.verify(serverKey, proof, 1, nonce, session, "test_player", 8));
                byte[] modified = proof.clone();
                modified[0] ^= 1;
                assertFalse(MessageDigest.isEqual(proof, modified));
                assertFalse(ChallengeProof.verify(serverKey, modified, 1, nonce, session, "test_player", 7));
            } finally {
                Arrays.fill(serverKey, (byte) 0);
                Arrays.fill(proof, (byte) 0);
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    @Test
    void rejectsMalformedVerifierAndUnsafeParameters() {
        assertThrows(IllegalArgumentException.class, () -> Argon2Verifier.parse("malformed"));
        assertThrows(IllegalArgumentException.class,
                () -> Argon2Verifier.parse("$argon2id$v=19$m=9999999,t=3,p=1$c2FsdHNhbHQ$aGFzaGhhc2hoYXNoaGFzaA"));
        assertThrows(IllegalArgumentException.class, () -> new Argon2Spec(1024, 3, 1, 32, new byte[16]));
    }
}
