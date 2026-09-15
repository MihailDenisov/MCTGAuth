package dev.example.mcauth.server;

import dev.example.mcauth.api.SyncEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SyncWorker implements AutoCloseable {
    private final SyncBackend backend;
    private final SyncEventProcessor processor;
    private final BackendStatus status;
    private final Clock clock;
    private final Duration normalInterval;
    private final Consumer<SyncEvent> appliedCallback;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean pollInProgress = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SyncWorker(BackendSyncClient backend, SyncEventProcessor processor, BackendStatus status,
                      Clock clock, Duration normalInterval, Consumer<SyncEvent> appliedCallback) {
        this((SyncBackend) backend, processor, status, clock, normalInterval, appliedCallback);
    }

    SyncWorker(SyncBackend backend, SyncEventProcessor processor, BackendStatus status,
                      Clock clock, Duration normalInterval, Consumer<SyncEvent> appliedCallback) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.status = Objects.requireNonNull(status, "status");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.normalInterval = Objects.requireNonNull(normalInterval, "normalInterval");
        this.appliedCallback = Objects.requireNonNull(appliedCallback, "appliedCallback");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        schedule(Duration.ZERO);
    }

    public boolean requestImmediatePoll() {
        if (closed.get()) return false;
        executor.execute(this::poll);
        return true;
    }

    private void schedule(Duration delay) {
        if (!closed.get()) executor.schedule(this::poll, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (closed.get() || !pollInProgress.compareAndSet(false, true)) return;
        Duration next = normalInterval;
        Instant now = Instant.now(clock);
        status.attempted(now);
        int applied = 0;
        try {
            List<SyncEvent> events = backend.pendingEvents();
            for (SyncEvent event : events) {
                SyncEventProcessor.ApplyResult result = processor.apply(event);
                if (result == SyncEventProcessor.ApplyResult.APPLIED) {
                    applied++;
                    appliedCallback.accept(event);
                }
                backend.acknowledge(event.id());
            }
            status.succeeded(Instant.now(clock), applied);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status.failed("Synchronization interrupted");
        } catch (Exception exception) {
            status.failed(safeMessage(exception));
            long baseSeconds = Math.min(60L, 10L << Math.min(status.consecutiveFailures() - 1, 3));
            long jitterMillis = ThreadLocalRandom.current().nextLong(250, 1251);
            next = Duration.ofSeconds(baseSeconds).plusMillis(jitterMillis);
        } finally {
            pollInProgress.set(false);
            schedule(next);
        }
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof java.net.http.HttpTimeoutException) return "Backend request timed out";
        if (exception instanceof java.net.ConnectException) return "Backend is unavailable";
        return "Backend synchronization failed: " + exception.getClass().getSimpleName();
    }

    public boolean pollInProgress() { return pollInProgress.get(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        backend.close();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
