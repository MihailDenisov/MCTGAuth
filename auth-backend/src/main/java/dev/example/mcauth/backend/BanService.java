package dev.example.mcauth.backend;

import dev.example.mcauth.api.SyncEventType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BanService {
    private final BackendDatabase database;
    private final ModeratorService moderators;
    private final SyncEventService syncEvents;
    private final AuditService audit;
    private final Clock clock;

    public BanService(BackendDatabase database, ModeratorService moderators,
                      SyncEventService syncEvents, AuditService audit, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.moderators = Objects.requireNonNull(moderators, "moderators");
        this.syncEvents = Objects.requireNonNull(syncEvents, "syncEvents");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BanImpact previewUserBan(ModeratorIdentity moderator, long userId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.USER_BAN);
            requireUser(connection, userId);
            List<Long> devices = ids(connection,
                    "SELECT device_id FROM user_devices WHERE user_id = ? ORDER BY device_id", userId);
            Set<Long> linkedUsers = directLinkedUsers(connection, userId, devices);
            int ownAccounts = count(connection, "SELECT COUNT(*) FROM minecraft_accounts WHERE owner_telegram_id = ?", userId);
            int linkedAccounts = linkedUsers.stream().mapToInt(id -> uncheckedCount(connection,
                    "SELECT COUNT(*) FROM minecraft_accounts WHERE owner_telegram_id = ?", id)).sum();
            return new BanImpact(userId, ownAccounts, devices, List.copyOf(linkedUsers), linkedAccounts);
        });
    }

    public BanResult banUser(ModeratorIdentity moderator, long userId, String reason, boolean includeLinkedUsers) {
        String safeReason = reason(reason);
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.USER_BAN);
            requireUser(connection, userId);
            Instant now = Instant.now(clock);
            if (hasActiveUserBan(connection, userId, now)) throw conflict("User is already banned");
            List<Long> targetDevices = ids(connection,
                    "SELECT device_id FROM user_devices WHERE user_id = ? ORDER BY device_id", userId);
            Set<Long> linkedUsers = includeLinkedUsers ? directLinkedUsers(connection, userId, targetDevices) : Set.of();
            long origin = insertBan(connection, BanType.USER_BAN, userId, null, safeReason,
                    moderator.telegramId(), now, null);
            int affectedAccounts = addAccountEffectsAndSync(connection, origin, userId,
                    SyncEventType.USER_BANNED, moderator, now);
            List<Long> linkedBanIds = new ArrayList<>();
            for (long linkedUser : linkedUsers) {
                if (hasActiveUserBan(connection, linkedUser, now)) continue;
                long linkedBan = insertBan(connection, BanType.LINKED_DEVICE_BAN, linkedUser, null,
                        safeReason, moderator.telegramId(), now, origin);
                linkedBanIds.add(linkedBan);
                affectedAccounts += addAccountEffectsAndSync(connection, linkedBan, linkedUser,
                        SyncEventType.USER_BANNED, moderator, now);
            }
            audit.record(connection, "BAN_CREATED", null, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("banId", origin, "targetUserId", userId,
                            "linkedUsers", linkedBanIds.size(), "affectedAccounts", affectedAccounts,
                            "propagation", includeLinkedUsers ? "DIRECT_ONE_HOP" : "NONE"));
            return new BanResult(origin, List.copyOf(linkedBanIds), affectedAccounts);
        });
    }

    public long banDevice(ModeratorIdentity moderator, long deviceId, String reason) {
        String safeReason = reason(reason);
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_BAN);
            requireDevice(connection, deviceId);
            Instant now = Instant.now(clock);
            Long existing = activeBanId(connection, "target_device_id", deviceId, now);
            if (existing != null) return existing;
            long banId = insertBan(connection, BanType.DEVICE_BAN, null, deviceId, safeReason,
                    moderator.telegramId(), now, null);
            syncEvents.enqueue(connection, SyncEventType.DEVICE_BANNED,
                    Map.of("deviceId", deviceId, "banId", banId), null,
                    moderator.telegramId(), moderator.usernameSnapshot(), now);
            audit.record(connection, "DEVICE_BAN_CREATED", null, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("banId", banId, "deviceId", deviceId));
            return banId;
        });
    }

    public int unban(ModeratorIdentity moderator, long banId, String removalReason, boolean wholeGroup) {
        String safeReason = optionalReason(removalReason);
        return database.transaction(connection -> {
            BanRow selected = requireActiveBan(connection, banId);
            moderators.requirePermission(connection, moderator,
                    selected.type() == BanType.DEVICE_BAN ? ModeratorPermission.DEVICE_UNBAN
                            : ModeratorPermission.USER_UNBAN);
            Instant now = Instant.now(clock);
            List<BanRow> rows = wholeGroup ? groupRows(connection, selected) : List.of(selected);
            int changed = 0;
            Set<Long> affectedUsers = new LinkedHashSet<>();
            for (BanRow row : rows) {
                changed += deactivate(connection, row.id(), moderator.telegramId(), safeReason, now);
                if (row.userId() != null) affectedUsers.add(row.userId());
                if (row.deviceId() != null) {
                    syncEvents.enqueue(connection, SyncEventType.DEVICE_UNBANNED,
                            Map.of("deviceId", row.deviceId(), "banId", row.id()), null,
                            moderator.telegramId(), moderator.usernameSnapshot(), now);
                }
            }
            for (long userId : affectedUsers) {
                if (!hasActiveUserBan(connection, userId, now)) {
                    enqueueUserState(connection, userId, SyncEventType.USER_UNBANNED, 0,
                            moderator, now);
                }
            }
            audit.record(connection, wholeGroup ? "BAN_GROUP_REMOVED"
                            : selected.type() == BanType.DEVICE_BAN ? "DEVICE_BAN_REMOVED" : "BAN_REMOVED",
                    null, null, moderator.telegramId(), moderator.usernameSnapshot(), now,
                    Map.of("banId", banId, "removedCount", changed));
            return changed;
        });
    }

    public void createExemption(ModeratorIdentity moderator, long userId, long deviceId, String reason) {
        String safeReason = reason(reason);
        database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_EXEMPTION);
            requireUser(connection, userId);
            requireDevice(connection, deviceId);
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO device_ban_exemptions
                        (user_id, device_id, created_by, created_at, reason, active)
                    VALUES (?, ?, ?, ?, ?, 1)
                    ON CONFLICT(user_id, device_id) DO UPDATE SET
                        created_by = excluded.created_by, created_at = excluded.created_at,
                        reason = excluded.reason, active = 1, removed_by = NULL,
                        removed_at = NULL, remove_reason = NULL
                    """)) {
                statement.setLong(1, userId); statement.setLong(2, deviceId);
                statement.setLong(3, moderator.telegramId()); statement.setString(4, now.toString());
                statement.setString(5, safeReason); statement.executeUpdate();
            }
            audit.record(connection, "BAN_EXEMPTION_CREATED", null, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("userId", userId, "deviceId", deviceId));
            return null;
        });
    }

    public DeviceView device(ModeratorIdentity moderator, long deviceId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_BAN);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT fingerprint_version, confidence, first_seen_at, last_seen_at,
                           (SELECT COUNT(*) FROM user_devices WHERE device_id = d.id) AS users,
                           (SELECT COUNT(*) FROM account_devices WHERE device_id = d.id) AS accounts,
                           EXISTS(SELECT 1 FROM bans WHERE target_device_id = d.id AND active = 1
                               AND (expires_at IS NULL OR expires_at > ?)) AS banned
                    FROM devices d WHERE id = ?
                    """)) {
                statement.setString(1, Instant.now(clock).toString()); statement.setLong(2, deviceId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw notFound("Device was not found");
                    return new DeviceView(deviceId, result.getInt(1),
                            DeviceFingerprintService.Confidence.valueOf(result.getString(2)),
                            Instant.parse(result.getString(3)), Instant.parse(result.getString(4)),
                            result.getInt(5), result.getInt(6), result.getInt(7) == 1);
                }
            }
        });
    }

    public Long activeUserBanId(ModeratorIdentity moderator, long userId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.USER_UNBAN);
            return activeBanId(connection, "target_user_id", userId, Instant.now(clock));
        });
    }

    public boolean isUserBanned(long userId) {
        return database.transaction(connection -> hasActiveUserBan(connection, userId, Instant.now(clock)));
    }

    public Long activeDeviceBanId(ModeratorIdentity moderator, long deviceId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_UNBAN);
            return activeBanId(connection, "target_device_id", deviceId, Instant.now(clock));
        });
    }

    public List<Long> usersForDevice(ModeratorIdentity moderator, long deviceId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_BAN);
            requireDevice(connection, deviceId);
            return ids(connection, "SELECT user_id FROM user_devices WHERE device_id = ? ORDER BY user_id", deviceId);
        });
    }

    public List<Long> accountsForDevice(ModeratorIdentity moderator, long deviceId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            requireDevice(connection, deviceId);
            return ids(connection,
                    "SELECT account_id FROM account_devices WHERE device_id = ? ORDER BY account_id", deviceId);
        });
    }

    public void removeExemption(ModeratorIdentity moderator, long userId, long deviceId, String reason) {
        String safeReason = optionalReason(reason);
        database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_EXEMPTION);
            Instant now = Instant.now(clock);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE device_ban_exemptions SET active = 0, removed_by = ?, removed_at = ?, remove_reason = ?
                    WHERE user_id = ? AND device_id = ? AND active = 1
                    """)) {
                statement.setLong(1, moderator.telegramId()); statement.setString(2, now.toString());
                statement.setString(3, safeReason); statement.setLong(4, userId); statement.setLong(5, deviceId);
                statement.executeUpdate();
            }
            audit.record(connection, "BAN_EXEMPTION_REMOVED", null, null, moderator.telegramId(),
                    moderator.usernameSnapshot(), now, Map.of("userId", userId, "deviceId", deviceId));
            return null;
        });
    }

    public boolean hasActiveExemption(ModeratorIdentity moderator, long userId, long deviceId) {
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.DEVICE_EXEMPTION);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM device_ban_exemptions
                    WHERE user_id = ? AND device_id = ? AND active = 1
                    """)) {
                statement.setLong(1, userId);
                statement.setLong(2, deviceId);
                try (ResultSet result = statement.executeQuery()) { return result.next(); }
            }
        });
    }

    private Set<Long> directLinkedUsers(Connection connection, long targetUser, List<Long> devices)
            throws SQLException {
        if (devices.isEmpty()) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT ud.user_id FROM user_devices ud
                WHERE ud.device_id = ? AND ud.user_id <> ?
                  AND NOT EXISTS (SELECT 1 FROM device_ban_exemptions e
                      WHERE e.user_id = ud.user_id AND e.device_id = ud.device_id AND e.active = 1)
                ORDER BY ud.user_id
                """)) {
            for (long deviceId : devices) {
                statement.setLong(1, deviceId); statement.setLong(2, targetUser);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getLong(1)); }
            }
        }
        return result;
    }

    private int addAccountEffectsAndSync(Connection connection, long banId, long userId,
                                         SyncEventType type, ModeratorIdentity moderator, Instant now)
            throws Exception {
        int count = 0;
        try (PreparedStatement accounts = connection.prepareStatement(
                "SELECT id, enabled FROM minecraft_accounts WHERE owner_telegram_id = ? ORDER BY id");
             PreparedStatement effect = connection.prepareStatement("""
                     INSERT INTO ban_account_effects (ban_id, account_id, was_enabled_before_ban)
                     VALUES (?, ?, ?)
                     """)) {
            accounts.setLong(1, userId);
            try (ResultSet result = accounts.executeQuery()) {
                while (result.next()) {
                    long accountId = result.getLong(1);
                    effect.setLong(1, banId); effect.setLong(2, accountId); effect.setInt(3, result.getInt(2));
                    effect.addBatch();
                    syncEvents.enqueue(connection, type, Map.of("accountId", accountId, "banId", banId),
                            accountId, moderator.telegramId(), moderator.usernameSnapshot(), now);
                    count++;
                }
            }
            effect.executeBatch();
        }
        return count;
    }

    private void enqueueUserState(Connection connection, long userId, SyncEventType type, long banId,
                                  ModeratorIdentity moderator, Instant now) throws Exception {
        try (PreparedStatement accounts = connection.prepareStatement(
                "SELECT id FROM minecraft_accounts WHERE owner_telegram_id = ? ORDER BY id")) {
            accounts.setLong(1, userId);
            try (ResultSet result = accounts.executeQuery()) {
                while (result.next()) {
                    long accountId = result.getLong(1);
                    syncEvents.enqueue(connection, type, Map.of("accountId", accountId, "banId", banId),
                            accountId, moderator.telegramId(), moderator.usernameSnapshot(), now);
                }
            }
        }
    }

    private static long insertBan(Connection connection, BanType type, Long userId, Long deviceId,
                                  String reason, long creator, Instant now, Long origin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bans (ban_type, target_user_id, target_device_id, reason, created_by,
                                  created_at, active, origin_ban_id)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, type.name()); setLong(statement, 2, userId); setLong(statement, 3, deviceId);
            statement.setString(4, reason); statement.setLong(5, creator); statement.setString(6, now.toString());
            setLong(statement, 7, origin); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Ban insert returned no ID");
                return keys.getLong(1);
            }
        }
    }

    private static int deactivate(Connection connection, long banId, long moderatorId, String reason, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bans SET active = 0, removed_by = ?, removed_at = ?, remove_reason = ?
                WHERE id = ? AND active = 1
                """)) {
            statement.setLong(1, moderatorId); statement.setString(2, now.toString());
            statement.setString(3, reason); statement.setLong(4, banId); return statement.executeUpdate();
        }
    }

    private static List<BanRow> groupRows(Connection connection, BanRow selected) throws SQLException {
        long origin = selected.originId() == null ? selected.id() : selected.originId();
        List<BanRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, ban_type, target_user_id, target_device_id, origin_ban_id
                FROM bans WHERE active = 1 AND (id = ? OR origin_ban_id = ?) ORDER BY id
                """)) {
            statement.setLong(1, origin); statement.setLong(2, origin);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) rows.add(mapBan(result)); }
        }
        return rows;
    }

    private static BanRow requireActiveBan(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, ban_type, target_user_id, target_device_id, origin_ban_id
                FROM bans WHERE id = ? AND active = 1
                """)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw notFound("Active ban was not found");
                return mapBan(result);
            }
        }
    }

    private static BanRow mapBan(ResultSet result) throws SQLException {
        long user = result.getLong("target_user_id"); Long userId = result.wasNull() ? null : user;
        long device = result.getLong("target_device_id"); Long deviceId = result.wasNull() ? null : device;
        long origin = result.getLong("origin_ban_id"); Long originId = result.wasNull() ? null : origin;
        return new BanRow(result.getLong("id"), BanType.valueOf(result.getString("ban_type")),
                userId, deviceId, originId);
    }

    private static boolean hasActiveUserBan(Connection c, long id, Instant now) throws SQLException {
        return activeBanId(c, "target_user_id", id, now) != null;
    }
    private static Long activeBanId(Connection c, String column, long id, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM bans WHERE " + column
                + " = ? AND active = 1 AND (expires_at IS NULL OR expires_at > ?) LIMIT 1")) {
            s.setLong(1, id); s.setString(2, now.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : null; }
        }
    }
    private static List<Long> ids(Connection c, String sql, long id) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) { s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(r.getLong(1)); } }
        return List.copyOf(result);
    }
    private static int count(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); } }
    }
    private static int uncheckedCount(Connection c, String sql, long id) {
        try { return count(c, sql, id); }
        catch (SQLException e) { throw new BackendDatabase.DatabaseException("Ban impact query failed", e); }
    }
    private static void requireUser(Connection c, long id) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM users WHERE telegram_id = ?", id) != 1) throw notFound("User was not found");
    }
    private static void requireDevice(Connection c, long id) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM devices WHERE id = ?", id) != 1) throw notFound("Device was not found");
    }
    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, java.sql.Types.BIGINT); else s.setLong(index, value);
    }
    private static String reason(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > 1000) throw invalid("Ban reason must contain 1-1000 characters");
        return result;
    }
    private static String optionalReason(String value) {
        String result = value == null ? "" : value.strip();
        if (result.length() > 1000) throw invalid("Removal reason is too long");
        return result;
    }
    private static WorkflowException invalid(String m) { return new WorkflowException(WorkflowException.Code.INVALID_INPUT, m); }
    private static WorkflowException conflict(String m) { return new WorkflowException(WorkflowException.Code.CONFLICT, m); }
    private static WorkflowException notFound(String m) { return new WorkflowException(WorkflowException.Code.NOT_FOUND, m); }

    public record BanImpact(long targetUserId, int ownAccountCount, List<Long> deviceIds,
                            List<Long> directlyLinkedUserIds, int linkedAccountCount) { }
    public record BanResult(long originBanId, List<Long> linkedBanIds, int affectedAccountCount) { }
    public record DeviceView(long id, int fingerprintVersion, DeviceFingerprintService.Confidence confidence,
                             Instant firstSeen, Instant lastSeen, int knownUsers, int knownAccounts,
                             boolean activelyBanned) { }
    private record BanRow(long id, BanType type, Long userId, Long deviceId, Long originId) { }
    public enum BanType { USER_BAN, DEVICE_BAN, LINKED_DEVICE_BAN }
}
