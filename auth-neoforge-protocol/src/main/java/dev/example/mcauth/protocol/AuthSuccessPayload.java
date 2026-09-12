package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record AuthSuccessPayload(UUID sessionId) implements CustomPacketPayload {
    public static final Type<AuthSuccessPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_success"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthSuccessPayload> STREAM_CODEC =
            StreamCodec.of((buffer, value) -> buffer.writeUUID(value.sessionId),
                    buffer -> new AuthSuccessPayload(buffer.readUUID()));

    public AuthSuccessPayload {
        if (sessionId == null) throw new IllegalArgumentException("Missing session ID");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
