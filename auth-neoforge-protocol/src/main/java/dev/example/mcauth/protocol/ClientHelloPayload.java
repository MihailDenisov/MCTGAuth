package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ClientHelloPayload(int protocolVersion, String clientModVersion, long featureBits)
        implements CustomPacketPayload {
    public static final Type<ClientHelloPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "client_hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientHelloPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ClientHelloPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new ClientHelloPayload(buffer.readVarInt(),
                            buffer.readUtf(AuthProtocol.MAX_MOD_VERSION_CHARS), buffer.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ClientHelloPayload value) {
                    buffer.writeVarInt(value.protocolVersion);
                    buffer.writeUtf(value.clientModVersion, AuthProtocol.MAX_MOD_VERSION_CHARS);
                    buffer.writeVarLong(value.featureBits);
                }
            };

    public ClientHelloPayload {
        PayloadCodecs.requireVersion(protocolVersion);
        Objects.requireNonNull(clientModVersion, "clientModVersion");
        if (clientModVersion.isBlank() || clientModVersion.length() > AuthProtocol.MAX_MOD_VERSION_CHARS
                || featureBits < 0) throw new IllegalArgumentException("Invalid client hello");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
