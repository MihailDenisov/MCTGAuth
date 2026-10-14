package dev.example.mcauth.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Background telemetry coordinator. Game callbacks only capture immutable data and submit work. */
final class ServerTelemetryService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerTelemetryService.class);
    private final String serverId;
    private final UUID instanceId = UUID.randomUUID();
    private final Instant startedAt;
    private final Clock clock;
    private final BackendTelemetryClient backend;
    private final TelemetryEventQueue queue;
    private final PlayerSessionTracker sessions;
    private final ScheduledExecutorService worker;
    private final HeartbeatScheduler heartbeats;
    private final AtomicInteger maxPlayers = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int failures;

    ServerTelemetryService(ServerAuthConfig.Values config, BackendSyncClient backendClient, Path journal,
                           Clock clock, int initialMaxPlayers) {
        this(config, backendClient, journal, clock, initialMaxPlayers, config.heartbeatInterval());
    }

    ServerTelemetryService(ServerAuthConfig.Values config, BackendSyncClient backendClient, Path journal,
                           Clock clock, int initialMaxPlayers, Duration heartbeatScheduleInterval) {
        this.serverId = config.serverId(); this.clock = clock; this.startedAt = Instant.now(clock);
        this.backend = new BackendTelemetryClient(backendClient);
        this.queue = new TelemetryEventQueue(journal, config.telemetryQueueLimit());
        this.sessions = new PlayerSessionTracker(serverId, instanceId, clock);
        this.maxPlayers.set(initialMaxPlayers);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-telemetry"); thread.setDaemon(true); return thread;
        });
        this.heartbeats = new HeartbeatScheduler(heartbeatScheduleInterval, this::sendHeartbeat,
                this::heartbeatFailure);
    }

    void start() {
        if (closed.get()) throw new IllegalStateException("Telemetry service is closed");
        if (!started.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { queue.open(); drain(); }
            catch (Exception exception) { LOGGER.error("MCTGAuth telemetry journal could not be opened: {}",
                    exception.getClass().getSimpleName()); }
        });
        heartbeats.start();
        LOGGER.info("MCTGAuth telemetry started: serverId={} heartbeatIntervalSeconds={}",
                serverId, configHeartbeatIntervalSeconds());
    }

    void authenticated(UUID connectionId, AuthAccount account) { capture(sessions.joined(connectionId, account)); }
    void disconnected(UUID connectionId) { capture(sessions.left(connectionId)); }
    void updateMaxPlayers(int value) { if (value >= 0) maxPlayers.set(value); }

    private void capture(TelemetryPlayerEvent event) {
        if (event == null || closed.get()) return;
        worker.execute(() -> {
            try {
                if (!queue.add(event)) {
                    LOGGER.error("MCTGAuth telemetry queue is full ({} events); player event {} requires administrator action",
                            queue.size(), event.eventId());
                    return;
                }
                drain();
            } catch (Exception exception) { failure(exception); }
        });
    }

    private void drain() {
        while (!closed.get() && queue.peek() != null) {
            try { backend.playerEvent(queue.peek()); queue.removeFirst(); failures = 0; }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            catch (Exception exception) { failure(exception); scheduleRetry(); return; }
        }
    }

    private void scheduleRetry() {
        long seconds = Math.min(60, 1L << Math.min(failures, 6));
        if (!closed.get()) worker.schedule(this::drain, seconds, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        if (closed.get()) return;
        LOGGER.debug("MCTGAuth heartbeat attempt");
        try {
            backend.heartbeat(serverId, instanceId, startedAt, Instant.now(clock),
                    sessions.activeCount(), maxPlayers.get());
            failures = 0;
            LOGGER.debug("MCTGAuth heartbeat accepted");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            heartbeatFailure(exception);
        } catch (Exception exception) {
            heartbeatFailure(exception);
        }
    }

    private long configHeartbeatIntervalSeconds() {
        return heartbeats.intervalSeconds();
    }

    private void heartbeatFailure(Throwable throwable) {
        LOGGER.warn("MCTGAuth heartbeat failed: errorType={}", throwable.getClass().getSimpleName());
    }

    private void failure(Exception exception) {
        failures++;
        if (failures == 1 || failures % 10 == 0) LOGGER.warn(
                "MCTGAuth telemetry delivery failed; queuedEvents={} errorType={}",
                queue.size(), exception.getClass().getSimpleName());
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        heartbeats.close();
        var closingEvents = sessions.closeAll();
        try {
            for (TelemetryPlayerEvent event : closingEvents) {
                if (!queue.add(event)) LOGGER.error("MCTGAuth telemetry queue full during shutdown");
            }
        } catch (Exception exception) { failure(exception); }
        worker.execute(() -> {
            try {
                while (queue.peek() != null) { backend.playerEvent(queue.peek()); queue.removeFirst(); }
                backend.shutdown(serverId, instanceId, Instant.now(clock));
            } catch (Exception exception) { failure(exception); }
        });
        worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        finally {
            worker.shutdownNow();
            LOGGER.info("MCTGAuth telemetry stopped");
        }
    }
}
