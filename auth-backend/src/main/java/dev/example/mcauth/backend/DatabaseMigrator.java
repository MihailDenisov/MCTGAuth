package dev.example.mcauth.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DatabaseMigrator {
    private static final String MIGRATION_ROOT = "/db/migration/";
    private static final String MIGRATION_INDEX = MIGRATION_ROOT + "migrations.txt";
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d{3,})__([A-Za-z0-9_]+)\\.sql");

    private final Connection connection;

    DatabaseMigrator(Connection connection) {
        this.connection = connection;
    }

    void migrate() throws IOException, SQLException {
        ensureMetadataTable();
        List<Migration> migrations = loadIndex();
        Set<Integer> applied = appliedVersions();
        for (Migration migration : migrations) {
            if (!applied.contains(migration.version())) {
                apply(migration);
            }
        }
    }

    private void ensureMetadataTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private List<Migration> loadIndex() throws IOException {
        String index = readResource(MIGRATION_INDEX);
        List<Migration> migrations = new ArrayList<>();
        Set<Integer> versions = new HashSet<>();
        int previous = -1;
        for (String rawLine : index.lines().toList()) {
            String fileName = rawLine.strip();
            if (fileName.isEmpty() || fileName.startsWith("#")) {
                continue;
            }
            Matcher matcher = FILE_NAME.matcher(fileName);
            if (!matcher.matches()) {
                throw new IOException("Invalid migration filename in index: " + fileName);
            }
            int version = Integer.parseInt(matcher.group(1));
            if (!versions.add(version) || version <= previous) {
                throw new IOException("Migrations must have unique, increasing versions: " + fileName);
            }
            migrations.add(new Migration(version, matcher.group(2), fileName,
                    readResource(MIGRATION_ROOT + fileName)));
            previous = version;
        }
        if (migrations.isEmpty()) {
            throw new IOException("No database migrations are configured");
        }
        return List.copyOf(migrations);
    }

    private Set<Integer> appliedVersions() throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM schema_migrations")) {
            while (result.next()) {
                versions.add(result.getInt(1));
            }
        }
        return versions;
    }

    private void apply(Migration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String command : migration.sql().split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.name());
                statement.setString(3, Instant.now().toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw new SQLException("Database migration " + migration.fileName() + " failed", exception);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = DatabaseMigrator.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing database migration resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Migration(int version, String name, String fileName, String sql) {
    }
}
