package dev.example.mcauth.server.remote;

import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.server.BackendSyncClient;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteCommandPoller implements AutoCloseable {
    private final BackendSyncClient backend;
    private final RemoteCommandDispatcher dispatcher;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RemoteCommandPoller(BackendSyncClient backend, RemoteCommandDispatcher dispatcher, Duration interval) {
        this.backend = backend;
        this.dispatcher = dispatcher;
        this.interval = interval;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-remote-commands");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
    }

    private void pollSafely() {
        if (closed.get()) return;
        try {
            for (RemoteCommand command : backend.pendingCommands()) {
                if (!dispatcher.reserve(command)) {
                    RemoteCommandHandler.CommandResult cached = dispatcher.dispatch(command);
                    backend.completeCommand(command.id(), cached.success() ? RemoteCommandStatus.SUCCESS : RemoteCommandStatus.FAILED,
                            cached.result(), cached.safeError());
                    continue;
                }
                backend.markCommandStarted(command.id());
                RemoteCommandHandler.CommandResult result = dispatcher.dispatch(command);
                backend.completeCommand(command.id(), result.success() ? RemoteCommandStatus.SUCCESS : RemoteCommandStatus.FAILED,
                        result.result(), result.safeError());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Sync status already exposes backend degradation; never log response bodies or credentials here.
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow();
    }
}
