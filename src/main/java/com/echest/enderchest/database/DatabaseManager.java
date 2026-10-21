package com.echest.enderchest.database;

import com.echest.enderchest.storage.ChestContents;
import com.echest.enderchest.storage.ChestStore;
import com.echest.enderchest.storage.ContentFingerprint;
import com.echest.enderchest.storage.ItemCodec;
import com.echest.enderchest.storage.LegacyJsonDecoder;
import com.echest.enderchest.storage.LruCache;
import com.echest.enderchest.storage.PayloadMerger;
import com.echest.enderchest.storage.SavePlanner;
import com.echest.enderchest.storage.SlotCodec;
import com.echest.enderchest.storage.VanillaMerge;
import com.echest.enderchest.util.ItemSerializationException;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores the ender chest contents of every player.
 *
 * <p>Design notes:
 * <ul>
 *   <li>All SQL is executed by a single background thread, the server thread never waits for the
 *       disk except for the rare cache miss of a player that is not cached yet.</li>
 *   <li>The cache is only updated <em>after</em> the database write succeeded, so a failed write
 *       can never make the plugin forget the old content.</li>
 *   <li>Items that cannot be serialized are never written as empty slots. The previous bytes of
 *       that slot are kept, and if there are none the whole write is refused.</li>
 *   <li>Items that cannot be deserialized keep their raw bytes, they are written back unchanged
 *       on the next save instead of being dropped.</li>
 * </ul>
 */
public class DatabaseManager implements AutoCloseable {

    /** Number of slots of a bigger ender chest. */
    public static final int CHEST_SIZE = 54;

    /** Number of players kept in the cache. */
    public static final int MAX_CACHE_SIZE = 100;

    /** How long a synchronous save may take before it is aborted. */
    private static final long SYNC_SAVE_TIMEOUT_SECONDS = 30;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ChestStore store;
    private final ItemCodec codec;

    /** Committed state, only updated after a successful write. Access ordered LRU cache. */
    private final Map<UUID, Entry> cache = Collections.synchronizedMap(new LruCache<>(MAX_CACHE_SIZE));

    /** Writes that have been submitted but are not committed yet. */
    private final Map<UUID, byte[][]> pending = new ConcurrentHashMap<>();

    /** Failures of the last write of a player. */
    private final Map<UUID, String> failures = new ConcurrentHashMap<>();

    private final ExecutorService writer;

