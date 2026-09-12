package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record AuthChallengePayload(UUID sessionId, int credentialVersion, Argon2Spec argon2,
                                   byte[] nonce, int expiresInSeconds) implements CustomPacketPayload {
    public static final Type<AuthChallengePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_challenge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthChallengePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AuthChallengePayload decode(RegistryFriendlyByteBuf buffer) {
                    UUID sessionId = buffer.readUUID();
                    int credentialVersion = buffer.readVarInt();
                    int memory = buffer.readVarInt();
                    int iterations = buffer.readVarInt();
                    int parallelism = buffer.readVarInt();
                    int hashLength = buffer.readVarInt();
                    byte[] salt = buffer.readByteArray(Argon2Spec.MAX_SALT_LENGTH);
                    byte[] nonce = PayloadCodecs.readBytes(buffer, AuthProtocol.NONCE_BYTES);
                    return new AuthChallengePayload(sessionId, credentialVersion,
                            new Argon2Spec(memory, iterations, parallelism, hashLength, salt),
                            nonce, buffer.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AuthChallengePayload value) {
                    buffer.writeUUID(value.sessionId);
                    buffer.writeVarInt(value.credentialVersion);
                    buffer.writeVarInt(value.argon2.memoryKiB());
                    buffer.writeVarInt(value.argon2.iterations());
                    buffer.writeVarInt(value.argon2.parallelism());
                    buffer.writeVarInt(value.argon2.hashLength());
                    buffer.writeByteArray(value.argon2.salt());
                    PayloadCodecs.writeBytes(buffer, value.nonce, AuthProtocol.NONCE_BYTES);
                    buffer.writeVarInt(value.expiresInSeconds);
                }
            };

    public AuthChallengePayload {
        if (sessionId == null || credentialVersion <= 0 || argon2 == null || nonce == null
                || nonce.length != AuthProtocol.NONCE_BYTES || expiresInSeconds < 1 || expiresInSeconds > 300) {
            throw new IllegalArgumentException("Invalid authentication challenge");
        }
        nonce = nonce.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
