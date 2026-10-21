package com.echest.enderchest.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite backed storage for ender chest rows.
 *
 * <p>The class only deals with opaque byte blobs, it does not know anything about items. That
 * keeps it free of Bukkit dependencies and makes it testable with a plain JUnit test.
 *
 * <p>Schema:
 * <pre>
 *   enderchest(uuid TEXT PRIMARY KEY, contents BLOB NOT NULL)
 *   enderchest_backup(uuid TEXT, backed_up_at INTEGER, reason TEXT, contents BLOB NOT NULL)
 * </pre>
 *
 * <p>The {@code enderchest} table is exactly the table the previous releases of this plugin used,
 * so existing databases are opened as they are. The {@code enderchest_backup} table is purely
 * additive; it keeps the last few versions of a row so that a broken write can always be undone.
 */
public final class ChestStore implements AutoCloseable {

    /** Number of historic versions kept per player. */
    public static final int MAX_BACKUPS_PER_PLAYER = 5;

    private final Logger logger;
    private final Connection connection;
    private final Object lock = new Object();

    /**
     * Opens (and if needed creates) the database.
     *
     * @param dbFile the database file
     * @param logger logger used for diagnostics
     * @throws SQLException when the database cannot be opened or prepared
     */
    public ChestStore(File dbFile, Logger logger) throws SQLException {
        this.logger = logger;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new SQLException("Could not create data folder " + parent.getAbsolutePath());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite JDBC driver is missing", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            // WAL keeps the database readable and consistent if the server is killed mid write.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS enderchest ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "contents BLOB NOT NULL"
                    + ")");
            statement.execute("CREATE TABLE IF NOT EXISTS enderchest_backup ("
                    + "uuid TEXT NOT NULL,"
                    + "backed_up_at INTEGER NOT NULL,"
                    + "reason TEXT,"
                    + "contents BLOB NOT NULL"
                    + ")");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_enderchest_backup_uuid "
                    + "ON enderchest_backup (uuid)");
            statement.execute("CREATE TABLE IF NOT EXISTS enderchest_export ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "exported_at INTEGER NOT NULL"
                    + ")");
            statement.execute("CREATE TABLE IF NOT EXISTS enderchest_import ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "fingerprint TEXT NOT NULL,"
                    + "imported_at INTEGER NOT NULL"
                    + ")");
        }
    }

    /**
     * Reads the raw contents blob of a player.
     *
     * @param uuid the player
     * @return the blob or {@code null} when the player has no row
     * @throws SQLException on database errors
     */
    public byte[] readContents(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT contents FROM enderchest WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBytes("contents") : null;
                }
            }
        }
    }

    /**
     * Checks whether a player has a row.
     *
     * @param uuid the player
     * @return {@code true} when a row exists
     * @throws SQLException on database errors
     */
    public boolean hasRow(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM enderchest WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }
    }

    /**
     * Writes the contents of a player. The previous content is backed up before it is replaced.
     *
     * @param uuid    the player
     * @param blob    the new contents
     * @param reason  short description stored with the backup
     * @throws SQLException on database errors
     */
    public void writeContents(UUID uuid, byte[] blob, String reason) throws SQLException {
        synchronized (lock) {
            byte[] previous = readContentsLocked(uuid);
            if (previous != null) {
                backupLocked(uuid, previous, reason);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO enderchest (uuid, contents) VALUES (?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setBytes(2, blob);
                statement.executeUpdate();
            }
        }
    }

    /**
     * Deletes the row of a player. The deleted content is backed up first.
     *
     * @param uuid   the player
     * @param reason short description stored with the backup
     * @throws SQLException on database errors
     */
    public void deleteContents(UUID uuid, String reason) throws SQLException {
        synchronized (lock) {
            byte[] previous = readContentsLocked(uuid);
            if (previous != null) {
                backupLocked(uuid, previous, reason);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM enderchest WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Lists every player that has a row.
     *
     * @return all known player ids
     * @throws SQLException on database errors
     */
    public Set<UUID> readAllUUIDs() throws SQLException {
        synchronized (lock) {
            Set<UUID> uuids = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT uuid FROM enderchest")) {
                while (result.next()) {
                    try {
                        uuids.add(UUID.fromString(result.getString("uuid")));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed uuid cannot be used, but it must not break the listing.
                    }
                }
            }
            return uuids;
        }
    }

    /**
     * Returns the backed up versions of a player, newest first.
     *
     * @param uuid  the player
     * @param limit maximum number of backups
     * @return the backups
     * @throws SQLException on database errors
     */
    public List<Backup> readBackups(UUID uuid, int limit) throws SQLException {
        synchronized (lock) {
            List<Backup> backups = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT backed_up_at, reason, contents FROM enderchest_backup "
                            + "WHERE uuid = ? ORDER BY rowid DESC LIMIT ?")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        backups.add(new Backup(uuid, result.getLong("backed_up_at"),
                                result.getString("reason"), result.getBytes("contents")));
                    }
                }
            }
            return backups;
        }
    }

    /**
     * Marks a player as exported, the vanilla ender chest is the source of truth for them.
     *
     * @param uuid the player
     * @throws SQLException on database errors
     */
    public void markExported(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO enderchest_export (uuid, exported_at) VALUES (?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Removes the export marker of a player.
     *
     * @param uuid the player
     * @throws SQLException on database errors
     */
    public void clearExported(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM enderchest_export WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Checks whether a player was exported.
     *
     * @param uuid the player
     * @return {@code true} when the chest of the player lives in the vanilla ender chest
     * @throws SQLException on database errors
     */
    public boolean isExported(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM enderchest_export WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }
    }

    /**
     * Reads the fingerprint of the content that was imported last.
     *
     * <p>The fingerprint is used to recognise content that is already in the database. Importing
     * the same content a second time would duplicate every item.
     *
     * @param uuid the player
     * @return the fingerprint, {@code null} when nothing was imported yet
     * @throws SQLException on database errors
     */
    public String readImportFingerprint(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT fingerprint FROM enderchest_import WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString("fingerprint") : null;
                }
            }
        }
    }

    /**
     * Stores the fingerprint of the content that was imported.
     *
     * @param uuid        the player
     * @param fingerprint the fingerprint of the imported content
     * @throws SQLException on database errors
     */
    public void markImported(UUID uuid, String fingerprint) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO enderchest_import (uuid, fingerprint, imported_at) "
                            + "VALUES (?, ?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, fingerprint);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Removes the import marker of a player.
     *
     * @param uuid the player
     * @throws SQLException on database errors
     */
    public void clearImported(UUID uuid) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM enderchest_import WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to close the ender chest database", e);
            }
        }
    }

    private byte[] readContentsLocked(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT contents FROM enderchest WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes("contents") : null;
            }
        }
    }

    private void backupLocked(UUID uuid, byte[] contents, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO enderchest_backup (uuid, backed_up_at, reason, contents) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, reason);
            statement.setBytes(4, contents);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM enderchest_backup WHERE uuid = ? AND rowid NOT IN ("
                        + "SELECT rowid FROM enderchest_backup WHERE uuid = ? ORDER BY rowid DESC LIMIT ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());
            statement.setInt(3, MAX_BACKUPS_PER_PLAYER);
            statement.executeUpdate();
        }
    }

    /**
     * A historic version of a chest row.
     *
     * @param uuid      the player
     * @param createdAt timestamp of the backup
     * @param reason    why the backup was created
     * @param contents  the raw contents blob
     */
    public record Backup(UUID uuid, long createdAt, String reason, byte[] contents) {
    }
}