    /**
     * Creates the manager and opens the database.
     *
     * @param plugin the plugin
     * @param codec  the codec used for the items
     * @throws SQLException when the database cannot be opened, the plugin must disable itself then
     */
    public DatabaseManager(JavaPlugin plugin, ItemCodec codec) throws SQLException {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.codec = codec;
        this.store = new ChestStore(new java.io.File(plugin.getDataFolder(), "enderchest.db"), logger);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "EnderChestDB-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Loads the content of a player.
     *
     * @param uuid the player
     * @return the content, never {@code null}
     */
    public ChestContents loadChest(UUID uuid) {
        byte[][] inFlight = pending.get(uuid);
        if (inFlight != null) {
            // A write is still in flight, the newest data is the one that is being written.
            return toContents(inFlight);
        }
        Entry entry = cache.get(uuid);
        if (entry != null) {
            return entry.contents();
        }

        byte[] blob = readRow(uuid);
        if (blob == null) {
            return ChestContents.empty(CHEST_SIZE);
        }

        ChestContents contents;
        byte[] trailing = null;
        if (SlotCodec.looksLikeJson(blob)) {
            contents = LegacyJsonDecoder.decode(blob, CHEST_SIZE, codec, logger);
        } else {
            SlotCodec.Decoded decoded = SlotCodec.decode(blob, CHEST_SIZE);
            trailing = decoded.trailing();
            contents = toContents(decoded.slots());
        }
        cache.put(uuid, new Entry(contents, trailing));
        return contents;
    }

    /**
     * Loads the items of a player.
     *
     * @param uuid the player
     * @return the items of the chest
     */
    public ItemStack[] loadEnderChest(UUID uuid) {
        return loadChest(uuid).items();
    }

    /**
     * Checks whether a player has a chest in the database.
     *
     * @param uuid the player
     * @return {@code true} when a row exists
     */
    public boolean hasEnderChest(UUID uuid) {
        if (cache.containsKey(uuid) || pending.containsKey(uuid)) {
            return true;
        }
        try {
            return store.hasRow(uuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check the ender chest of " + uuid, e);
            return false;
        }
    }

    /**
     * Lists every player with a chest in the database.
     *
     * @return all known player ids
     */
    public Set<UUID> getAllUUIDs() {
        try {
            return store.readAllUUIDs();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to list the ender chests", e);
            return Set.of();
        }
    }

    /**
     * Removes a player from the cache.
     *
     * @param uuid the player
     */
    public void evictCache(UUID uuid) {
        cache.remove(uuid);
        pending.remove(uuid);
        failures.remove(uuid);
    }

    /**
     * Saves the content of a player without blocking the server thread.
     *
     * @param uuid     the player
     * @param contents the new content
     * @return the result of the save, the write itself may still be running
     */
    public SaveResult saveEnderChest(UUID uuid, ItemStack[] contents) {
        return save(uuid, contents, false);
    }

    /**
     * Saves the content of a player and waits for the database write.
     *
     * @param uuid     the player
     * @param contents the new content
     * @return the result of the save
     */
    public SaveResult saveEnderChestSync(UUID uuid, ItemStack[] contents) {
        return save(uuid, contents, true);
    }

    /**
     * Merges the content of the vanilla ender chest of a player into the stored chest.
     *
     * <p>Items that do not fit into the stored chest and items that cannot be serialized stay in
     * the vanilla ender chest. The vanilla chest is only cleared for the items that really made it
     * into the stored chest.
     *
     * @param uuid            the player
     * @param vanillaContents the content of the vanilla ender chest
     * @return the result of the merge
     */
    public VanillaMergeResult mergeVanillaContents(UUID uuid, ItemStack[] vanillaContents) {
        return mergeVanillaContents(uuid, vanillaContents, true);
    }

    /**
     * Merges the content of the vanilla ender chest of a player into the stored chest.
     *
     * @param uuid            the player
     * @param vanillaContents the content of the vanilla ender chest
     * @param guard           whether the import guard is used, it stops content that is already in
     *                        the database from being imported a second time
     * @return the result of the merge
     */
    private VanillaMergeResult mergeVanillaContents(UUID uuid, ItemStack[] vanillaContents,
                                                    boolean guard) {
        ChestContents current = loadChest(uuid);

        byte[][] incoming = new byte[CHEST_SIZE][];
        List<Integer> failedSlots = new ArrayList<>();
        for (int slot = 0; slot < vanillaContents.length && slot < CHEST_SIZE; slot++) {
            ItemStack item = vanillaContents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            try {
                byte[] payload = codec.serialize(item);
                if (payload != null && payload.length > 0) {
                    incoming[slot] = payload;
                } else {
                    failedSlots.add(slot);
                }
            } catch (ItemSerializationException e) {
                logger.log(Level.WARNING, "Could not serialize the item in slot " + slot
                        + " of the vanilla ender chest of " + uuid, e);
                failedSlots.add(slot);
            }
        }

        String fingerprint = ContentFingerprint.of(incoming);
        if (guard && !fingerprint.isEmpty() && fingerprint.equals(readImportFingerprint(uuid))) {
            // This exact content is already in the database. Importing it again would add another
            // copy of every item, so the vanilla ender chest is left alone.
            logger.info("The vanilla ender chest of " + uuid + " was already imported, "
                    + "it is not imported again.");
            return new VanillaMergeResult(vanillaContents.clone(), 0, failedSlots.size(), false, true);
        }

        PayloadMerger.Result merged = PayloadMerger.merge(current.payloads(), incoming);

        // An item that was moved into the stored chest must not stay in the vanilla ender chest,
        // otherwise the next join would move it again and duplicate it.
        boolean[] moved = VanillaMerge.movedSlots(incoming, merged.leftoverSlots(), failedSlots);

        ItemStack[] remaining = new ItemStack[vanillaContents.length];
        for (int slot : failedSlots) {
            // The item cannot be stored, it has to stay in the vanilla chest.
            remaining[slot] = vanillaContents[slot];
        }
        for (int index = 0; index < merged.leftoverSlots().length; index++) {
            int slot = merged.leftoverSlots()[index];
            try {
                remaining[slot] = codec.deserialize(merged.leftover()[index]);
            } catch (ItemSerializationException e) {
                // The item cannot be read back, so the vanilla chest must not be touched at all.
                logger.log(Level.WARNING, "Could not read back a merged item of " + uuid
                        + ", the vanilla ender chest is left untouched", e);
                return new VanillaMergeResult(vanillaContents.clone(), 0, failedSlots.size(),
                        false, false);
            }
        }

        int mergedCount = 0;
        for (int slot = 0; slot < moved.length && slot < remaining.length; slot++) {
            if (moved[slot]) {
                mergedCount++;
            }
        }

        Entry entry = cache.get(uuid);
        // The items have to be in the database before the vanilla ender chest is cleared,
        // otherwise they are lost when the write fails.
        if (!writePayloadsSync(uuid, merged.merged(), entry != null ? entry.trailing() : null)) {
            logger.severe("Could not store the ender chest of " + uuid
                    + ", the vanilla ender chest was left untouched.");
            return new VanillaMergeResult(vanillaContents.clone(), 0, failedSlots.size(), false, false);
        }

        if (mergedCount > 0) {
            // Remember what was imported, the same content must not be imported a second time.
            markImported(uuid, fingerprint);
        }

        return new VanillaMergeResult(remaining, mergedCount, failedSlots.size(), true, false);
    }

    /**
     * Moves the content of the stored chest back into the vanilla ender chest of a player.
     *
     * <p>The stored row is deleted (after it was backed up) and the player is marked as exported,
     * so the vanilla ender chest is not imported again on the next join.
     *
     * @param uuid the player
     * @return the exported items, {@code null} when the chest does not fit into the vanilla chest
     */
    public ItemStack[] exportToVanilla(UUID uuid) {
        ChestContents contents = loadChest(uuid);
        ItemStack[] items = contents.items();

        int used = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                used++;
            }
        }
        if (used > 27) {
            // The vanilla ender chest has 27 slots, the rest would be lost.
            return null;
        }
        if (contents.hasUnreadable()) {
            // Unreadable items cannot be written into the vanilla ender chest.
            return null;
        }

        ItemStack[] vanilla = new ItemStack[27];
        int slot = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                vanilla[slot++] = item;
            }
        }
        try {
            store.deleteContents(uuid, "export");
            store.markExported(uuid);
            // The chest is in the vanilla ender chest now, the import marker of the database is
            // not valid anymore.
            store.clearImported(uuid);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to export the ender chest of " + uuid, e);
            return null;
        }
        evictCache(uuid);
        return vanilla;
    }

    /**
     * Imports the content of the vanilla ender chest of a player back into the stored chest.
     *
     * <p>The import guard is not used here, the admin asked for this import explicitly.
     *
     * @param uuid            the player
     * @param vanillaContents the content of the vanilla ender chest
     * @return the result of the import
     */
    public VanillaMergeResult importFromVanilla(UUID uuid, ItemStack[] vanillaContents) {
        VanillaMergeResult result = mergeVanillaContents(uuid, vanillaContents, false);
        try {
            store.clearExported(uuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to clear the export marker of " + uuid, e);
        }
        return result;
    }

    /**
     * Whether the chest of a player was exported to the vanilla ender chest.
     *
     * @param uuid the player
     * @return {@code true} when the chest was exported
     */
    public boolean isExported(UUID uuid) {
        try {
            return store.isExported(uuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read the export marker of " + uuid, e);
            return false;
        }
    }

    /**
     * Waits until every queued database write has been processed.
     */
    public void flush() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(SYNC_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Timed out while flushing the ender chest database");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    /**
     * Flushes the pending writes and closes the database.
     */
    @Override
    public void close() {
        flush();
        store.close();
        cache.clear();
        pending.clear();
    }

    /**
     * The error of the last failed write of a player, if any.
     *
     * @param uuid the player
     * @return the error message or {@code null}
     */
    public String lastFailure(UUID uuid) {
        return failures.get(uuid);
    }

    private SaveResult save(UUID uuid, ItemStack[] contents, boolean synchronous) {
        Entry previous = cache.get(uuid);
        byte[][] previousPayloads = previous != null ? previous.contents().payloads() : null;
        boolean[] previousUnreadable = previous != null ? previous.contents().unreadableSlots() : null;

        byte[][] payloads = new byte[CHEST_SIZE][];
        List<Integer> failed = new ArrayList<>();

        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            ItemStack item = slot < contents.length ? contents[slot] : null;
            if (item == null || item.getType().isAir()) {
                continue;
            }
            try {
                byte[] payload = codec.serialize(item);
                if (payload == null || payload.length == 0) {
                    throw new ItemSerializationException(item, slot, "empty payload");
                }
                payloads[slot] = payload;
            } catch (ItemSerializationException e) {
                // The item cannot be stored. The planner decides whether the old bytes are kept
                // or whether the whole write has to be refused.
                failed.add(slot);
            }
        }

        SavePlanner.Plan plan = SavePlanner.plan(payloads, failed, previousPayloads, previousUnreadable);
        if (plan.refused()) {
            String message = "Refused to overwrite the stored ender chest, " + plan.failedSlots().size()
                    + " item(s) could not be serialized";
            logger.severe(message + " for " + uuid);
            failures.put(uuid, message);
            return new SaveResult(false, plan.failedSlots(), true, message);
        }

        byte[] trailing = previous != null ? previous.trailing() : null;

        if (synchronous) {
            CompletableFuture<SaveResult> future = new CompletableFuture<>();
            write(uuid, plan.payloads(), trailing, plan.failedSlots(), future::complete);
            try {
                return future.get(SYNC_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                String message = "Timed out while saving the ender chest";
                logger.log(Level.SEVERE, message, e);
                failures.put(uuid, message);
                return new SaveResult(false, plan.failedSlots(), false, message);
            }
        }

        write(uuid, plan.payloads(), trailing, plan.failedSlots(), result -> {
        });
        return new SaveResult(true, plan.failedSlots(), false, null);
    }

    private void write(UUID uuid, byte[][] payloads, byte[] trailing, List<Integer> failedSlots,
                       java.util.function.Consumer<SaveResult> callback) {
        byte[] blob = SlotCodec.encode(payloads, trailing);
        pending.put(uuid, payloads);
        writer.execute(() -> {
            try {
                store.writeContents(uuid, blob, "save");
                cache.put(uuid, new Entry(toContents(payloads), trailing));
                failures.remove(uuid);
                callback.accept(new SaveResult(true, failedSlots, false, null));
            } catch (SQLException e) {
                String message = "Failed to save the ender chest: " + e.getMessage();
                logger.log(Level.SEVERE, message, e);
                failures.put(uuid, message);
                callback.accept(new SaveResult(false, failedSlots, false, message));
            } finally {
                pending.remove(uuid, payloads);
            }
        });
    }

    /**
     * Writes already serialized payloads and waits for the database write.
     *
     * <p>The merge of the vanilla ender chest uses this. The vanilla chest is only cleared after
     * the write succeeded, otherwise the items would be lost when the write fails.
     *
     * @param uuid     the player
     * @param payloads the payloads
     * @param trailing bytes of the old row that could not be parsed
     * @return {@code true} when the data is stored
     */
    private boolean writePayloadsSync(UUID uuid, byte[][] payloads, byte[] trailing) {
        CompletableFuture<SaveResult> future = new CompletableFuture<>();
        write(uuid, payloads, trailing, List.of(), future::complete);
        try {
            SaveResult result = future.get(SYNC_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, "Timed out while saving the ender chest of " + uuid, e);
            return false;
        }
    }

    /**
     * Reads the fingerprint of the content that was imported last.
     *
     * @param uuid the player
     * @return the fingerprint, {@code null} when nothing was imported yet
     */
    private String readImportFingerprint(UUID uuid) {
        try {
            return store.readImportFingerprint(uuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read the import marker of " + uuid, e);
            return null;
        }
    }

    /**
     * Stores the fingerprint of the content that was imported.
     *
     * @param uuid        the player
     * @param fingerprint the fingerprint of the imported content
     */
    private void markImported(UUID uuid, String fingerprint) {
        try {
            store.markImported(uuid, fingerprint);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to store the import marker of " + uuid, e);
        }
    }

    /**
     * The codec that is used for the items.
     *
     * @return the codec
     */
    public ItemCodec codec() {
        return codec;
    }

    /**
     * Deletes the chest of a player. The old row is backed up before it is deleted.
     *
     * @param uuid the player
     */
    public void deleteChest(UUID uuid) {
        try {
            store.deleteContents(uuid, "deleted");
            store.clearExported(uuid);
            store.clearImported(uuid);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to delete the ender chest of " + uuid, e);
        }
        evictCache(uuid);
    }

    /**
     * Lists the backed up versions of the chest of a player.
     *
     * @param uuid the player
     * @return the backups, newest first
     */
    public List<ChestStore.Backup> listBackups(UUID uuid) {
        try {
            return store.readBackups(uuid, 10);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read the backups of " + uuid, e);
            return List.of();
        }
    }

    /**
     * Restores a backed up version of the chest of a player.
     *
     * <p>The row that is replaced is backed up again, so a restore can be undone.
     *
     * @param uuid the player
     * @param blob the raw contents of the backup
     * @return the number of slots with items, {@code -1} when the write failed
     */
    public int restoreBackup(UUID uuid, byte[] blob) {
        SlotCodec.Decoded decoded = SlotCodec.decode(blob, CHEST_SIZE);
        byte[][] payloads = decoded.slots();
        if (!writePayloadsSync(uuid, payloads, decoded.trailing())) {
            return -1;
        }
        int used = 0;
        for (byte[] payload : payloads) {
            if (payload != null && payload.length > 0) {
                used++;
            }
        }
        return used;
    }

    private byte[] readRow(UUID uuid) {
        try {
            return store.readContents(uuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read the ender chest of " + uuid, e);
            return null;
        }
    }

    private ChestContents toContents(byte[][] payloads) {
        ItemStack[] items = new ItemStack[CHEST_SIZE];
        boolean[] unreadable = new boolean[CHEST_SIZE];
        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            byte[] payload = payloads[slot];
            if (payload == null || payload.length == 0) {
                continue;
            }
            try {
                items[slot] = codec.deserialize(payload);
            } catch (ItemSerializationException e) {
                // The bytes are kept in the payload, they are written back unchanged.
                unreadable[slot] = true;
                logger.warning("Slot " + slot + " of an ender chest could not be read: " + e.getMessage());
            }
        }
        return ChestContents.of(items, payloads, unreadable);
    }

    /**
     * A cached chest.
     *
     * @param contents the content
     * @param trailing bytes of the row that could not be parsed
     */
    private record Entry(ChestContents contents, byte[] trailing) {
    }

    /**
     * The result of a save.
     *
     * @param success     whether the data was written
     * @param failedSlots slots that could not be serialized
     * @param refused     whether the write was refused to protect the stored data
     * @param error       error message, {@code null} on success
     */
    public record SaveResult(boolean success, List<Integer> failedSlots, boolean refused, String error) {

        public boolean hasFailedSlots() {
            return failedSlots != null && !failedSlots.isEmpty();
        }
    }

    /**
     * The result of a merge with the vanilla ender chest.
     *
     * @param remaining    the content the vanilla ender chest has to keep
     * @param mergedCount  number of items that were moved into the stored chest
     * @param failedCount  number of items that could not be serialized
     * @param success      whether the merge was carried out
     * @param skipped      whether the import was skipped because the content is already stored
     */
    public record VanillaMergeResult(ItemStack[] remaining, int mergedCount, int failedCount,
                                     boolean success, boolean skipped) {
    }
}
