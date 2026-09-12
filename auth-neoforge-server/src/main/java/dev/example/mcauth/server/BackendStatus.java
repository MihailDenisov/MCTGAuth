package dev.example.mcauth.server;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BackendStatus {
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong appliedEvents = new AtomicLong();
    private final AtomicLong failedPolls = new AtomicLong();

    void attempted(Instant now) { lastAttempt.set(now); }
    void succeeded(Instant now, long applied) {
        lastSuccess.set(now);
        lastError.set(null);
        consecutiveFailures.set(0);
        appliedEvents.addAndGet(applied);
    }
    void failed(String safeMessage) {
        lastError.set(safeMessage);
        consecutiveFailures.incrementAndGet();
        failedPolls.incrementAndGet();
    }

    public Optional<Instant> lastAttempt() { return Optional.ofNullable(lastAttempt.get()); }
    public Optional<Instant> lastSuccess() { return Optional.ofNullable(lastSuccess.get()); }
    public Optional<String> lastError() { return Optional.ofNullable(lastError.get()); }
    public int consecutiveFailures() { return consecutiveFailures.get(); }
    public long appliedEvents() { return appliedEvents.get(); }
    public long failedPolls() { return failedPolls.get(); }
    public boolean backendHealthy() { return lastSuccess.get() != null && consecutiveFailures.get() == 0; }
}
