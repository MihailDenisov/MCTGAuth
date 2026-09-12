package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ServerHelloPayload(int protocolVersion, String serverModVersion, long requiredFeatureBits)
        implements CustomPacketPayload {
    public static final Type<ServerHelloPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "server_hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerHelloPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ServerHelloPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new ServerHelloPayload(buffer.readVarInt(),
                            buffer.readUtf(AuthProtocol.MAX_MOD_VERSION_CHARS), buffer.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ServerHelloPayload value) {
                    buffer.writeVarInt(value.protocolVersion);
                    buffer.writeUtf(value.serverModVersion, AuthProtocol.MAX_MOD_VERSION_CHARS);
                    buffer.writeVarLong(value.requiredFeatureBits);
                }
            };

    public ServerHelloPayload {
        PayloadCodecs.requireVersion(protocolVersion);
        Objects.requireNonNull(serverModVersion, "serverModVersion");
        if (serverModVersion.isBlank() || serverModVersion.length() > AuthProtocol.MAX_MOD_VERSION_CHARS
                || requiredFeatureBits < 0) throw new IllegalArgumentException("Invalid server hello");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
