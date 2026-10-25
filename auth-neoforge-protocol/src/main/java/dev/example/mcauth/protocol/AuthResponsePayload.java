package dev.example.mcauth.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record AuthResponsePayload(UUID sessionId, int credentialVersion, byte[] proof,
                                  DeviceFingerprint deviceFingerprint)
        implements CustomPacketPayload {
    public static final Type<AuthResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AuthProtocol.NAMESPACE, "auth_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthResponsePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public AuthResponsePayload decode(RegistryFriendlyByteBuf buffer) {
                    UUID sessionId = buffer.readUUID();
                    int credentialVersion = buffer.readVarInt();
                    byte[] proof = PayloadCodecs.readBytes(buffer, AuthProtocol.PROOF_BYTES);
                    int fingerprintVersion = buffer.readVarInt();
                    int count = buffer.readVarInt();
                    if (count < 0 || count > AuthProtocol.MAX_DEVICE_SIGNALS) {
                        throw new IllegalArgumentException("Invalid device signal count");
                    }
                    List<DeviceSignal> signals = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        int ordinal = buffer.readVarInt();
                        DeviceSignalType[] types = DeviceSignalType.values();
                        if (ordinal < 0 || ordinal >= types.length) {
                            throw new IllegalArgumentException("Unknown device signal type");
                        }
                        signals.add(new DeviceSignal(types[ordinal],
                                PayloadCodecs.readBytes(buffer, AuthProtocol.DEVICE_SIGNAL_BYTES)));
                    }
                    return new AuthResponsePayload(sessionId, credentialVersion, proof,
                            new DeviceFingerprint(fingerprintVersion, signals));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AuthResponsePayload value) {
                    buffer.writeUUID(value.sessionId);
                    buffer.writeVarInt(value.credentialVersion);
                    PayloadCodecs.writeBytes(buffer, value.proof, AuthProtocol.PROOF_BYTES);
                    buffer.writeVarInt(value.deviceFingerprint.fingerprintVersion());
                    buffer.writeVarInt(value.deviceFingerprint.signals().size());
                    for (DeviceSignal signal : value.deviceFingerprint.signals()) {
                        buffer.writeVarInt(signal.type().ordinal());
                        PayloadCodecs.writeBytes(buffer, signal.digest(), AuthProtocol.DEVICE_SIGNAL_BYTES);
                    }
                }
            };

    public AuthResponsePayload {
        if (sessionId == null || credentialVersion <= 0 || proof == null
                || proof.length != AuthProtocol.PROOF_BYTES || deviceFingerprint == null) {
            throw new IllegalArgumentException("Invalid authentication response");
        }
        proof = proof.clone();
    }

    public AuthResponsePayload(UUID sessionId, int credentialVersion, byte[] proof) {
        this(sessionId, credentialVersion, proof,
                new DeviceFingerprint(AuthProtocol.FINGERPRINT_VERSION, List.of()));
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
