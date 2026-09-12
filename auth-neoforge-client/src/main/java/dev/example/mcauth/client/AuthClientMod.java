package dev.example.mcauth.client;

import dev.example.mcauth.protocol.AuthChallengePayload;
import dev.example.mcauth.protocol.AuthFailurePayload;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.AuthStatePayload;
import dev.example.mcauth.protocol.AuthSuccessPayload;
import dev.example.mcauth.protocol.ClientHelloPayload;
import dev.example.mcauth.protocol.ServerHelloPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(AuthProtocol.CLIENT_MOD_ID)
public final class AuthClientMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthClientMod.class);
    private final AuthNetworkClient controller;
    private final MCTGAuthClientConfig config;
    private volatile boolean storedPasswordRejected;

    public AuthClientMod(IEventBus modBus, ModContainer container) {
        config = new MCTGAuthClientConfig(FMLPaths.CONFIGDIR.get().resolve(MCTGAuthClientConfig.FILE_NAME));
        config.load();
        controller = new AuthNetworkClient(this::scheduleAuthResponse);
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (ignored, parent) -> new MCTGAuthSettingsScreen(parent, config));
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::titleScreen);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Client support is optional until a server explicitly advertises MCTGAuth.
        var registrar = event.registrar(AuthProtocol.NETWORK_VERSION).optional();
        registrar.playToServer(ClientHelloPayload.TYPE, ClientHelloPayload.STREAM_CODEC,
                (payload, context) -> context.disconnect(Component.translatable("mctgauth.error.internal")));
        registrar.playToServer(AuthResponsePayload.TYPE, AuthResponsePayload.STREAM_CODEC,
                (payload, context) -> context.disconnect(Component.translatable("mctgauth.error.internal")));
        registrar.playToClient(ServerHelloPayload.TYPE, ServerHelloPayload.STREAM_CODEC, this::serverHello);
        registrar.playToClient(AuthChallengePayload.TYPE, AuthChallengePayload.STREAM_CODEC, this::challenge);
        registrar.playToClient(AuthSuccessPayload.TYPE, AuthSuccessPayload.STREAM_CODEC, this::success);
        registrar.playToClient(AuthFailurePayload.TYPE, AuthFailurePayload.STREAM_CODEC, this::failure);
        registrar.playToClient(AuthStatePayload.TYPE, AuthStatePayload.STREAM_CODEC, (payload, context) -> { });
    }

    private void scheduleAuthResponse(AuthResponsePayload payload, long generation) {
        Minecraft.getInstance().execute(() -> {
            if (controller.maySendForGeneration(generation)) {
                PacketDistributor.sendToServer(payload);
            } else {
                LOGGER.debug("[MCTGAuth] Ignored stale authentication response: generation={}", generation);
            }
        });
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        storedPasswordRejected = false;
        boolean integratedServer = MctgAuthConnectionClassifier.isIntegratedServer(event);
        controller.connected(event.getPlayer().getGameProfile().getName(), !integratedServer);
        LOGGER.debug("[MCTGAuth] Connection opened: generation={} mode={}",
                controller.connectionGeneration(), controller.state());
        if (integratedServer) {
            LOGGER.debug("[MCTGAuth] Integrated server detected; MCTGAuth authentication is not applicable");
        } else {
            LOGGER.debug("[MCTGAuth] No MCTGAuth capability detected; authentication remains inactive");
        }
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        storedPasswordRejected = false;
        ClientAuthState previousState = controller.state();
        controller.disconnected();
        LOGGER.debug("[MCTGAuth] Connection closed: generation={} previousMode={}",
                controller.connectionGeneration(), previousState);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MCTGAuthSettingsScreen) minecraft.setScreen(null);
    }

    private void serverHello(ServerHelloPayload payload, IPayloadContext context) {
        if (!controller.mayActivate()) {
            LOGGER.debug("[MCTGAuth] Ignored server capability outside a passive applicable connection: generation={} mode={}",
                    controller.connectionGeneration(), controller.state());
            return;
        }
        LOGGER.debug("[MCTGAuth] Server capability detected: protocol={} generation={}",
                payload.protocolVersion(), controller.connectionGeneration());
        controller.activate();
        if (payload.protocolVersion() != AuthProtocol.AUTH_PROTOCOL_VERSION
                || (AuthProtocol.REQUIRED_FEATURES & payload.requiredFeatureBits()) != payload.requiredFeatureBits()) {
            recordFailure(new AuthFailurePayload(null,
                    AuthFailureReason.PROTOCOL_MISMATCH, false, 0));
            context.disconnect(Component.translatable("mctgauth.error.protocol_mismatch"));
            return;
        }
        LOGGER.debug("[MCTGAuth] Authentication activated: generation={}", controller.connectionGeneration());
        PacketDistributor.sendToServer(new ClientHelloPayload(AuthProtocol.AUTH_PROTOCOL_VERSION,
                AuthProtocol.MOD_VERSION, AuthProtocol.REQUIRED_FEATURES));
    }

    private void challenge(AuthChallengePayload payload, IPayloadContext context) {
        MCTGAuthClientConfig.Snapshot saved = config.snapshot();
        AuthNetworkClient.ChallengeDisposition disposition = !storedPasswordRejected
                ? controller.handleChallenge(payload, saved.password(), saved.autoAuthenticate())
                : controller.challenge(payload) ? AuthNetworkClient.ChallengeDisposition.INTERACTIVE_REQUIRED
                : AuthNetworkClient.ChallengeDisposition.IGNORED;
        if (disposition == AuthNetworkClient.ChallengeDisposition.AUTO_SUBMITTED
                || disposition == AuthNetworkClient.ChallengeDisposition.IGNORED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new MCTGAuthSettingsScreen(null, config, controller, true, storedPasswordRejected));
    }

    private void success(AuthSuccessPayload payload, IPayloadContext context) {
        if (!controller.success(payload.sessionId())) return;
        LOGGER.info("[MCTGAuth] Authentication successful");
        storedPasswordRejected = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MCTGAuthSettingsScreen) minecraft.setScreen(null);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable("mctgauth.auth.success"), true);
        }
    }

    private void failure(AuthFailurePayload payload, IPayloadContext context) {
        recordFailure(payload);
    }

    private void recordFailure(AuthFailurePayload payload) {
        if (!controller.failure(payload)) return;
        LOGGER.info("[MCTGAuth] Authentication failure received: reason={} retryAllowed={} remainingAttempts={}",
                payload.reason().name(), payload.retryAllowed(), payload.remainingAttempts());
        if (payload.retryAllowed()) {
            storedPasswordRejected = true;
        } else {
            storedPasswordRejected = false;
            LOGGER.warn("[MCTGAuth] Terminal authentication failure: reason={}; automatic reconnect disabled. "
                    + "MCTGAuth has no pending reconnect task.", payload.reason().name());
        }
    }

    private void titleScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) return;
        event.addListener(Button.builder(Component.translatable("mctgauth.settings.button"),
                button -> Minecraft.getInstance().setScreen(new MCTGAuthSettingsScreen(screen, config)))
                .bounds(6, 6, 110, 20).build());
    }
}
