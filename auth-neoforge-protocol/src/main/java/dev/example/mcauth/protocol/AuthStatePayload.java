package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AuthStatePayload(PlayerAuthState state) implements CustomPacketPayload {
    public static final Type<AuthStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthStatePayload> STREAM_CODEC =
            StreamCodec.of((buffer, value) -> buffer.writeVarInt(value.state.ordinal()), buffer -> {
                int ordinal = buffer.readVarInt();
                if (ordinal < 0 || ordinal >= PlayerAuthState.values().length) {
                    throw new IllegalArgumentException("Invalid authentication state");
                }
                return new AuthStatePayload(PlayerAuthState.values()[ordinal]);
            });

    public AuthStatePayload {
        if (state == null) throw new IllegalArgumentException("Missing state");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
