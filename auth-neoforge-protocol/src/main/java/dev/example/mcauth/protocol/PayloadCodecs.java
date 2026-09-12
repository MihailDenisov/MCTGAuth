package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;

final class PayloadCodecs {
    private PayloadCodecs() {
    }

    static void writeBytes(RegistryFriendlyByteBuf buffer, byte[] value, int expectedLength) {
        if (value.length != expectedLength) throw new IllegalArgumentException("Invalid byte array length");
        buffer.writeByteArray(value);
    }

    static byte[] readBytes(RegistryFriendlyByteBuf buffer, int expectedLength) {
        byte[] value = buffer.readByteArray(expectedLength);
        if (value.length != expectedLength) throw new IllegalArgumentException("Invalid byte array length");
        return value;
    }

    static void requireVersion(int protocolVersion) {
        if (protocolVersion <= 0 || protocolVersion > 10_000) {
            throw new IllegalArgumentException("Invalid protocol version");
        }
    }
}
