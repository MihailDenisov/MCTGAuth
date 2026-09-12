package dev.example.mcauth.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;

final class AuthCommands {
    private AuthCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, AuthServerMod runtime) {
        dispatcher.register(Commands.literal("mcauth")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource(), runtime)))
                .then(Commands.literal("sync").executes(context -> sync(context.getSource(), runtime)))
                .then(Commands.literal("player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> player(context.getSource(), runtime,
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("resync")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> unsupported(context.getSource(),
                                        "Per-player resync is unsupported by the current backend API."))))
                .then(Commands.literal("reload")
                        .executes(context -> unsupported(context.getSource(),
                                "Safe config reload is not available; restart the server after editing config."))));
    }

    private static int status(CommandSourceStack source, AuthServerMod runtime) {
        if (!runtime.ready()) {
            source.sendFailure(Component.literal("MCTGAuth service: unavailable (fail-closed)"));
            return 0;
        }
        BackendStatus backend = runtime.backendStatus();
        String lastSync = backend.lastSuccess().map(AuthCommands::age).orElse("never");
        source.sendSuccess(() -> Component.literal("MCTGAuth"), false);
        source.sendSuccess(() -> Component.literal("Auth service: healthy"), false);
        source.sendSuccess(() -> Component.literal("Backend sync: "
                + (backend.backendHealthy() ? "healthy" : "degraded")), false);
        source.sendSuccess(() -> Component.literal("Local authentication: available"), false);
        source.sendSuccess(() -> Component.literal("Server ID: " + runtime.config().serverId()), false);
        source.sendSuccess(() -> Component.literal("Last successful sync: " + lastSync), false);
        source.sendSuccess(() -> Component.literal("Local accounts: " + runtime.store().accountCount()), false);
        source.sendSuccess(() -> Component.literal("Authenticated players: " + runtime.sessions().authenticatedCount()), false);
        source.sendSuccess(() -> Component.literal("Protocol version: "
                + dev.example.mcauth.protocol.AuthProtocol.AUTH_PROTOCOL_VERSION), false);
        return 1;
    }

    private static int sync(CommandSourceStack source, AuthServerMod runtime) {
        boolean scheduled = runtime.syncWorker().map(SyncWorker::requestImmediatePoll).orElse(false);
        if (!scheduled) {
            source.sendFailure(Component.literal("Authentication synchronization is unavailable."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Authentication synchronization requested."), false);
        return 1;
    }

    private static int player(CommandSourceStack source, AuthServerMod runtime, String name) {
        try {
            AuthAccount account = runtime.store().findByMinecraftName(name).orElse(null);
            if (account == null) {
                source.sendFailure(Component.literal("Account is not registered locally."));
                return 0;
            }
            String onlineState = source.getServer().getPlayerList().getPlayers().stream()
                    .filter(candidate -> candidate.getGameProfile().getName().equalsIgnoreCase(account.minecraftName()))
                    .findFirst()
                    .flatMap(candidate -> runtime.sessions().session(candidate.getUUID()))
                    .map(session -> session.state().name()).orElse("OFFLINE");
            source.sendSuccess(() -> Component.literal("Account: " + account.minecraftName()), false);
            source.sendSuccess(() -> Component.literal("Registered locally: yes"), false);
            source.sendSuccess(() -> Component.literal("Enabled: " + (account.enabled() ? "yes" : "no")), false);
            source.sendSuccess(() -> Component.literal("Credential version: " + account.credentialVersion()), false);
            source.sendSuccess(() -> Component.literal("Last updated: " + account.updatedAt()), false);
            source.sendSuccess(() -> Component.literal("Online authentication state: " + onlineState), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Invalid Minecraft account name."));
            return 0;
        }
    }

    private static int unsupported(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static String age(Instant instant) {
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).toSeconds());
        return seconds < 60 ? seconds + " seconds ago" : (seconds / 60) + " minutes ago";
    }
}
