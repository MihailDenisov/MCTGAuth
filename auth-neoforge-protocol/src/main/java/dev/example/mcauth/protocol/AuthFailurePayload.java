package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record AuthFailurePayload(UUID sessionId, AuthFailureReason reason, boolean retryAllowed,
                                 int remainingAttempts) implements CustomPacketPayload {
    public static final Type<AuthFailurePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_failure"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthFailurePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AuthFailurePayload decode(RegistryFriendlyByteBuf buffer) {
                    UUID sessionId = buffer.readBoolean() ? buffer.readUUID() : null;
                    int ordinal = buffer.readVarInt();
                    if (ordinal < 0 || ordinal >= AuthFailureReason.values().length) {
                        throw new IllegalArgumentException("Invalid failure reason");
                    }
                    return new AuthFailurePayload(sessionId, AuthFailureReason.values()[ordinal],
                            buffer.readBoolean(), buffer.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AuthFailurePayload value) {
                    buffer.writeBoolean(value.sessionId != null);
                    if (value.sessionId != null) buffer.writeUUID(value.sessionId);
                    buffer.writeVarInt(value.reason.ordinal());
                    buffer.writeBoolean(value.retryAllowed);
                    buffer.writeVarInt(value.remainingAttempts);
                }
            };

    public AuthFailurePayload {
        if (reason == null || remainingAttempts < 0 || remainingAttempts > 100
                || (retryAllowed && remainingAttempts == 0)) {
            throw new IllegalArgumentException("Invalid authentication failure");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public boolean terminal() {
        return !retryAllowed;
    }
}
