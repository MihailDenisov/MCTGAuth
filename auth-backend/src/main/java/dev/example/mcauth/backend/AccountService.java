package dev.example.mcauth.backend;

import dev.example.mcauth.api.AccountCreationMethod;
import dev.example.mcauth.api.SyncEventType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AccountService {
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Duration PASSWORD_CHANGE_MIN_INTERVAL = Duration.ofMinutes(10);
    private static final Duration PASSWORD_CHANGE_WINDOW = Duration.ofHours(24);
    private static final int PASSWORD_CHANGE_WINDOW_LIMIT = 5;

    private final BackendDatabase database;
    private final CredentialService credentials;
    private final SyncEventService syncEvents;
    private final AuditService audit;
    private final ModeratorService moderators;
    private final Clock clock;

    public AccountService(BackendDatabase database) {
        this(database, new CredentialService(), null, null, null, Clock.systemUTC());
    }

    public AccountService(
            BackendDatabase database,
            CredentialService credentials,
            SyncEventService syncEvents,
            AuditService audit,
            ModeratorService moderators,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.syncEvents = syncEvents;
        this.audit = audit;
        this.moderators = moderators;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean hasEnabledAccount(long telegramId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM minecraft_accounts WHERE owner_telegram_id = ? AND enabled = 1 LIMIT 1")) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    public boolean touchTelegramUser(long telegramId, String username) {
        return touchTelegramUser(telegramId, username, "en");
    }

    public boolean touchTelegramUser(long telegramId, String username, String initialLocale) {
        if (telegramId <= 0) throw invalid("Invalid Telegram identity");
        String safeUsername = username == null ? "" : username.strip();
        if (safeUsername.length() > 64) throw invalid("Telegram username is too long");
        if (!"en".equals(initialLocale) && !"ru".equals(initialLocale)) {
            throw invalid("Unsupported initial locale");
        }
        return database.transaction(connection -> {
            Instant now = Instant.now(clock);
            boolean created;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO users
                        (telegram_id, username_snapshot, account_status, locale, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, ?, ?)
                    """)) {
                statement.setLong(1, telegramId);
                statement.setString(2, safeUsername);
                statement.setString(3, initialLocale);
                statement.setString(4, now.toString());
                statement.setString(5, now.toString());
                created = statement.executeUpdate() == 1;
            }
            if (!created) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE users SET username_snapshot = ?, updated_at = ? WHERE telegram_id = ?
                        """)) {
                    statement.setString(1, safeUsername);
                    statement.setString(2, now.toString());
                    statement.setLong(3, telegramId);
                    statement.executeUpdate();
                }
            }
            return created;
        });
    }

    public List<AccountView> accountsFor(long telegramId) {
        return database.transaction(connection -> {
            List<AccountView> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, owner_telegram_id, nickname, licensed, enabled, creation_method,
                           credential_version, created_at
                    FROM minecraft_accounts
                    WHERE owner_telegram_id = ?
                    ORDER BY id
                    """)) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        accounts.add(mapAccount(result));
                    }
                }
            }
            return List.copyOf(accounts);
        });
    }

    public CredentialIssueResult changePassword(long ownerTelegramId, long accountId) {
        requirePhaseTwoServices();
        return database.transaction(connection -> {
            AccountView account = requireOwnedAccount(connection, ownerTelegramId, accountId);
            Instant now = Instant.now(clock);
            enforcePasswordRateLimit(connection, accountId, now);
            char[] password = credentials.generatePassword();
            try {
                String verifier = credentials.hash(password);
                int version = account.credentialVersion() + 1;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE minecraft_accounts
                        SET credential_verifier = ?, credential_version = ?, updated_at = ?
                        WHERE id = ? AND owner_telegram_id = ?
                        """)) {
                    statement.setString(1, verifier);
                    statement.setInt(2, version);
                    statement.setString(3, now.toString());
                    statement.setLong(4, accountId);
                    statement.setLong(5, ownerTelegramId);
                    statement.executeUpdate();
                }
                recordPasswordChange(connection, accountId, ownerTelegramId, version, now);
                syncEvents.enqueue(connection, SyncEventType.CREDENTIAL_CHANGED,
                        credentialPayload(accountId, account.nickname(), account.enabled(), version, verifier),
                        accountId, ownerTelegramId, "", now);
                audit.record(connection, "PASSWORD_CHANGED", null, accountId, ownerTelegramId, "", now,
                        Map.of("credentialVersion", version));
                return new CredentialIssueResult(accountId, account.nickname(), version, password);
            } catch (Exception exception) {
                Arrays.fill(password, '\0');
                throw exception;
            }
        });
    }

    public CredentialIssueResult createManual(
            ModeratorIdentity moderator, long ownerTelegramId, String nickname, boolean licensed) {
        requirePhaseTwoServices();
        String displayName = validateNickname(nickname);
        String normalizedName = displayName.toLowerCase(Locale.ROOT);
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.ACCOUNT_CREATE_MANUAL);
            requireUser(connection, ownerTelegramId);
            requireNicknameAvailable(connection, normalizedName);
            Instant now = Instant.now(clock);
            char[] password = credentials.generatePassword();
            try {
                String verifier = credentials.hash(password);
                long accountId = insertAccount(connection, ownerTelegramId, displayName, normalizedName,
                        licensed, AccountCreationMethod.ADMIN_MANUAL, moderator.telegramId(), verifier, now);
                syncEvents.enqueue(connection, SyncEventType.ACCOUNT_CREATED,
                        accountPayload(accountId, displayName, normalizedName, licensed, true, 1, verifier),
                        accountId, moderator.telegramId(), moderator.usernameSnapshot(), now);
                audit.record(connection, "MINECRAFT_ACCOUNT_CREATED_MANUALLY", null, accountId,
                        moderator.telegramId(), moderator.usernameSnapshot(), now,
                        Map.of("ownerTelegramId", ownerTelegramId, "nickname", displayName));
                return new CredentialIssueResult(accountId, displayName, 1, password);
            } catch (Exception exception) {
                Arrays.fill(password, '\0');
                throw exception;
            }
        });
    }

    AccountView requireOwnedAccount(Connection connection, long ownerTelegramId, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, owner_telegram_id, nickname, licensed, enabled, creation_method,
                       credential_version, created_at
                FROM minecraft_accounts WHERE id = ? AND owner_telegram_id = ?
                """)) {
            statement.setLong(1, accountId);
            statement.setLong(2, ownerTelegramId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND,
                            "Minecraft account was not found");
                }
                return mapAccount(result);
            }
        }
    }

    static long insertAccount(
            Connection connection,
            long ownerTelegramId,
            String nickname,
            String normalizedNickname,
            boolean licensed,
            AccountCreationMethod creationMethod,
            Long moderatorId,
            String verifier,
            Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO minecraft_accounts
                    (owner_telegram_id, nickname, normalized_nickname, licensed, enabled,
                     creation_method, creator_moderator_id, credential_verifier, credential_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, ownerTelegramId);
            statement.setString(2, nickname);
            statement.setString(3, normalizedNickname);
            statement.setInt(4, licensed ? 1 : 0);
            statement.setString(5, creationMethod.name());
            if (moderatorId == null) statement.setNull(6, java.sql.Types.BIGINT); else statement.setLong(6, moderatorId);
            statement.setString(7, verifier);
            statement.setString(8, now.toString());
            statement.setString(9, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Account insert returned no ID");
                return keys.getLong(1);
            }
        }
    }

    static Map<String, Object> accountPayload(
            long accountId, String nickname, String normalizedNickname, boolean licensed,
            boolean enabled, int version, String verifier) {
        return Map.of(
                "accountId", accountId,
                "nickname", nickname,
                "normalizedNickname", normalizedNickname,
                "licensed", licensed,
                "enabled", enabled,
                "credentialVersion", version,
                "credentialVerifier", verifier);
    }

    private static Map<String, Object> credentialPayload(
            long accountId, String nickname, boolean enabled, int version, String verifier) {
        return Map.of(
                "accountId", accountId,
                "nickname", nickname,
                "enabled", enabled,
                "credentialVersion", version,
                "credentialVerifier", verifier);
    }

    private static AccountView mapAccount(ResultSet result) throws SQLException {
        return new AccountView(
                result.getLong("id"),
                result.getLong("owner_telegram_id"),
                result.getString("nickname"),
                result.getInt("licensed") == 1,
                result.getInt("enabled") == 1,
                AccountCreationMethod.valueOf(result.getString("creation_method")),
                result.getInt("credential_version"),
                Instant.parse(result.getString("created_at")));
    }

    private static void requireUser(Connection connection, long telegramId) throws SQLException {
        if (telegramId <= 0) {
            throw invalid("Owner Telegram ID must be positive");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM users WHERE telegram_id = ?")) {
            statement.setLong(1, telegramId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND,
                            "The owner must start the bot before an account can be assigned");
                }
            }
        }
    }

    static void requireNicknameAvailable(Connection connection, String normalizedNickname) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM minecraft_accounts WHERE normalized_nickname = ?")) {
            statement.setString(1, normalizedNickname);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new WorkflowException(WorkflowException.Code.CONFLICT,
                            "That Minecraft nickname is already registered");
                }
            }
        }
    }

    static String validateNickname(String nickname) {
        String value = nickname == null ? "" : nickname.strip();
        if (!MINECRAFT_NAME.matcher(value).matches()) {
            throw invalid("Minecraft nickname must contain 3-16 letters, digits, or underscores");
        }
        return value;
    }

    private static void enforcePasswordRateLimit(Connection connection, long accountId, Instant now)
            throws SQLException {
        try (PreparedStatement latest = connection.prepareStatement("""
                SELECT created_at FROM password_changes WHERE account_id = ? ORDER BY id DESC LIMIT 1
                """)) {
            latest.setLong(1, accountId);
            try (ResultSet result = latest.executeQuery()) {
                if (result.next() && Instant.parse(result.getString(1))
                        .isAfter(now.minus(PASSWORD_CHANGE_MIN_INTERVAL))) {
                    throw new WorkflowException(WorkflowException.Code.RATE_LIMITED,
                            "Password was changed recently; try again later");
                }
            }
        }
        try (PreparedStatement count = connection.prepareStatement("""
                SELECT COUNT(*) FROM password_changes WHERE account_id = ? AND created_at >= ?
                """)) {
            count.setLong(1, accountId);
            count.setString(2, now.minus(PASSWORD_CHANGE_WINDOW).toString());
            try (ResultSet result = count.executeQuery()) {
                if (result.next() && result.getInt(1) >= PASSWORD_CHANGE_WINDOW_LIMIT) {
                    throw new WorkflowException(WorkflowException.Code.RATE_LIMITED,
                            "Daily password change limit reached; try again later");
                }
            }
        }
    }

    private static void recordPasswordChange(
            Connection connection, long accountId, long actorId, int version, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO password_changes
                    (account_id, requested_by_telegram_id, credential_version, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, accountId);
            statement.setLong(2, actorId);
            statement.setInt(3, version);
            statement.setString(4, now.toString());
            statement.executeUpdate();
        }
    }

    private void requirePhaseTwoServices() {
        if (syncEvents == null || audit == null || moderators == null) {
            throw new IllegalStateException("AccountService phase-two dependencies are not configured");
        }
    }

    private static WorkflowException invalid(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_INPUT, message);
    }
}
