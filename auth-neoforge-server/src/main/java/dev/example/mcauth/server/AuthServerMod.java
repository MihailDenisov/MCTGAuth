package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.api.SyncEventType;
import dev.example.mcauth.protocol.AuthFailureReason;
import dev.example.mcauth.protocol.AuthProtocol;
import dev.example.mcauth.protocol.AuthResponsePayload;
import dev.example.mcauth.protocol.AuthStatePayload;
import dev.example.mcauth.protocol.AuthSuccessPayload;
import dev.example.mcauth.protocol.ClientHelloPayload;
import dev.example.mcauth.protocol.PlayerAuthState;
import dev.example.mcauth.protocol.ServerHelloPayload;
import dev.example.mcauth.protocol.AuthChallengePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import dev.example.mcauth.server.remote.RemoteCommandDispatcher;
import dev.example.mcauth.server.remote.RemoteCommandPoller;
import dev.example.mcauth.server.remote.handlers.AuthStatusHandler;
import dev.example.mcauth.server.remote.handlers.ForceAuthSyncHandler;
import dev.example.mcauth.server.remote.handlers.ResyncPlayerHandler;

@Mod(AuthProtocol.SERVER_MOD_ID)
public final class AuthServerMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServerMod.class);
    private static volatile AuthServerMod INSTANCE;

    private final Map<UUID, Anchor> anchors = new ConcurrentHashMap<>();
    private final Set<UUID> terminalDisconnects = ConcurrentHashMap.newKeySet();
    private volatile MinecraftServer minecraftServer;
    private volatile AuthAccountStore store;
    private volatile LoginSessionManager sessions;
    private volatile BackendStatus backendStatus = new BackendStatus();
    private volatile SyncWorker syncWorker;
    private volatile RemoteCommandPoller remoteCommandPoller;
    private volatile ServerTelemetryService telemetryService;
    private volatile ServerAuthConfig.Values config;

    public AuthServerMod(IEventBus modBus, ModContainer container) {
        INSTANCE = this;
        // COMMON config stays on the dedicated server; SERVER configs are synchronized to clients by NeoForge.
        container.registerConfig(ModConfig.Type.COMMON, ServerAuthConfig.SPEC, "mctgauth-server.toml");
        modBus.addListener(this::registerPayloads);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::serverStarting);
        gameBus.addListener(this::serverStarted);
        gameBus.addListener(this::serverStopping);
        gameBus.addListener(this::playerLogin);
        gameBus.addListener(this::playerLogout);
        gameBus.addListener(this::playerTick);
        gameBus.addListener(this::leftClickBlock);
        gameBus.addListener(this::rightClickBlock);
        gameBus.addListener(this::rightClickItem);
        gameBus.addListener(this::entityInteract);
        gameBus.addListener(this::entityInteractSpecific);
        gameBus.addListener(this::itemToss);
        gameBus.addListener(this::itemPickup);
        gameBus.addListener(this::damage);
        gameBus.addListener(this::chat);
        gameBus.addListener(this::command);
        gameBus.addListener(this::changedDimension);
        gameBus.addListener(this::registerCommands);
    }

    static AuthServerMod instance() { return INSTANCE; }
    boolean ready() { return store != null && sessions != null; }
    LoginSessionManager sessions() { return sessions; }
    BackendStatus backendStatus() { return backendStatus; }
    AuthAccountStore store() { return store; }
    ServerAuthConfig.Values config() { return config; }
    Optional<SyncWorker> syncWorker() { return Optional.ofNullable(syncWorker); }
    boolean claimTerminalDisconnect(UUID playerId) { return terminalDisconnects.add(playerId); }
    void playerAuthenticated(ServerPlayer player) {
        ServerTelemetryService telemetry = telemetryService;
        AuthAccountStore accountStore = store;
        if (telemetry == null || accountStore == null) return;
        accountStore.findByMinecraftName(player.getGameProfile().getName())
                .ifPresent(account -> telemetry.authenticated(player.getUUID(), account));
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(AuthProtocol.NETWORK_VERSION);
        registrar.playToServer(ClientHelloPayload.TYPE, ClientHelloPayload.STREAM_CODEC, AuthPayloadHandler::clientHello);
        registrar.playToServer(AuthResponsePayload.TYPE, AuthResponsePayload.STREAM_CODEC, AuthPayloadHandler::authResponse);
        registrar.playToClient(ServerHelloPayload.TYPE, ServerHelloPayload.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(AuthChallengePayload.TYPE, AuthChallengePayload.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(AuthSuccessPayload.TYPE, AuthSuccessPayload.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(dev.example.mcauth.protocol.AuthFailurePayload.TYPE,
                dev.example.mcauth.protocol.AuthFailurePayload.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(AuthStatePayload.TYPE, AuthStatePayload.STREAM_CODEC, (payload, context) -> { });
    }

    private void serverStarting(ServerStartingEvent event) {
        minecraftServer = event.getServer();
        try {
            config = ServerAuthConfig.values();
            store = new JsonAuthAccountStore(FMLPaths.GAMEDIR.get().resolve("mctgauth").resolve("accounts.json"));
            store.open();
            sessions = new LoginSessionManager(store, Clock.systemUTC(), new SecureRandom(),
                    config.authenticationTimeout(), config.maxAttempts());
            Optional<char[]> token;
            boolean invalidToken = false;
            try {
                token = ServerSecretProvider.apiToken(ServerAuthConfig.configuredApiToken());
            } catch (IllegalStateException exception) {
                token = Optional.empty();
                invalidToken = true;
                backendStatus.failed("Synchronization disabled: backend API token configuration is invalid");
                LOGGER.error("MCTGAuth backend API token configuration is invalid. Backend synchronization is disabled: {}",
                        exception.getMessage());
            }
            LOGGER.info("MCTGAuth backend configuration: baseUrl={} serverId={} apiToken={}",
                    config.baseUrl(), config.serverId(), token.isPresent() ? "<configured>" : "<missing>");
            if (token.isPresent()) {
                char[] secret = token.get();
                try {
                    BackendSyncClient client = new BackendSyncClient(config, secret);
                    syncWorker = new SyncWorker(client, new SyncEventProcessor(store), backendStatus,
                            Clock.systemUTC(), config.pollInterval(), this::syncApplied);
                    syncWorker.start();
                    RemoteCommandDispatcher dispatcher = new RemoteCommandDispatcher(config.serverId(), store,
                            Clock.systemUTC(), java.util.List.of(
                            new ForceAuthSyncHandler(() -> syncWorker),
                            new AuthStatusHandler(store, sessions, backendStatus),
                            new ResyncPlayerHandler(store, () -> syncWorker,
                                    uuid -> sessions == null ? Optional.empty()
                                            : sessions.connectedMinecraftName(uuid))));
                    remoteCommandPoller = new RemoteCommandPoller(client, dispatcher, config.pollInterval());
                    remoteCommandPoller.start();
                    if (config.telemetryEnabled()) {
                        telemetryService = new ServerTelemetryService(config, client,
                                FMLPaths.GAMEDIR.get().resolve("mctgauth").resolve("telemetry-events.json"),
                                Clock.systemUTC(), minecraftServer.getMaxPlayers());
                    }
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
            } else if (!invalidToken) {
                backendStatus.failed("Synchronization disabled: backend API token is not configured");
                LOGGER.warn("MCTGAuth backend API token is not configured. Backend synchronization is disabled. "
                        + "Local authentication remains available.");
            }
            LOGGER.info("MCTGAuth server ready: localSchema={} protocol={}",
                    JsonAuthAccountStore.SCHEMA_VERSION, AuthProtocol.AUTH_PROTOCOL_VERSION);
        } catch (RuntimeException exception) {
            LOGGER.error("MCTGAuth failed to initialize; player authentication will fail closed", exception);
            stopServices();
        }
    }

    private void serverStarted(ServerStartedEvent event) {
        if (event.getServer() != minecraftServer) return;
        ServerTelemetryService telemetry = telemetryService;
        if (telemetry != null) telemetry.start();
    }

    private void serverStopping(ServerStoppingEvent event) {
        stopServices();
        minecraftServer = null;
    }

    private void stopServices() {
        ServerTelemetryService telemetry = telemetryService;
        telemetryService = null;
        if (telemetry != null) telemetry.close();
        RemoteCommandPoller commands = remoteCommandPoller;
        remoteCommandPoller = null;
        if (commands != null) commands.close();
        SyncWorker worker = syncWorker;
        syncWorker = null;
        if (worker != null) worker.close();
        LoginSessionManager manager = sessions;
        sessions = null;
        if (manager != null) manager.close();
        AuthAccountStore localStore = store;
        store = null;
        if (localStore != null) localStore.close();
        anchors.clear();
        terminalDisconnects.clear();
    }

    private void playerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        terminalDisconnects.remove(player.getUUID());
        if (!ready()) {
            AuthPayloadHandler.rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, null);
            return;
        }
        sessions.connected(player.getUUID(), player.getUUID(), player.getGameProfile().getName());
        anchors.put(player.getUUID(), new Anchor(player.position(), player.getYRot(), player.getXRot()));
        PacketDistributor.sendToPlayer(player,
                new ServerHelloPayload(AuthProtocol.AUTH_PROTOCOL_VERSION,
                        AuthProtocol.MOD_VERSION, AuthProtocol.REQUIRED_FEATURES));
        LOGGER.debug("[MCTGAuth] Authentication started: player={} uuid={}",
                player.getGameProfile().getName(), player.getUUID());
    }

    private void playerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerTelemetryService telemetry = telemetryService;
        if (telemetry != null) telemetry.disconnected(player.getUUID());
        if (sessions != null) sessions.disconnected(player.getUUID());
        anchors.remove(player.getUUID());
        terminalDisconnects.remove(player.getUUID());
    }

    private void playerTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || authenticated(player)) return;
        if (!ready()) {
            player.connection.disconnect(net.minecraft.network.chat.Component.translatable("mctgauth.error.internal"));
            return;
        }
        PlayerAuthSession session = sessions.session(player.getUUID()).orElse(null);
        if (session == null) {
            AuthPayloadHandler.rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, null);
            return;
        }
        if (session.state() == PlayerAuthState.FAILED || session.state() == PlayerAuthState.TIMED_OUT) return;
        if (!Instant.now().isBefore(session.createdAt().plus(config.authenticationTimeout()))) {
            AuthFailureReason reason = session.state() == PlayerAuthState.WAITING_FOR_CLIENT
                    ? AuthFailureReason.CLIENT_MISSING : AuthFailureReason.AUTH_TIMEOUT;
            session.timeout();
            AuthPayloadHandler.rejectAndDisconnect(player, reason, null);
            return;
        }
        Anchor anchor = anchors.get(player.getUUID());
        if (anchor != null && player.position().distanceToSqr(anchor.position) > 0.0001) {
            player.connection.teleport(anchor.position.x, anchor.position.y, anchor.position.z,
                    anchor.yaw, anchor.pitch);
        }
        player.setDeltaMovement(Vec3.ZERO);
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
    }

    private boolean authenticated(ServerPlayer player) {
        return sessions != null && sessions.isAuthenticated(player.getUUID());
    }

    public static boolean isAuthenticated(ServerPlayer player) {
        AuthServerMod runtime = INSTANCE;
        return runtime != null && runtime.authenticated(player);
    }

    private void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) { cancelInteraction(event); }
    private void rightClickBlock(PlayerInteractEvent.RightClickBlock event) { cancelInteraction(event); }
    private void rightClickItem(PlayerInteractEvent.RightClickItem event) { cancelInteraction(event); }
    private void entityInteract(PlayerInteractEvent.EntityInteract event) { cancelInteraction(event); }
    private void entityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { cancelInteraction(event); }

    private void cancelInteraction(PlayerInteractEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !authenticated(player)
                && event instanceof net.neoforged.bus.api.ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
        }
    }

    private void itemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && !authenticated(player)) event.setCanceled(true);
    }

    private void itemPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer player && !authenticated(player)) event.setCanPickup(TriState.FALSE);
    }

    private void damage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer target && !authenticated(target)) {
            event.setCanceled(true);
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof ServerPlayer player && !authenticated(player)) event.setCanceled(true);
    }

    private void chat(ServerChatEvent event) {
        if (!authenticated(event.getPlayer())) event.setCanceled(true);
    }

    private void command(CommandEvent event) {
        Entity entity = event.getParseResults().getContext().getSource().getEntity();
        if (entity instanceof ServerPlayer player && !authenticated(player)) event.setCanceled(true);
    }

    private void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !authenticated(player)) {
            AuthPayloadHandler.rejectAndDisconnect(player, AuthFailureReason.INTERNAL_AUTH_ERROR, null);
        }
    }

    private void registerCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher(), this);
    }

    private void syncApplied(SyncEvent event) {
        if (event.type() != SyncEventType.ACCOUNT_DISABLED || !config.disconnectPlayerWhenAccountDisabled()) return;
        MinecraftServer server = minecraftServer;
        if (server == null) return;
        server.execute(() -> server.getPlayerList().getPlayers().forEach(player ->
                store.findByMinecraftName(player.getGameProfile().getName()).filter(account -> !account.enabled())
                        .ifPresent(account -> {
                            AuthPayloadHandler.disconnectPlayer(player, AuthFailureReason.ACCOUNT_DISABLED);
                            LOGGER.info("[MCTGAuth] Player disconnected after account disable: player={}",
                                    player.getGameProfile().getName());
                        })));
    }

    private record Anchor(Vec3 position, float yaw, float pitch) { }
}
