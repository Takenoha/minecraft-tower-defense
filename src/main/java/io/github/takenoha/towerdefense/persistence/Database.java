package io.github.takenoha.towerdefense.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite connection factory used by the persistence layer.
 *
 * <p>A fresh connection is opened for every operation. SQLite PRAGMAs which are scoped to a
 * connection are therefore applied here rather than only during plugin startup.</p>
 */
public final class Database {
    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;

    private final String jdbcUrl;
    private final int busyTimeoutMillis;

    public Database(Path databaseFile) {
        this(databaseFile, DEFAULT_BUSY_TIMEOUT_MILLIS);
    }

    public Database(Path databaseFile, int busyTimeoutMillis) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        if (busyTimeoutMillis < 0) {
            throw new IllegalArgumentException("busyTimeoutMillis must not be negative");
        }

        Path absoluteFile = databaseFile.toAbsolutePath().normalize();
        Path parent = absoluteFile.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
        } catch (IOException exception) {
            throw new PersistenceException("Could not create the SQLite data directory", exception);
        } catch (ClassNotFoundException exception) {
            throw new PersistenceException("The SQLite JDBC driver is not available", exception);
        }

        this.jdbcUrl = "jdbc:sqlite:" + absoluteFile;
        this.busyTimeoutMillis = busyTimeoutMillis;
        SchemaMigrator.migrate(this);
    }

    /**
     * Opens a configured connection. The caller owns the returned connection.
     */
    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        boolean configured = false;
        try {
            configure(connection);
            configured = true;
            return connection;
        } finally {
            if (!configured) {
                connection.close();
            }
        }
    }

    <T> T inImmediateTransaction(SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        try (Connection connection = openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }

            try {
                T result = work.execute(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("COMMIT");
                }
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    int busyTimeoutMillis() {
        return busyTimeoutMillis;
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
