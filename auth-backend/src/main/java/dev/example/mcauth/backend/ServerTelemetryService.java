package dev.example.mcauth.backend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative backend storage and aggregation for authenticated Minecraft server telemetry. */
public final class ServerTelemetryService {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private final BackendDatabase database;
    private final Clock clock;
    private final String configuredServerId;
    private final Duration heartbeatTimeout;

    public ServerTelemetryService(BackendDatabase database, Clock clock, String configuredServerId,
                                  Duration heartbeatTimeout) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuredServerId = requireServerId(configuredServerId);
        if (heartbeatTimeout == null || heartbeatTimeout.compareTo(Duration.ofSeconds(10)) < 0
                || heartbeatTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Heartbeat timeout must be between 10 seconds and 1 hour");
        }
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public boolean heartbeat(Heartbeat heartbeat) {
        validateIdentity(heartbeat.serverId());
        validateTimes(heartbeat.startedAt(), heartbeat.occurredAt());
        if (heartbeat.onlinePlayers() < 0 || heartbeat.maxPlayers() < 0
                || heartbeat.onlinePlayers() > heartbeat.maxPlayers()) {
            throw invalid("Invalid player counts");
        }
        return database.transaction(connection -> {
            StatusRow previous = statusRow(connection, heartbeat.serverId());
            boolean sameInstance = previous != null
                    && previous.serverInstanceId().equals(heartbeat.serverInstanceId().toString());
            if (previous != null && !sameInstance) {
                if (!heartbeat.startedAt().isAfter(previous.startedAt())) return false;
                Instant closedAt = earlier(previous.lastHeartbeatAt(), heartbeat.occurredAt());
                closeInstance(connection, heartbeat.serverId(), previous.serverInstanceId(), closedAt, "SERVER_LOST");
            } else if (previous != null && heartbeat.occurredAt().isBefore(previous.lastHeartbeatAt())) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO server_status(server_id, server_instance_id, started_at, last_heartbeat_at,
                                              online_players, max_players, shutdown_at)
                    VALUES (?, ?, ?, ?, ?, ?, NULL)
                    ON CONFLICT(server_id) DO UPDATE SET
                      server_instance_id=excluded.server_instance_id,
                      started_at=CASE
                        WHEN server_status.server_instance_id=excluded.server_instance_id
                        THEN server_status.started_at ELSE excluded.started_at END,
                      last_heartbeat_at=excluded.last_heartbeat_at, online_players=excluded.online_players,
                      max_players=excluded.max_players, shutdown_at=NULL
                    """)) {
                statement.setString(1, heartbeat.serverId());
                statement.setString(2, heartbeat.serverInstanceId().toString());
                statement.setString(3, heartbeat.startedAt().toString());
                statement.setString(4, heartbeat.occurredAt().toString());
                statement.setInt(5, heartbeat.onlinePlayers());
                statement.setInt(6, heartbeat.maxPlayers());
                int affected = statement.executeUpdate();
                if (affected != 1) {
                    throw new BackendDatabase.DatabaseException(
                            "Heartbeat persistence affected an unexpected row count",
                            new SQLException("Expected one server_status row, affected " + affected));
                }
            }
            return true;
        });
    }

    public boolean playerEvent(PlayerEvent event) {
        validateIdentity(event.serverId());
        if (event.minecraftAccountId() <= 0 || event.nickname() == null || event.nickname().isBlank()
                || event.nickname().length() > 16 || event.occurredAt() == null) throw invalid("Invalid player event");
        validateOccurredAt(event.occurredAt());
        return database.transaction(connection -> {
            if (!accountExists(connection, event.minecraftAccountId())) throw invalid("Unknown Minecraft account");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO telemetry_events
                      (event_id, server_id, server_instance_id, event_type, occurred_at, processed_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, event.eventId().toString());
                insert.setString(2, event.serverId());
                insert.setString(3, event.serverInstanceId().toString());
                insert.setString(4, event.type().name());
                insert.setString(5, event.occurredAt().toString());
                insert.setString(6, Instant.now(clock).toString());
                if (insert.executeUpdate() == 0) return false;
            }
            StatusRow current = statusRow(connection, event.serverId());
            boolean currentInstance = current == null
                    || current.serverInstanceId().equals(event.serverInstanceId().toString());
            if (event.type() == PlayerEventType.PLAYER_JOINED) {
                if (currentInstance) openSession(connection, event);
            } else {
                closeSession(connection, event);
            }
            return true;
        });
    }

    public void shutdown(String serverId, UUID instanceId, Instant occurredAt) {
        validateIdentity(serverId);
        validateOccurredAt(occurredAt);
        database.transaction(connection -> {
            closeInstance(connection, serverId, instanceId.toString(), occurredAt, "SERVER_SHUTDOWN");
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE server_status SET shutdown_at=?, last_heartbeat_at=?, online_players=0
                    WHERE server_id=? AND server_instance_id=?
                    """)) {
                statement.setString(1, occurredAt.toString());
                statement.setString(2, occurredAt.toString());
                statement.setString(3, serverId);
                statement.setString(4, instanceId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public ServerStatus status(String serverId) {
        validateIdentity(serverId);
        return database.transaction(connection -> {
            StatusRow row = statusRow(connection, serverId);
            if (row == null) return ServerStatus.unknown(serverId);
            Instant now = Instant.now(clock);
            boolean fresh = row.shutdownAt() == null && row.lastHeartbeatAt().plus(heartbeatTimeout).isAfter(now);
            return new ServerStatus(serverId, fresh, row.lastHeartbeatAt(),
                    fresh ? row.onlinePlayers() : 0, fresh ? row.maxPlayers() : 0);
        });
    }

    public List<OnlinePlayer> onlinePlayers(String serverId) {
        ServerStatus status = status(serverId);
        if (!status.online()) return List.of();
        Instant now = Instant.now(clock);
        return database.transaction(connection -> {
            List<OnlinePlayer> players = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT minecraft_account_id, nickname_snapshot, joined_at FROM player_sessions
                    WHERE server_id=? AND left_at IS NULL ORDER BY joined_at, minecraft_account_id
                    """)) {
                statement.setString(1, serverId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) players.add(new OnlinePlayer(result.getLong(1), result.getString(2),
                            Duration.between(Instant.parse(result.getString(3)), now)));
                }
            }
            return players.size() == status.onlinePlayers() ? List.copyOf(players) : List.of();
        });
    }

    public Playtime playtime(long ownerTelegramId, long accountId) {
        return database.transaction(connection -> {
            try (PreparedStatement ownership = connection.prepareStatement(
                    "SELECT nickname FROM minecraft_accounts WHERE id=? AND owner_telegram_id=?")) {
                ownership.setLong(1, accountId);
                ownership.setLong(2, ownerTelegramId);
                try (ResultSet result = ownership.executeQuery()) {
                    if (!result.next()) throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "Account not found");
                    return aggregate(connection, accountId, result.getString(1), Instant.now(clock));
                }
            }
        });
    }

    public List<PlayerStatistic> playerStatistics(ModeratorIdentity moderator, ModeratorService moderators,
                                                   int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 50) throw invalid("Invalid page");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.PLAYER_STATISTICS);
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.id FROM minecraft_accounts a
                    LEFT JOIN player_sessions s ON s.minecraft_account_id=a.id
                    GROUP BY a.id ORDER BY MAX(COALESCE(s.left_at, s.joined_at)) DESC, a.id
                    LIMIT ? OFFSET ?
                    """)) {
                statement.setInt(1, pageSize);
                statement.setInt(2, page * pageSize);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getLong(1)); }
            }
            Instant now = Instant.now(clock);
            List<PlayerStatistic> stats = new ArrayList<>();
            for (long id : ids) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT nickname FROM minecraft_accounts WHERE id=?")) {
                    statement.setLong(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            Playtime playtime = aggregate(connection, id, result.getString(1), now);
                            stats.add(new PlayerStatistic(id, playtime.nickname(), playtime.allTime(),
                                    playtime.online(), playtime.lastSeen()));
                        }
                    }
                }
            }
            return List.copyOf(stats);
        });
    }

    Instant currentTime() { return Instant.now(clock); }

    private Playtime aggregate(Connection connection, long accountId, String nickname, Instant now) throws SQLException {
        List<Interval> intervals = new ArrayList<>();
        boolean online = false;
        Instant lastSeen = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.joined_at, s.left_at, ss.last_heartbeat_at, ss.shutdown_at
                FROM player_sessions s
                LEFT JOIN server_status ss ON ss.server_id=s.server_id
                    AND ss.server_instance_id=s.server_instance_id
                WHERE s.minecraft_account_id=? ORDER BY s.joined_at, s.left_at
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Instant start = Instant.parse(result.getString(1));
                    String endText = result.getString(2);
                    Instant end;
                    boolean sessionOnline = false;
                    if (endText == null) {
                        String heartbeatText = result.getString(3);
                        String shutdownText = result.getString(4);
                        if (shutdownText != null) end = Instant.parse(shutdownText);
                        else if (heartbeatText != null) {
                            Instant heartbeat = Instant.parse(heartbeatText);
                            if (heartbeat.plus(heartbeatTimeout).isAfter(now)) {
                                end = now;
                                sessionOnline = true;
                            } else end = heartbeat;
                        } else end = start;
                    } else end = Instant.parse(endText);
                    online |= sessionOnline;
                    if (!sessionOnline && (lastSeen == null || end.isAfter(lastSeen))) lastSeen = end;
                    if (end.isAfter(start)) intervals.add(new Interval(start, end));
                }
            }
        }
        intervals.sort(Comparator.comparing(Interval::start));
        Duration all = unionDuration(intervals, Instant.MIN);
        Duration day = unionDuration(intervals, now.minus(Duration.ofHours(24)));
        Duration week = unionDuration(intervals, now.minus(Duration.ofDays(7)));
        return new Playtime(accountId, nickname, day, week, all, online, online ? null : lastSeen);
    }

    private static Duration unionDuration(List<Interval> intervals, Instant since) {
        Instant start = null, end = null;
        Duration total = Duration.ZERO;
        for (Interval interval : intervals) {
            Instant nextStart = interval.start().isBefore(since) ? since : interval.start();
            if (!interval.end().isAfter(nextStart)) continue;
            if (start == null) { start = nextStart; end = interval.end(); continue; }
            if (!nextStart.isAfter(end)) { if (interval.end().isAfter(end)) end = interval.end(); }
            else { total = total.plus(Duration.between(start, end)); start = nextStart; end = interval.end(); }
        }
        return start == null ? total : total.plus(Duration.between(start, end));
    }

    private void openSession(Connection connection, PlayerEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO player_sessions(join_event_id, server_id, server_instance_id,
                    minecraft_account_id, nickname_snapshot, joined_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventId().toString()); statement.setString(2, event.serverId());
            statement.setString(3, event.serverInstanceId().toString()); statement.setLong(4, event.minecraftAccountId());
            statement.setString(5, event.nickname()); statement.setString(6, event.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    private void closeSession(Connection connection, PlayerEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_sessions SET leave_event_id=?, left_at=?, close_reason='PLAYER_LEFT'
                WHERE id=(SELECT id FROM player_sessions WHERE server_id=? AND server_instance_id=?
                          AND minecraft_account_id=? AND left_at IS NULL ORDER BY joined_at DESC LIMIT 1)
                """)) {
            statement.setString(1, event.eventId().toString()); statement.setString(2, event.occurredAt().toString());
            statement.setString(3, event.serverId()); statement.setString(4, event.serverInstanceId().toString());
            statement.setLong(5, event.minecraftAccountId()); statement.executeUpdate();
        }
    }

    private static void closeInstance(Connection connection, String serverId, String instanceId,
                                      Instant at, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_sessions SET left_at=CASE WHEN joined_at > ? THEN joined_at ELSE ? END, close_reason=?
                WHERE server_id=? AND server_instance_id=? AND left_at IS NULL
                """)) {
            statement.setString(1, at.toString()); statement.setString(2, at.toString()); statement.setString(3, reason);
            statement.setString(4, serverId); statement.setString(5, instanceId); statement.executeUpdate();
        }
    }

    private static boolean accountExists(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM minecraft_accounts WHERE id=?")) {
            statement.setLong(1, id); try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static StatusRow statusRow(Connection connection, String serverId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT server_instance_id,started_at,last_heartbeat_at,online_players,max_players,shutdown_at
                FROM server_status WHERE server_id=?
                """)) {
            statement.setString(1, serverId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? new StatusRow(r.getString(1), Instant.parse(r.getString(2)),
                        Instant.parse(r.getString(3)), r.getInt(4), r.getInt(5),
                        r.getString(6) == null ? null : Instant.parse(r.getString(6))) : null;
            }
        }
    }

    private void validateIdentity(String serverId) {
        if (!configuredServerId.equals(requireServerId(serverId)))
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Server ID mismatch");
    }
    private void validateTimes(Instant startedAt, Instant occurredAt) {
        if (startedAt == null || occurredAt == null || startedAt.isAfter(occurredAt)) throw invalid("Invalid timestamps");
        validateOccurredAt(occurredAt);
    }
    private void validateOccurredAt(Instant occurredAt) {
        if (occurredAt.isAfter(Instant.now(clock).plus(MAX_CLOCK_SKEW))) throw invalid("Event time is in the future");
    }
    private static String requireServerId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Invalid server ID");
        return value;
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }

    public enum PlayerEventType { PLAYER_JOINED, PLAYER_LEFT }
    public record Heartbeat(String serverId, UUID serverInstanceId, Instant startedAt, Instant occurredAt,
                            int onlinePlayers, int maxPlayers) { }
    public record PlayerEvent(UUID eventId, String serverId, UUID serverInstanceId, PlayerEventType type,
                              long minecraftAccountId, String nickname, Instant occurredAt) { }
    public record ServerStatus(String serverId, boolean online, Instant lastHeartbeatAt,
                               int onlinePlayers, int maxPlayers) {
        static ServerStatus unknown(String serverId) { return new ServerStatus(serverId, false, null, 0, 0); }
    }
    public record OnlinePlayer(long accountId, String nickname, Duration sessionDuration) { }
    public record Playtime(long accountId, String nickname, Duration last24Hours, Duration last7Days,
                           Duration allTime, boolean online, Instant lastSeen) { }
    public record PlayerStatistic(long accountId, String nickname, Duration allTime,
                                  boolean online, Instant lastSeen) { }
    private record StatusRow(String serverInstanceId, Instant startedAt, Instant lastHeartbeatAt, int onlinePlayers,
                             int maxPlayers, Instant shutdownAt) { }
    private record Interval(Instant start, Instant end) { }
}
