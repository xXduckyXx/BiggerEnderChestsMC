package com.echest.enderchest.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestStoreTest {

    @TempDir
    File tempDir;

    private ChestStore store;
    private final Logger logger = Logger.getLogger("ChestStoreTest");

    @BeforeEach
    void setUp() throws SQLException {
        store = new ChestStore(new File(tempDir, "enderchest.db"), logger);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void writesAndReadsARow() throws SQLException {
        UUID uuid = UUID.randomUUID();
        byte[] blob = SlotCodec.encode(new byte[][]{{1, 2, 3}});

        store.writeContents(uuid, blob, "test");

        assertTrue(store.hasRow(uuid));
        assertArrayEquals(blob, store.readContents(uuid));
    }

    @Test
    void readsNullForAnUnknownPlayer() throws SQLException {
        assertNull(store.readContents(UUID.randomUUID()));
        assertFalse(store.hasRow(UUID.randomUUID()));
    }

    @Test
    void keepsTheOldRowAsBackupBeforeOverwriting() throws SQLException {
        UUID uuid = UUID.randomUUID();
        byte[] first = SlotCodec.encode(new byte[][]{{1}});
        byte[] second = SlotCodec.encode(new byte[][]{{2}});

        store.writeContents(uuid, first, "first");
        store.writeContents(uuid, second, "second");

        assertArrayEquals(second, store.readContents(uuid));

        List<ChestStore.Backup> backups = store.readBackups(uuid, 10);
        assertEquals(1, backups.size());
        assertArrayEquals(first, backups.get(0).contents());
        assertEquals("second", backups.get(0).reason());
    }

    @Test
    void limitsTheNumberOfBackups() throws SQLException {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < ChestStore.MAX_BACKUPS_PER_PLAYER + 4; i++) {
            store.writeContents(uuid, SlotCodec.encode(new byte[][]{{(byte) i}}), "write" + i);
        }

        List<ChestStore.Backup> backups = store.readBackups(uuid, 100);

        assertEquals(ChestStore.MAX_BACKUPS_PER_PLAYER, backups.size());
    }

    @Test
    void backsUpBeforeDeleting() throws SQLException {
        UUID uuid = UUID.randomUUID();
        byte[] blob = SlotCodec.encode(new byte[][]{{1}});
        store.writeContents(uuid, blob, "write");

        store.deleteContents(uuid, "export");

        assertFalse(store.hasRow(uuid));
        assertNull(store.readContents(uuid));
        List<ChestStore.Backup> backups = store.readBackups(uuid, 10);
        assertEquals(1, backups.size());
        assertArrayEquals(blob, backups.get(0).contents());
    }

    @Test
    void remembersTheFingerprintOfTheLastImport() throws SQLException {
        UUID uuid = UUID.randomUUID();

        assertNull(store.readImportFingerprint(uuid));

        store.markImported(uuid, "fingerprint-of-the-import");

        assertEquals("fingerprint-of-the-import", store.readImportFingerprint(uuid));
    }

    @Test
    void replacesTheFingerprintOfAnOlderImport() throws SQLException {
        UUID uuid = UUID.randomUUID();

        store.markImported(uuid, "first");
        store.markImported(uuid, "second");

        assertEquals("second", store.readImportFingerprint(uuid));
    }

    @Test
    void listsAllPlayers() throws SQLException {        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.writeContents(first, SlotCodec.encode(new byte[][]{{1}}), "a");
        store.writeContents(second, SlotCodec.encode(new byte[][]{{2}}), "b");

        Set<UUID> all = store.readAllUUIDs();

        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createsTheDatabaseFileWhenItIsMissing() throws SQLException {
        File file = new File(new File(tempDir, "nested"), "enderchest.db");
        assertFalse(file.exists());

        try (ChestStore other = new ChestStore(file, logger)) {
            assertTrue(file.exists());
            other.writeContents(UUID.randomUUID(), SlotCodec.encode(new byte[][]{{1}}), "test");
        }
    }

    @Test
    void survivesReopeningTheDatabase() throws SQLException {
        UUID uuid = UUID.randomUUID();
        byte[] blob = SlotCodec.encode(new byte[][]{{7}});
        store.writeContents(uuid, blob, "test");
        store.close();

        store = new ChestStore(new File(tempDir, "enderchest.db"), logger);

        assertArrayEquals(blob, store.readContents(uuid));
    }

    @Test
    void remembersThatAChestWasExported() throws SQLException {
        UUID uuid = UUID.randomUUID();

        assertFalse(store.isExported(uuid));

        store.markExported(uuid);
        assertTrue(store.isExported(uuid));

        store.clearExported(uuid);
        assertFalse(store.isExported(uuid));
    }

    @Test
    void usesTheSameTableLayoutAsTheOldReleases() throws SQLException {
        // The table of the previous releases must stay readable.
        UUID uuid = UUID.randomUUID();
        byte[] blob = SlotCodec.encode(new byte[][]{{5}});
        store.writeContents(uuid, blob, "test");

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + new File(tempDir, "enderchest.db").getAbsolutePath());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT contents FROM enderchest WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertArrayEquals(blob, result.getBytes("contents"));
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
        assertNotNull(SlotCodec.decode(blob, 54).slots()[0]);
        assertEquals(1, new String(SlotCodec.decode(blob, 54).slots()[0], StandardCharsets.UTF_8).length());
    }
}
