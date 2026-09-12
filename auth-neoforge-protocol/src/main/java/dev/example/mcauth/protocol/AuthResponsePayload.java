package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record AuthResponsePayload(UUID sessionId, int credentialVersion, byte[] proof)
        implements CustomPacketPayload {
    public static final Type<AuthResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthResponsePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AuthResponsePayload decode(RegistryFriendlyByteBuf buffer) {
                    return new AuthResponsePayload(buffer.readUUID(), buffer.readVarInt(),
                            PayloadCodecs.readBytes(buffer, AuthProtocol.PROOF_BYTES));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AuthResponsePayload value) {
                    buffer.writeUUID(value.sessionId);
                    buffer.writeVarInt(value.credentialVersion);
                    PayloadCodecs.writeBytes(buffer, value.proof, AuthProtocol.PROOF_BYTES);
                }
            };

    public AuthResponsePayload {
        if (sessionId == null || credentialVersion <= 0 || proof == null
                || proof.length != AuthProtocol.PROOF_BYTES) {
            throw new IllegalArgumentException("Invalid authentication response");
        }
        proof = proof.clone();
    }

    @Override
    public byte[] proof() {
        return proof.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
