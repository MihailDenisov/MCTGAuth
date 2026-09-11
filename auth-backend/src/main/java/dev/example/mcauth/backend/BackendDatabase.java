package dev.example.mcauth.backend;

import dev.example.mcauth.api.AuthDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class BackendDatabase implements AuthDatabase, AutoCloseable {
    private final Connection connection;
    private final Object transactionLock = new Object();

    private BackendDatabase(Connection connection) {
        this.connection = connection;
    }

    public static BackendDatabase open(Path databasePath) throws SQLException, IOException {
        Objects.requireNonNull(databasePath, "databasePath");
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return openJdbcUrl("jdbc:sqlite:" + absolutePath);
    }

    static BackendDatabase openJdbcUrl(String jdbcUrl) throws SQLException, IOException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        BackendDatabase database = new BackendDatabase(connection);
        try {
            database.configure();
            new DatabaseMigrator(connection).migrate();
            return database;
        } catch (SQLException | IOException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    public <T> T transaction(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        synchronized (transactionLock) {
            try {
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (Exception exception) {
                    connection.rollback();
                    if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new DatabaseException("Database transaction failed", exception);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException exception) {
                throw new DatabaseException("Database transaction failed", exception);
            }
        }
    }

    public boolean isHealthy() {
        try {
            return transaction(connection -> {
                try (java.sql.Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT 1")) {
                    return result.next() && result.getInt(1) == 1;
                }
            });
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public int schemaVersion() {
        return transaction(connection -> {
            try (java.sql.Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
                return result.next() ? result.getInt(1) : 0;
            }
        });
    }

    private void configure() throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (transactionLock) {
            connection.close();
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
