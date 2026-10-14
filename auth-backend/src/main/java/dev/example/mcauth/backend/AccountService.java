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

    public ChatTagPreference chatTagPreference(long telegramId) {
        if (telegramId <= 0) throw invalid("Invalid Telegram identity");
        return database.transaction(connection -> loadChatTagPreference(connection, telegramId, true));
    }

    public ChatTagPreference selectChatTag(long telegramId, Long accountId) {
        if (telegramId <= 0) throw invalid("Invalid Telegram identity");
        if (accountId != null && accountId <= 0) throw invalid("Minecraft account ID must be positive");
        return database.transaction(connection -> {
            if (accountId != null) {
                AccountView account = requireOwnedAccount(connection, telegramId, accountId);
                if (!account.enabled()) {
                    throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                            "Disabled Minecraft accounts cannot be selected as chat tags");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE users SET chat_tag_account_id = ?, chat_tag_managed = 1, updated_at = ?
                    WHERE telegram_id = ?
                    """)) {
                if (accountId == null) statement.setNull(1, java.sql.Types.BIGINT);
                else statement.setLong(1, accountId);
                statement.setString(2, Instant.now(clock).toString());
                statement.setLong(3, telegramId);
                if (statement.executeUpdate() != 1) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "Telegram user was not found");
                }
            }
            return loadChatTagPreference(connection, telegramId, false);
        });
    }

    public List<ChatTagPreference> chatTagPreferences() {
        return database.transaction(connection -> {
            List<ChatTagPreference> preferences = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT u.telegram_id, u.chat_tag_account_id, m.nickname, m.enabled,
                           m.owner_telegram_id
                    FROM users u
                    LEFT JOIN minecraft_accounts m ON m.id = u.chat_tag_account_id
                    WHERE u.chat_tag_account_id IS NOT NULL OR u.chat_tag_managed = 1
                    ORDER BY u.telegram_id
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long ownerId = result.getLong("telegram_id");
                        long selectedId = result.getLong("chat_tag_account_id");
                        boolean valid = result.getString("nickname") != null
                                && result.getInt("enabled") == 1
                                && result.getLong("owner_telegram_id") == ownerId;
                        if (valid) {
                            preferences.add(new ChatTagPreference(
                                    ownerId, selectedId, result.getString("nickname")));
                        } else {
                            clearChatTagPreference(connection, ownerId);
                            preferences.add(new ChatTagPreference(ownerId, null, null));
                        }
                    }
                }
            }
            return List.copyOf(preferences);
        });
    }

    public List<AccountView> search(ModeratorIdentity moderator, String query) {
        requirePhaseTwoServices();
        String value = query == null ? "" : query.strip();
        if (value.isEmpty() || value.length() > 100) throw invalid("Invalid account search query");
        String normalized = value.toLowerCase(Locale.ROOT);
        Long numeric = parsePositiveLong(value);
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            List<AccountView> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.id, m.owner_telegram_id, m.nickname, m.licensed, m.enabled,
                           m.creation_method, m.credential_version, m.created_at
                    FROM minecraft_accounts m
                    LEFT JOIN users u ON u.telegram_id = m.owner_telegram_id
                    WHERE m.normalized_nickname LIKE ? ESCAPE '\\'
                       OR lower(u.username_snapshot) LIKE ? ESCAPE '\\'
                       OR (? IS NOT NULL AND (m.id = ? OR m.owner_telegram_id = ?))
                    ORDER BY m.id
                    LIMIT 20
                    """)) {
                String like = "%" + escapeLike(normalized) + "%";
                statement.setString(1, like);
                statement.setString(2, like);
                if (numeric == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                    statement.setNull(4, java.sql.Types.BIGINT);
                    statement.setNull(5, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, numeric);
                    statement.setLong(4, numeric);
                    statement.setLong(5, numeric);
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) results.add(mapAccount(result));
                }
            }
            return List.copyOf(results);
        });
    }

    public AccountView accountForModerator(ModeratorIdentity moderator, long accountId) {
        requirePhaseTwoServices();
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator, ModeratorPermission.TICKET_VIEW);
            return requireAccount(connection, accountId);
        });
    }

    public AccountStateChangeResult disable(
            ModeratorIdentity moderator, long accountId, String reason) {
        return setEnabled(moderator, accountId, false, reason);
    }

    public AccountStateChangeResult enable(
            ModeratorIdentity moderator, long accountId, String reason) {
        return setEnabled(moderator, accountId, true, reason);
    }

    private AccountStateChangeResult setEnabled(
            ModeratorIdentity moderator, long accountId, boolean enabled, String reason) {
        requirePhaseTwoServices();
        String safeReason = reason == null ? "" : reason.strip();
        if (safeReason.length() > 500) throw invalid("Account state reason is too long");
        return database.transaction(connection -> {
            moderators.requirePermission(connection, moderator,
                    enabled ? ModeratorPermission.ACCOUNT_ENABLE : ModeratorPermission.ACCOUNT_DISABLE);
            AccountView account = requireAccount(connection, accountId);
            Instant now = Instant.now(clock);
            if (account.enabled() == enabled) {
                return new AccountStateChangeResult(account, false, now);
            }
            if (!enabled) {
                try (PreparedStatement clearTag = connection.prepareStatement("""
                        UPDATE users SET chat_tag_account_id = NULL, updated_at = ?
                        WHERE chat_tag_account_id = ?
                        """)) {
                    clearTag.setString(1, now.toString());
                    clearTag.setLong(2, accountId);
                    clearTag.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE minecraft_accounts SET enabled = ?, updated_at = ?
                    WHERE id = ? AND enabled = ?
                    """)) {
                statement.setInt(1, enabled ? 1 : 0);
                statement.setString(2, now.toString());
                statement.setLong(3, accountId);
                statement.setInt(4, enabled ? 0 : 1);
                if (statement.executeUpdate() != 1) {
                    throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                            "Minecraft account state changed concurrently");
                }
            }
            SyncEventType eventType = enabled
                    ? SyncEventType.ACCOUNT_ENABLED : SyncEventType.ACCOUNT_DISABLED;
            syncEvents.enqueue(connection, eventType, Map.of("accountId", accountId), accountId,
                    moderator.telegramId(), moderator.usernameSnapshot(), now);
            audit.record(connection, eventType.name(), null, accountId, moderator.telegramId(),
                    moderator.usernameSnapshot(), now,
                    Map.of("minecraftNickname", account.nickname(), "previousEnabled", account.enabled(),
                            "newEnabled", enabled, "reason", safeReason));
            AccountView changed = new AccountView(account.id(), account.ownerTelegramId(), account.nickname(),
                    account.licensed(), enabled, account.creationMethod(), account.credentialVersion(),
                    account.createdAt());
            return new AccountStateChangeResult(changed, true, now);
        });
    }

    public String telegramUsername(long telegramId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT username_snapshot FROM users WHERE telegram_id = ?")) {
                statement.setLong(1, telegramId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : "";
                }
            }
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

    private static AccountView requireAccount(Connection connection, long accountId) throws SQLException {
        if (accountId <= 0) throw invalid("Minecraft account ID must be positive");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, owner_telegram_id, nickname, licensed, enabled, creation_method,
                       credential_version, created_at
                FROM minecraft_accounts WHERE id = ?
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new WorkflowException(
                        WorkflowException.Code.NOT_FOUND, "Minecraft account was not found");
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

    private static ChatTagPreference loadChatTagPreference(
            Connection connection, long telegramId, boolean repairInvalid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.chat_tag_account_id, m.nickname, m.enabled, m.owner_telegram_id
                FROM users u
                LEFT JOIN minecraft_accounts m ON m.id = u.chat_tag_account_id
                WHERE u.telegram_id = ?
                """)) {
            statement.setLong(1, telegramId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "Telegram user was not found");
                }
                long selectedId = result.getLong("chat_tag_account_id");
                if (result.wasNull()) return new ChatTagPreference(telegramId, null, null);
                boolean valid = result.getString("nickname") != null
                        && result.getInt("enabled") == 1
                        && result.getLong("owner_telegram_id") == telegramId;
                if (!valid) {
                    if (repairInvalid) clearChatTagPreference(connection, telegramId);
                    return new ChatTagPreference(telegramId, null, null);
                }
                return new ChatTagPreference(telegramId, selectedId, result.getString("nickname"));
            }
        }
    }

    private static void clearChatTagPreference(Connection connection, long telegramId) throws SQLException {
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE users SET chat_tag_account_id = NULL WHERE telegram_id = ?")) {
            clear.setLong(1, telegramId);
            clear.executeUpdate();
        }
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

    private static Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
