package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigratorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void appliesMigrationsInOrderAndDoesNotReapplyThem() throws Exception {
        Path path = temporaryDirectory.resolve("migrations.db");
        try (BackendDatabase database = BackendDatabase.open(path)) {
            assertEquals("1:initial,2:backend_phase_2,3:user_locale,4:telegram_workflow_fixes,5:telemetry_and_bot_blocking,6:ticket_closure_comments,7:chat_tags_and_registration_rejection,8:device_fingerprints_and_bans",
                    migrations(database));
            assertTrue(tableContains(database, "users", "chat_tag_account_id"));
            assertTrue(tableContains(database, "users", "chat_tag_managed"));
            assertTrue(tableSql(database, "moderator_pending_actions")
                    .contains("REGISTRATION_REJECTION_REASON"));
            assertTrue(tableContains(database, "devices", "last_seen_at"));
            assertTrue(tableContains(database, "device_signals", "signal_hash"));
            assertTrue(tableContains(database, "bans", "origin_ban_id"));
        }
        try (BackendDatabase database = BackendDatabase.open(path)) {
            assertEquals("1:initial,2:backend_phase_2,3:user_locale,4:telegram_workflow_fixes,5:telemetry_and_bot_blocking,6:ticket_closure_comments,7:chat_tags_and_registration_rejection,8:device_fingerprints_and_bans",
                    migrations(database));
        }
    }

    @Test
    void reportsAndDoesNotRecordFailedMigration() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT, applied_at TEXT)");
                statement.execute("INSERT INTO schema_migrations VALUES (1, 'initial', '2026-01-01T00:00:00Z')");
                statement.execute("CREATE TABLE tickets (affected_account_id INTEGER)");
            }
            var failure = assertThrows(java.sql.SQLException.class,
                    () -> new DatabaseMigrator(connection).migrate());
            assertTrue(failure.getMessage().contains("V002__backend_phase_2.sql"));
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 2")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private static String migrations(BackendDatabase database) {
        return database.transaction(connection -> {
            StringBuilder value = new StringBuilder();
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT version, name FROM schema_migrations ORDER BY version")) {
                while (result.next()) {
                    if (!value.isEmpty()) value.append(',');
                    value.append(result.getInt(1)).append(':').append(result.getString(2));
                }
            }
            return value.toString();
        });
    }

    private static boolean tableContains(BackendDatabase database, String table, String column) {
        return database.transaction(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (result.next()) {
                    if (column.equals(result.getString("name"))) return true;
                }
                return false;
            }
        });
    }

    private static String tableSql(BackendDatabase database, String table) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                statement.setString(1, table);
                try (var result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : "";
                }
            }
        });
    }
}
