package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceFingerprintAndBanServiceTest {
    @TempDir Path temporary;

    @Test
    void resolvesChangedDiskByStrongStableSignalsAndStoresOnlyBackendHmacTokens() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("device.db"))) {
            long account = context.register(2001L, "PlayerOne");
            DeviceFingerprintService service = devices(context);
            var first = service.observe(observation(account, "playerone", bytes(1), bytes(2), bytes(3)));
            var changedDisk = service.observe(observation(account, "playerone", bytes(1), bytes(2), bytes(9)));

            assertTrue(first.created());
            assertEquals(first.deviceId(), changedDisk.deviceId());
            assertEquals(DeviceFingerprintService.Confidence.HIGH, changedDisk.confidence());
            String stored = context.database.transaction(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT signal_hash FROM device_signals LIMIT 1")) {
                    result.next(); return result.getString(1);
                }
            });
            assertEquals(64, stored.length());
            assertNotEquals(java.util.HexFormat.of().formatHex(bytes(1)), stored);
        }
    }

    @Test
    void rejectsWeakOnlyObservationsAndDetectionNeverCreatesABan() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("weak.db"))) {
            long account = context.register(2002L, "PlayerTwo");
            DeviceFingerprintService service = devices(context);
            assertThrows(WorkflowException.class, () -> service.observe(new DeviceFingerprintService.Observation(
                    "survival-main", account, "playertwo", 1, List.of(
                    new DeviceFingerprintService.ObservedSignal(DeviceFingerprintConfig.SignalType.GPU, bytes(1))))));
            assertEquals(0, count(context, "SELECT COUNT(*) FROM bans"));
        }
    }

    @Test
    void explicitDeviceBanBlocksAFutureAccountResolvedToThatDevice() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("device-ban.db"))) {
            long firstAccount = context.register(2101L, "FirstUser");
            DeviceFingerprintService service = devices(context);
            long deviceId = service.observe(observation(
                    firstAccount, "firstuser", bytes(31), bytes(32), bytes(33))).deviceId();
            BanService bans = new BanService(context.database, context.moderators,
                    context.syncEvents, context.audit, context.clock);
            bans.banDevice(new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    deviceId, "confirmed device abuse");

            long futureAccount = context.register(2102L, "FutureUser");
            var verdict = service.observe(observation(
                    futureAccount, "futureuser", bytes(31), bytes(32), bytes(33)));
            assertEquals(deviceId, verdict.deviceId());
            assertEquals(DeviceFingerprintService.Decision.DEVICE_BANNED, verdict.decision());
        }
    }

    @Test
    void linkedBanIsExplicitAndPropagatesExactlyOneHopWithExemptions() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporary.resolve("bans.db"))) {
            long accountA = context.register(3001L, "PlayerA");
            long accountB = context.register(3002L, "PlayerB");
            long accountC = context.register(3003L, "PlayerC");
            DeviceFingerprintService service = devices(context);
            long deviceOne = service.observe(observation(accountA, "playera", bytes(11), bytes(12), bytes(13))).deviceId();
            service.observe(observation(accountB, "playerb", bytes(11), bytes(12), bytes(13)));
            long deviceTwo = service.observe(observation(accountB, "playerb", bytes(21), bytes(22), bytes(23))).deviceId();
            service.observe(observation(accountC, "playerc", bytes(21), bytes(22), bytes(23)));

            BanService bans = new BanService(context.database, context.moderators,
                    context.syncEvents, context.audit, context.clock);
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod");
            var preview = bans.previewUserBan(moderator, 3001L);
            assertEquals(List.of(3002L), preview.directlyLinkedUserIds());
            assertFalse(bans.isUserBanned(3001L), "preview and matching must not ban automatically");

            bans.createExemption(moderator, 3002L, deviceOne, "shared computer");
            assertTrue(bans.previewUserBan(moderator, 3001L).directlyLinkedUserIds().isEmpty());
            bans.removeExemption(moderator, 3002L, deviceOne, "no longer shared");
            context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE minecraft_accounts SET enabled = 0 WHERE owner_telegram_id = ?")) {
                    statement.setLong(1, 3002L);
                    statement.executeUpdate();
                }
                return null;
            });
            var result = bans.banUser(moderator, 3001L, "confirmed abuse", true);
            assertEquals(1, result.linkedBanIds().size());
            assertTrue(bans.isUserBanned(3001L));
            assertTrue(bans.isUserBanned(3002L));
            assertFalse(bans.isUserBanned(3003L), "device-of-linked-user must not cause recursive propagation");

            assertEquals(1, bans.unban(moderator, result.linkedBanIds().getFirst(), "appeal", false));
            assertTrue(bans.isUserBanned(3001L));
            assertFalse(bans.isUserBanned(3002L));
            assertEquals(0, enabled(context, accountB), "unban must not re-enable a manually disabled account");
            insertIndependentActiveBan(context, 3001L);
            assertEquals(1, bans.unban(moderator, result.originBanId(), "resolved", true));
            assertTrue(bans.isUserBanned(3001L), "an unrelated active ban must survive group unban");
            assertEquals(2, count(context, "SELECT COUNT(*) FROM bans WHERE active = 0"),
                    "removed ban records must be retained as inactive history");
            assertNotEquals(deviceOne, deviceTwo);
        }
    }

    private static DeviceFingerprintService devices(BackendTestContext context) {
        return new DeviceFingerprintService(context.database, context.clock, "survival-main",
                "test-device-secret-with-at-least-32-bytes", DeviceFingerprintConfig.defaults());
    }

    private static DeviceFingerprintService.Observation observation(long account, String name,
                                                                     byte[] system, byte[] machine, byte[] disk) {
        return new DeviceFingerprintService.Observation("survival-main", account, name, 1, List.of(
                new DeviceFingerprintService.ObservedSignal(DeviceFingerprintConfig.SignalType.SYSTEM_UUID, system),
                new DeviceFingerprintService.ObservedSignal(DeviceFingerprintConfig.SignalType.MACHINE_ID, machine),
                new DeviceFingerprintService.ObservedSignal(DeviceFingerprintConfig.SignalType.DISK_ID, disk)));
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static int count(BackendTestContext context, String sql) {
        return context.database.transaction(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
                result.next(); return result.getInt(1);
            }
        });
    }

    private static int enabled(BackendTestContext context, long accountId) {
        return context.database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT enabled FROM minecraft_accounts WHERE id = ?")) {
                statement.setLong(1, accountId);
                try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
            }
        });
    }

    private static void insertIndependentActiveBan(BackendTestContext context, long userId) {
        context.database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO bans (ban_type, target_user_id, reason, created_by, created_at, active)
                    VALUES ('USER_BAN', ?, 'independent fixture ban', ?, ?, 1)
                    """)) {
                statement.setLong(1, userId);
                statement.setLong(2, BackendTestContext.MODERATOR_ID);
                statement.setString(3, context.clock.instant().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
