package dev.example.mcauth.server;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class HeartbeatScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private final Runnable task;
    private final Consumer<Throwable> failureHandler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduledTask;

    HeartbeatScheduler(Duration interval, Runnable task, Consumer<Throwable> failureHandler) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        }
        this.interval = interval;
        this.task = java.util.Objects.requireNonNull(task, "task");
        this.failureHandler = java.util.Objects.requireNonNull(failureHandler, "failureHandler");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mctgauth-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (closed.get()) throw new IllegalStateException("Heartbeat scheduler is closed");
        if (!started.compareAndSet(false, true)) return;
        scheduledTask = executor.scheduleWithFixedDelay(
                this::runSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runSafely() {
        if (closed.get()) return;
        try {
            task.run();
        } catch (Throwable throwable) {
            try {
                failureHandler.accept(throwable);
            } catch (Throwable ignored) {
                // The recurring task must survive even a broken diagnostic callback.
            }
        }
    }

    boolean isRunning() {
        ScheduledFuture<?> current = scheduledTask;
        return started.get() && !closed.get() && current != null && !current.isCancelled() && !current.isDone();
    }

    long intervalSeconds() {
        return interval.toSeconds();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> current = scheduledTask;
        if (current != null) current.cancel(true);
        executor.shutdownNow();
    }
}
