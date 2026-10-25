package dev.example.mcauth.server;

import dev.example.mcauth.protocol.AuthFailurePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.AuthSuccessPayload;
import dev.example.mcauth.protocol.ClientHelloPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuthPayloadHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthPayloadHandler.class);

    private AuthPayloadHandler() {
    }

    static void clientHello(ClientHelloPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            context.disconnect(Component.translatable("mctgauth.error.internal"));
            return;
        }
        AuthServerMod runtime = AuthServerMod.instance();
        if (runtime == null || !runtime.ready()) {
            rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, null);
            return;
        }
        LoginSessionManager.Decision decision;
        try {
            decision = runtime.sessions().hello(player.getUUID(), payload);
        } catch (RuntimeException exception) {
            rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, exception);
            return;
        }
        sendDecision(player, decision);
    }

    static void authResponse(AuthResponsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            context.disconnect(Component.translatable("mctgauth.error.internal"));
            return;
        }
        AuthServerMod runtime = AuthServerMod.instance();
        if (runtime == null || !runtime.ready()) {
            rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, null);
            return;
        }
        try {
            LoginSessionManager.Decision decision = runtime.sessions().verify(player.getUUID(), payload);
            if (decision.deviceCheckRequired()) {
                runtime.evaluateDevice(player, decision, payload.deviceFingerprint());
            } else {
                sendDecision(player, decision);
            }
        } catch (RuntimeException exception) {
            rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, exception);
        }
    }

    static void sendDecision(ServerPlayer player, LoginSessionManager.Decision decision) {
        if (decision.authenticated()) {
            sendAuthenticated(player, decision.sessionId(), decision.credentialVersion());
            return;
        }
        if (decision.failure() != null) {
            if (!decision.retryAllowed()) {
                // Protocol 1 ordinal compatibility: newly precise terminal reasons travel in the
                // disconnect Component, not in auth_failure, whose legacy ordinal set must stay stable.
                rejectAndDisconnect(player, decision.failure(), null);
                return;
            }
            PacketDistributor.sendToPlayer(player, new AuthFailurePayload(decision.sessionId(), decision.failure(),
                    true, decision.remainingAttempts()));
            logRejection(player, decision.failure(), null);
        }
        if (decision.challenge() != null) PacketDistributor.sendToPlayer(player, decision.challenge());
    }

    static void sendAuthenticated(ServerPlayer player, java.util.UUID sessionId, int credentialVersion) {
        LOGGER.info("[MCTGAuth] Authentication successful: player={} uuid={} credentialVersion={}",
                player.getGameProfile().getName(), player.getUUID(), credentialVersion);
        PacketDistributor.sendToPlayer(player, new AuthSuccessPayload(sessionId));
        AuthServerMod runtime = AuthServerMod.instance();
        if (runtime != null) runtime.playerAuthenticated(player);
    }

    static void rejectAndDisconnect(ServerPlayer player,
                                    AuthFailureReason reason,
                                    RuntimeException exception) {
        if (!claimTerminalDisconnect(player)) return;
        logRejection(player, reason, exception);
        logAndDisconnect(player, reason);
    }

    static void disconnectPlayer(ServerPlayer player, AuthFailureReason reason) {
        if (!claimTerminalDisconnect(player)) return;
        logAndDisconnect(player, reason);
    }

    private static void logAndDisconnect(ServerPlayer player,
                                         AuthFailureReason reason) {
        LOGGER.warn("[MCTGAuth] Disconnecting player={} reason={}",
                player.getGameProfile().getName(), reason.name());
        player.connection.disconnect(Component.translatable(translationKey(reason)));
    }

    private static boolean claimTerminalDisconnect(ServerPlayer player) {
        AuthServerMod runtime = AuthServerMod.instance();
        return runtime == null || runtime.claimTerminalDisconnect(player.getUUID());
    }

    private static void logRejection(ServerPlayer player,
                                     AuthFailureReason reason,
                                     RuntimeException exception) {
        if (reason == AuthFailureReason.INTERNAL_AUTH_ERROR) {
            String errorType = exception == null ? "unavailable" : exception.getClass().getSimpleName();
            LOGGER.error("[MCTGAuth] Authentication rejected: player={} uuid={} reason={} errorType={}",
                    player.getGameProfile().getName(), player.getUUID(), reason.name(), errorType);
        } else {
            LOGGER.warn("[MCTGAuth] Authentication rejected: player={} uuid={} reason={}",
                    player.getGameProfile().getName(), player.getUUID(), reason.name());
        }
    }

    static String translationKey(AuthFailureReason reason) {
        return switch (reason) {
            case INVALID_CREDENTIALS, TOO_MANY_ATTEMPTS -> "mctgauth.error.invalid_credentials";
            case AUTH_TIMEOUT -> "mctgauth.error.timeout";
            case PROTOCOL_MISMATCH -> "mctgauth.error.protocol_mismatch";
            case CLIENT_MISSING -> "mctgauth.error.mod_required";
            case ACCOUNT_DISABLED -> "mctgauth.error.account_disabled";
            case DEVICE_BANNED -> "mctgauth.error.device_banned";
            case DEVICE_VERIFICATION_UNAVAILABLE -> "mctgauth.error.device_verification_unavailable";
            case ACCOUNT_NOT_FOUND -> "mctgauth.error.account_not_found";
            case ACCOUNT_NOT_SYNCHRONIZED -> "mctgauth.error.account_not_synchronized";
            case CREDENTIAL_OUTDATED -> "mctgauth.error.credential_outdated";
            case INVALID_CHALLENGE, REPLAY_DETECTED, INVALID_SESSION -> "mctgauth.error.verification_failed";
            case INTERNAL_AUTH_ERROR -> "mctgauth.error.internal";
        };
    }
}
