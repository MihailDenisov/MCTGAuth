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
            assertEquals("1:initial,2:backend_phase_2,3:user_locale,4:telegram_workflow_fixes",
                    migrations(database));
        }
        try (BackendDatabase database = BackendDatabase.open(path)) {
            assertEquals("1:initial,2:backend_phase_2,3:user_locale,4:telegram_workflow_fixes",
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
}
