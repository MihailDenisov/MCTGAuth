package dev.example.mcauth.backend;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DeviceFingerprintService {
    public static final int DIGEST_BYTES = 32;
    public static final int MAX_SIGNALS = 6;
    private final BackendDatabase database;
    private final Clock clock;
    private final String serverId;
    private final byte[] secret;
    private final DeviceFingerprintConfig config;

    public DeviceFingerprintService(BackendDatabase database, Clock clock, String serverId,
                                    String secret, DeviceFingerprintConfig config) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
        this.config = Objects.requireNonNull(config, "config");
    }

    public Resolution observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!serverId.equals(observation.serverId())) throw unauthorized("Wrong server ID");
        if (!config.enabled()) {
            if (observation.accountId() <= 0 || observation.normalizedMinecraftName() == null) {
                throw invalid("Invalid device observation");
            }
            return database.transaction(connection -> {
                long ownerId = requireAccountOwner(connection, observation.accountId(),
                        observation.normalizedMinecraftName());
                return new Resolution(null, Confidence.LOW,
                        activeBan(connection, "target_user_id", ownerId, Instant.now(clock))
                                ? Decision.USER_BANNED : Decision.ALLOWED, false);
            });
        }
        validate(observation);
        Map<DeviceFingerprintConfig.SignalType, String> tokens = tokenize(observation.signals());
        return database.transaction(connection -> resolve(connection, observation, tokens, Instant.now(clock)));
    }

    private Resolution resolve(Connection connection, Observation observation,
                               Map<DeviceFingerprintConfig.SignalType, String> tokens, Instant now)
            throws SQLException {
        long ownerId = requireAccountOwner(connection, observation.accountId(), observation.normalizedMinecraftName());
        Map<Long, Match> matches = candidateMatches(connection, observation.fingerprintVersion(), tokens);
        Match best = matches.values().stream().max(java.util.Comparator
                .comparingDouble(Match::ratio).thenComparingLong(match -> -match.deviceId())).orElse(null);
        Confidence confidence = confidence(best);
        long deviceId;
        boolean created;
        if (best != null && best.strongMatched() && best.ratio() >= 0.50d) {
            deviceId = best.deviceId();
            created = false;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE devices SET last_seen_at = ?, confidence = ? WHERE id = ?")) {
                update.setString(1, now.toString());
                update.setString(2, confidence.name());
                update.setLong(3, deviceId);
                update.executeUpdate();
            }
        } else {
            confidence = Confidence.LOW;
            deviceId = insertDevice(connection, observation.fingerprintVersion(), confidence, now);
            created = true;
        }
        upsertSignals(connection, deviceId, tokens, now);
        upsertRelationship(connection, "user_devices", "user_id", ownerId, deviceId, now);
        upsertRelationship(connection, "account_devices", "account_id", observation.accountId(), deviceId, now);
        if (activeBan(connection, "target_user_id", ownerId, now)) {
            return new Resolution(deviceId, confidence, Decision.USER_BANNED, created);
        }
        if (config.deviceBanEnabled() && activeBan(connection, "target_device_id", deviceId, now)) {
            return new Resolution(deviceId, confidence, Decision.DEVICE_BANNED, created);
        }
        return new Resolution(deviceId, confidence, Decision.ALLOWED, created);
    }

    private Map<Long, Match> candidateMatches(Connection connection, int version,
                                               Map<DeviceFingerprintConfig.SignalType, String> tokens)
            throws SQLException {
        Map<Long, MutableMatch> matches = new HashMap<>();
        int denominator = tokens.keySet().stream().mapToInt(type -> config.weights().get(type)).sum();
        String sql = """
                SELECT ds.device_id FROM device_signals ds
                JOIN devices d ON d.id = ds.device_id
                WHERE d.fingerprint_version = ? AND ds.signal_type = ? AND ds.signal_hash = ?
                """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            for (Map.Entry<DeviceFingerprintConfig.SignalType, String> signal : tokens.entrySet()) {
                query.setInt(1, version);
                query.setString(2, signal.getKey().name());
                query.setString(3, signal.getValue());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        MutableMatch match = matches.computeIfAbsent(result.getLong(1), ignored -> new MutableMatch());
                        match.weight += config.weights().get(signal.getKey());
                        match.strong |= signal.getKey().strong();
                    }
                }
            }
        }
        Map<Long, Match> result = new HashMap<>();
        matches.forEach((id, match) -> result.put(id,
                new Match(id, denominator == 0 ? 0 : (double) match.weight / denominator, match.strong)));
        return result;
    }

    private Confidence confidence(Match match) {
        if (match == null || !match.strongMatched()) return Confidence.LOW;
        if (match.ratio() >= 0.75d) return Confidence.HIGH;
        return match.ratio() >= 0.50d ? Confidence.MEDIUM : Confidence.LOW;
    }

    private Map<DeviceFingerprintConfig.SignalType, String> tokenize(List<ObservedSignal> signals) {
        EnumMap<DeviceFingerprintConfig.SignalType, String> result = new EnumMap<>(DeviceFingerprintConfig.SignalType.class);
        for (ObservedSignal signal : signals) result.put(signal.type(), hmac(signal.clientDigest()));
        return Map.copyOf(result);
    }

    private String hmac(byte[] clientDigest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(clientDigest));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Device tokenization is unavailable", exception);
        }
    }

    private void validate(Observation observation) {
        if (observation.accountId() <= 0 || observation.fingerprintVersion() != config.fingerprintVersion()
                || observation.signals().isEmpty() || observation.signals().size() > MAX_SIGNALS) {
            throw invalid("Invalid device observation");
        }
        Set<DeviceFingerprintConfig.SignalType> seen = new HashSet<>();
        long strong = observation.signals().stream().filter(signal -> signal.type().strong()).count();
        if (strong == 0) throw invalid("At least one strong device signal is required");
        for (ObservedSignal signal : observation.signals()) {
            if (!seen.add(signal.type()) || signal.clientDigest().length != DIGEST_BYTES) {
                throw invalid("Invalid device signals");
            }
        }
    }

    private static long requireAccountOwner(Connection connection, long accountId, String normalizedName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_telegram_id, normalized_nickname FROM minecraft_accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getString(2).equals(normalizedName)) {
                    throw invalid("Account observation mismatch");
                }
                return result.getLong(1);
            }
        }
    }

    private static long insertDevice(Connection connection, int version, Confidence confidence, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO devices (fingerprint_version, confidence, created_at, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, version);
            statement.setString(2, confidence.name());
            for (int index = 3; index <= 5; index++) statement.setString(index, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Device insert returned no ID");
                return keys.getLong(1);
            }
        }
    }

    private static void upsertSignals(Connection connection, long deviceId,
                                      Map<DeviceFingerprintConfig.SignalType, String> tokens, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO device_signals
                    (device_id, signal_type, signal_hash, strong, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id, signal_type, signal_hash) DO UPDATE SET last_seen_at = excluded.last_seen_at
                """)) {
            for (Map.Entry<DeviceFingerprintConfig.SignalType, String> signal : tokens.entrySet()) {
                statement.setLong(1, deviceId);
                statement.setString(2, signal.getKey().name());
                statement.setString(3, signal.getValue());
                statement.setInt(4, signal.getKey().strong() ? 1 : 0);
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void upsertRelationship(Connection connection, String table, String key,
                                           long subjectId, long deviceId, Instant now) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + key + ", device_id, first_seen_at, last_seen_at, login_count) "
                + "VALUES (?, ?, ?, ?, 1) ON CONFLICT(" + key + ", device_id) DO UPDATE SET "
                + "last_seen_at = excluded.last_seen_at, login_count = login_count + 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, subjectId);
            statement.setLong(2, deviceId);
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.executeUpdate();
        }
    }

    private static boolean activeBan(Connection connection, String targetColumn, long targetId, Instant now)
            throws SQLException {
        String sql = "SELECT 1 FROM bans WHERE " + targetColumn
                + " = ? AND active = 1 AND (expires_at IS NULL OR expires_at > ?) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, targetId);
            statement.setString(2, now.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }
    private static WorkflowException unauthorized(String message) {
        return new WorkflowException(WorkflowException.Code.UNAUTHORIZED, message);
    }

    public record Observation(String serverId, long accountId, String normalizedMinecraftName,
                              int fingerprintVersion, List<ObservedSignal> signals) {
        public Observation { signals = List.copyOf(signals); }
    }
    public record ObservedSignal(DeviceFingerprintConfig.SignalType type, byte[] clientDigest) {
        public ObservedSignal {
            Objects.requireNonNull(type, "type");
            clientDigest = Objects.requireNonNull(clientDigest, "clientDigest").clone();
        }
        @Override public byte[] clientDigest() { return clientDigest.clone(); }
    }
    public record Resolution(Long deviceId, Confidence confidence, Decision decision, boolean created) { }
    public enum Confidence { HIGH, MEDIUM, LOW }
    public enum Decision { ALLOWED, USER_BANNED, DEVICE_BANNED }
    private record Match(long deviceId, double ratio, boolean strongMatched) { }
    private static final class MutableMatch { int weight; boolean strong; }
}
