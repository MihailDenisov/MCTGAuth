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
            sendDecision(player, runtime.sessions().verify(player.getUUID(), payload));
        } catch (RuntimeException exception) {
            rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, exception);
        }
    }

    static void sendDecision(ServerPlayer player, LoginSessionManager.Decision decision) {
        if (decision.authenticated()) {
            LOGGER.info("[MCTGAuth] Authentication successful: player={} uuid={} credentialVersion={}",
                    player.getGameProfile().getName(), player.getUUID(), decision.credentialVersion());
            PacketDistributor.sendToPlayer(player, new AuthSuccessPayload(decision.sessionId()));
            return;
        }
        if (decision.failure() != null) {
            PacketDistributor.sendToPlayer(player, new AuthFailurePayload(decision.sessionId(), decision.failure(),
                    decision.retryAllowed(), decision.remainingAttempts()));
            if (!decision.retryAllowed()) {
                rejectAndDisconnect(player, decision.failure(), null);
                return;
            }
            logRejection(player, decision.failure(), null);
        }
        if (decision.challenge() != null) PacketDistributor.sendToPlayer(player, decision.challenge());
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
            case ACCOUNT_NOT_SYNCHRONIZED -> "mctgauth.error.account_not_synchronized";
            case INTERNAL_AUTH_ERROR -> "mctgauth.error.internal";
        };
    }
}
