package dev.example.mcauth.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatSchedulerTest {
    @Test
    void startSendsImmediateAndRecurringHeartbeats() throws Exception {
        CountDownLatch heartbeats = new CountDownLatch(4);
        try (HeartbeatScheduler scheduler = scheduler(heartbeats::countDown, ignored -> { })) {
            scheduler.start();

            assertTrue(heartbeats.await(2, TimeUnit.SECONDS), "expected immediate heartbeat plus three repeats");
            assertTrue(scheduler.isRunning());
        }
    }

    @Test
    void zeroOnlinePlayersDoNotStopRecurringHeartbeats() throws Exception {
        CountDownLatch heartbeats = new CountDownLatch(4);
        List<Integer> reportedOnlinePlayers = new CopyOnWriteArrayList<>();
        try (HeartbeatScheduler scheduler = scheduler(() -> {
            reportedOnlinePlayers.add(0);
            heartbeats.countDown();
        }, ignored -> { })) {
            scheduler.start();

            assertTrue(heartbeats.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(0, 0, 0, 0), reportedOnlinePlayers.subList(0, 4));
        }
    }

    @Test
    void heartbeatRunsOnDedicatedDaemonThread() throws Exception {
        CountDownLatch heartbeat = new CountDownLatch(1);
        AtomicReference<Thread> executingThread = new AtomicReference<>();
        try (HeartbeatScheduler scheduler = scheduler(() -> {
            executingThread.set(Thread.currentThread());
            heartbeat.countDown();
        }, ignored -> { })) {
            scheduler.start();

            assertTrue(heartbeat.await(2, TimeUnit.SECONDS));
            assertEquals("mctgauth-heartbeat", executingThread.get().getName());
            assertTrue(executingThread.get().isDaemon());
        }
    }

    @Test
    void escapedHeartbeatExceptionDoesNotCancelLaterExecutions() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch laterHeartbeat = new CountDownLatch(1);
        CountDownLatch failures = new CountDownLatch(1);
        try (HeartbeatScheduler scheduler = scheduler(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) throw new IllegalStateException("simulated heartbeat defect");
            if (attempt >= 4) laterHeartbeat.countDown();
        }, ignored -> failures.countDown())) {
            scheduler.start();

            assertTrue(failures.await(2, TimeUnit.SECONDS));
            assertTrue(laterHeartbeat.await(2, TimeUnit.SECONDS));
            assertTrue(scheduler.isRunning());
        }
    }

    @Test
    void temporaryBackendFailureRecoversWithoutRestart() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(3);
        try (HeartbeatScheduler scheduler = scheduler(() -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException("simulated backend outage");
            recovered.countDown();
        }, ignored -> { })) {
            scheduler.start();

            assertTrue(recovered.await(2, TimeUnit.SECONDS), "three later heartbeats must succeed");
            assertTrue(scheduler.isRunning());
        }
    }

    @Test
    void repeatedStartDoesNotReplaceOrCancelTheLiveSchedule() throws Exception {
        CountDownLatch heartbeats = new CountDownLatch(4);
        try (HeartbeatScheduler scheduler = scheduler(heartbeats::countDown, ignored -> { })) {
            scheduler.start();
            scheduler.start();

            assertTrue(heartbeats.await(2, TimeUnit.SECONDS));
            assertTrue(scheduler.isRunning());
        }
    }

    @Test
    void closeStopsTheRecurringTask() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch initialHeartbeat = new CountDownLatch(1);
        HeartbeatScheduler scheduler = new HeartbeatScheduler(Duration.ofMillis(100), () -> {
            attempts.incrementAndGet();
            initialHeartbeat.countDown();
        }, ignored -> { });
        scheduler.start();
        assertTrue(initialHeartbeat.await(2, TimeUnit.SECONDS));

        scheduler.close();
        int attemptsAtShutdown = attempts.get();
        Thread.sleep(250);

        assertEquals(attemptsAtShutdown, attempts.get());
        assertFalse(scheduler.isRunning());
    }

    @Test
    void rejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatScheduler(Duration.ZERO, () -> { }, ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatScheduler(Duration.ofSeconds(-1), () -> { }, ignored -> { }));
    }

    private static HeartbeatScheduler scheduler(Runnable task, java.util.function.Consumer<Throwable> failures) {
        return new HeartbeatScheduler(Duration.ofMillis(20), task, failures);
    }
}
