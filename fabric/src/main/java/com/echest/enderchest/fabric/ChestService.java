package com.echest.enderchest.fabric;

import com.echest.enderchest.storage.ChestStore;
import com.echest.enderchest.storage.ContentFingerprint;
import com.echest.enderchest.storage.LruCache;
import com.echest.enderchest.storage.PayloadMerger;
import com.echest.enderchest.storage.SavePlanner;
import com.echest.enderchest.storage.SlotCodec;
import com.echest.enderchest.storage.VanillaMerge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * Stores the ender chest of every player.
 *
 * <p>The behaviour is the same as the behaviour of the Paper version of this plugin:
 *
 * <ul>
 *   <li>The cache is only updated after the database write succeeded.</li>
 *   <li>An item that cannot be serialized keeps the bytes that are already stored, if there are
 *       none the write is refused instead of deleting the item.</li>
 *   <li>An item that cannot be read keeps its raw bytes, they are written back unchanged.</li>
 *   <li>The old row of a player is backed up before it is overwritten.</li>
 *   <li>The content of the vanilla ender chest is merged into the database, it is never
 *       deleted.</li>
 * </ul>
 */
public final class ChestService implements AutoCloseable {

    /** Number of slots of a bigger ender chest. */
    public static final int CHEST_SIZE = 54;

    /** Number of players kept in the cache. */
    public static final int MAX_CACHE_SIZE = 100;

    /** How long a synchronous write may take before it is aborted. */
    private static final long WRITE_TIMEOUT_SECONDS = 30;

    private final Logger logger;
    private final ChestStore store;

    /** Committed state, only updated after a successful write. */
    private final Map<UUID, CachedChest> cache =
            Collections.synchronizedMap(new LruCache<>(MAX_CACHE_SIZE));

    /** Writes that have been submitted but are not committed yet. */
    private final Map<UUID, byte[][]> pending = new ConcurrentHashMap<>();

    private final ExecutorService writer;
    private volatile MinecraftServer server;

    /**
     * Creates the service and opens the database.
     *
     * @param database the file of the database
     * @param logger   the logger
     * @throws SQLException when the database cannot be opened
     */
    public ChestService(Path database, Logger logger) throws SQLException {
        this.logger = logger;
        this.store = new ChestStore(database.toFile(), logger);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BiggerEnderChests-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Sets the server the codec needs.
     *
     * @param server the server
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * A codec for the items of the running server.
     *
     * @return the codec
     */
    public MinecraftItemCodec codec() {
        MinecraftServer current = server;
        if (current == null) {
            throw new IllegalStateException("The server is not running");
        }
        return new MinecraftItemCodec(current.registryAccess());
    }

    /**
     * Loads the chest of a player.
     *
     * @param uuid the player
     * @return the chest
     * @throws SQLException on database errors
     */
    public CachedChest load(UUID uuid) throws SQLException {
        byte[][] inFlight = pending.get(uuid);
        if (inFlight != null) {
            return decode(inFlight);
        }
        CachedChest cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }

        byte[] blob = store.readContents(uuid);
        if (blob == null) {
            return CachedChest.empty();
        }
        SlotCodec.Decoded decoded = SlotCodec.decode(blob, CHEST_SIZE);
        CachedChest chest = decode(decoded.slots());
        chest.trailing = decoded.trailing();
        cache.put(uuid, chest);
        return chest;
    }

    /**
     * Whether a player has a chest in the database.
     *
     * @param uuid the player
     * @return {@code true} when the player has a chest
     * @throws SQLException on database errors
     */
    public boolean hasChest(UUID uuid) throws SQLException {
        if (cache.containsKey(uuid) || pending.containsKey(uuid)) {
            return true;
        }
        return store.hasRow(uuid);
    }

    /**
     * Saves the chest of a player.
     *
     * @param uuid  the player
     * @param items the 54 slots of the chest
     * @return the result of the save
     */
    public SaveResult save(UUID uuid, List<ItemStack> items) {
        CachedChest previous = cache.get(uuid);
        byte[][] previousPayloads = previous != null ? previous.payloads : null;
        boolean[] previousUnreadable = previous != null ? previous.unreadable : null;

        byte[][] payloads = new byte[CHEST_SIZE][];
        List<Integer> failed = new ArrayList<>();
        MinecraftItemCodec codec = codec();

        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            ItemStack stack = slot < items.size() ? items.get(slot) : null;
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            try {
                byte[] payload = codec.serialize(stack);
                if (payload == null || payload.length == 0) {
                    throw new ItemCodecException("the item encoded into nothing");
                }
                payloads[slot] = payload;
            } catch (ItemCodecException e) {
                logger.log(Level.WARNING, "Could not serialize the item in slot " + slot
                        + " of the ender chest of " + uuid, e);
                failed.add(slot);
            }
        }

        SavePlanner.Plan plan = SavePlanner.plan(payloads, failed, previousPayloads, previousUnreadable);
        if (plan.refused()) {
            String message = "Refused to overwrite the stored ender chest, "
                    + plan.failedSlots().size() + " item(s) could not be serialized";
            logger.severe(message + " for " + uuid);
            return new SaveResult(false, plan.failedSlots(), true, message);
        }

        write(uuid, plan.payloads(), previous != null ? previous.trailing : null, plan.failedSlots());
        return new SaveResult(true, plan.failedSlots(), false, null);
    }

    /**
     * Merges the content of the vanilla ender chest of a player into the stored chest.
     *
     * <p>Items that do not fit and items that cannot be stored stay in the vanilla ender chest.
     *
     * @param uuid  the player
     * @param items the 27 slots of the vanilla ender chest
     * @return the result of the merge
     * @throws SQLException on database errors
     */
    public MergeResult mergeVanillaContents(UUID uuid, List<ItemStack> items) throws SQLException {
        return mergeVanillaContents(uuid, items, true);
    }

    /**
     * Merges the content of the vanilla ender chest of a player into the stored chest.
     *
     * @param uuid  the player
     * @param items the 27 slots of the vanilla ender chest
     * @param guard whether the import guard is used, it stops content that is already in the
     *              database from being imported a second time
     * @return the result of the merge
     * @throws SQLException on database errors
     */
    private MergeResult mergeVanillaContents(UUID uuid, List<ItemStack> items, boolean guard)
            throws SQLException {
        CachedChest current = load(uuid);

        byte[][] incoming = new byte[CHEST_SIZE][];
        List<Integer> failedSlots = new ArrayList<>();
        MinecraftItemCodec codec = codec();
        for (int slot = 0; slot < items.size() && slot < CHEST_SIZE; slot++) {
            ItemStack stack = items.get(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            try {
                byte[] payload = codec.serialize(stack);
                if (payload != null && payload.length > 0) {
                    incoming[slot] = payload;
                } else {
                    failedSlots.add(slot);
                }
            } catch (ItemCodecException e) {
                logger.log(Level.WARNING, "Could not serialize the item in slot " + slot
                        + " of the vanilla ender chest of " + uuid, e);
                failedSlots.add(slot);
            }
        }

        String fingerprint = ContentFingerprint.of(incoming);
        if (guard && !fingerprint.isEmpty() && fingerprint.equals(store.readImportFingerprint(uuid))) {
            // This exact content is already in the database. Importing it again would add another
            // copy of every item, so the vanilla ender chest is left alone.
            logger.info("The vanilla ender chest of " + uuid + " was already imported, "
                    + "it is not imported again.");
            return new MergeResult(items, 0, failedSlots.size(), false, true);
        }

        PayloadMerger.Result merged = PayloadMerger.merge(current.payloads, incoming);

        // An item that was moved into the stored chest must not stay in the vanilla ender chest,
        // otherwise the next join would move it again and duplicate it.
        boolean[] moved = VanillaMerge.movedSlots(incoming, merged.leftoverSlots(), failedSlots);

        List<ItemStack> remaining = new ArrayList<>(
                Collections.nCopies(items.size(), (ItemStack) null));
        for (int slot : failedSlots) {
            // The item cannot be stored, it has to stay in the vanilla ender chest.
            remaining.set(slot, items.get(slot));
        }
        for (int index = 0; index < merged.leftoverSlots().length; index++) {
            int slot = merged.leftoverSlots()[index];
            try {
                remaining.set(slot, codec.deserialize(merged.leftover()[index]));
            } catch (ItemCodecException e) {
                logger.log(Level.WARNING, "Could not read back a merged item of " + uuid
                        + ", the vanilla ender chest is left untouched", e);
                return new MergeResult(items, 0, failedSlots.size(), false, false);
            }
        }

        int mergedCount = 0;
        for (int slot = 0; slot < moved.length && slot < remaining.size(); slot++) {
            if (moved[slot]) {
                mergedCount++;
            }
        }

        // The items have to be in the database before the vanilla ender chest is cleared,
        // otherwise they are lost when the write fails.
        if (!writeSync(uuid, merged.merged(), current.trailing)) {
            logger.severe("Could not store the ender chest of " + uuid
                    + ", the vanilla ender chest was left untouched.");
            return new MergeResult(items, 0, failedSlots.size(), false, false);
        }

        if (mergedCount > 0) {
            // Remember what was imported, the same content must not be imported a second time.
            store.markImported(uuid, fingerprint);
        }

        return new MergeResult(remaining, mergedCount, failedSlots.size(), true, false);
    }

    /**
     * Moves the chest of a player back into the vanilla ender chest.
     *
     * @param uuid the player
     * @return the 27 slots of the vanilla ender chest, {@code null} when the chest does not fit
     * @throws SQLException on database errors
     */
    public List<ItemStack> exportToVanilla(UUID uuid) throws SQLException {
        CachedChest chest = load(uuid);
        int used = 0;
        for (ItemStack stack : chest.items) {
            if (stack != null && !stack.isEmpty()) {
                used++;
            }
        }
        if (used > 27 || chest.unreadableCount() > 0) {
            // The vanilla ender chest has 27 slots, the rest would be lost.
            return null;
        }

        List<ItemStack> vanilla = new ArrayList<>(27);
        for (ItemStack stack : chest.items) {
            if (stack != null && !stack.isEmpty()) {
                vanilla.add(stack);
            }
        }
        while (vanilla.size() < 27) {
            vanilla.add(null);
        }

        store.deleteContents(uuid, "export");
        store.markExported(uuid);
        // The chest is in the vanilla ender chest now, the import marker of the database is not
        // valid anymore.
        store.clearImported(uuid);
        cache.remove(uuid);
        pending.remove(uuid);
        return vanilla;
    }

    /**
     * Imports the content of the vanilla ender chest back into the stored chest.
     *
     * <p>The import guard is not used here, the admin asked for this import explicitly.
     *
     * @param uuid  the player
     * @param items the 27 slots of the vanilla ender chest
     * @return the result of the import
     * @throws SQLException on database errors
     */
    public MergeResult importFromVanilla(UUID uuid, List<ItemStack> items) throws SQLException {
        MergeResult result = mergeVanillaContents(uuid, items, false);
        store.clearExported(uuid);
        return result;
    }

    /**
     * Whether the chest of a player was exported to the vanilla ender chest.
     *
     * @param uuid the player
     * @return {@code true} when the chest was exported
     * @throws SQLException on database errors
     */
    public boolean isExported(UUID uuid) throws SQLException {
        return store.isExported(uuid);
    }

    /**
     * Lists the backed up versions of the chest of a player.
     *
     * @param uuid the player
     * @return the backups, newest first
     * @throws SQLException on database errors
     */
    public List<ChestStore.Backup> listBackups(UUID uuid) throws SQLException {
        return store.readBackups(uuid, 10);
    }

    /**
     * Restores a backed up version of the chest of a player.
     *
     * <p>The row that is replaced is backed up again, so a restore can be undone.
     *
     * @param uuid the player
     * @param blob the raw contents of the backup
     * @return the number of slots with items, {@code -1} when the write failed
     * @throws SQLException on database errors
     */
    public int restoreBackup(UUID uuid, byte[] blob) throws SQLException {
        SlotCodec.Decoded decoded = SlotCodec.decode(blob, CHEST_SIZE);
        byte[][] payloads = decoded.slots();
        if (!writeSync(uuid, payloads, decoded.trailing())) {
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

    /**
     * Removes a player from the cache.
     *
     * @param uuid the player
     */
    public void evict(UUID uuid) {
        cache.remove(uuid);
        pending.remove(uuid);
    }

    /**
     * Deletes the chest of a player. The old row is backed up before it is deleted.
     *
     * @param uuid the player
     * @throws SQLException on database errors
     */
    public void deleteChest(UUID uuid) throws SQLException {
        store.deleteContents(uuid, "deleted");
        store.clearExported(uuid);
        store.clearImported(uuid);
        cache.remove(uuid);
        pending.remove(uuid);
    }

    /**
     * Waits until every queued database write has been processed.
     *
     * <p>Unlike {@link #flush()} the writer is not shut down, the service stays usable.
     *
     * @param timeoutSeconds how long the wait may take
     * @return {@code true} when the queue is empty
     */
    public boolean awaitWrites(long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!pending.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Waits until every queued database write has been processed.
     */
    public void flush() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Timed out while flushing the ender chest database");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    @Override
    public void close() {
        flush();
        store.close();
        cache.clear();
        pending.clear();
    }

    private void write(UUID uuid, byte[][] payloads, byte[] trailing, List<Integer> failedSlots) {
        byte[] blob = SlotCodec.encode(payloads, trailing);
        pending.put(uuid, payloads);
        writer.execute(() -> {
            try {
                store.writeContents(uuid, blob, "save");
                cache.put(uuid, decode(payloads));
                logger.fine("Saved the ender chest of " + uuid);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to save the ender chest of " + uuid, e);
            } finally {
                pending.remove(uuid, payloads);
            }
        });
    }

    /**
     * Writes the chest of a player and waits until the data is in the database.
     *
     * <p>This is used by the import of the vanilla ender chest. The vanilla chest is only cleared
     * after the write succeeded, otherwise the items would be lost when the write fails.
     *
     * @param uuid     the player
     * @param payloads the payloads of the slots
     * @param trailing bytes of the old row that could not be parsed
     * @return {@code true} when the data is stored
     */
    private boolean writeSync(UUID uuid, byte[][] payloads, byte[] trailing) {
        byte[] blob = SlotCodec.encode(payloads, trailing);
        pending.put(uuid, payloads);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                store.writeContents(uuid, blob, "save");
                cache.put(uuid, decode(payloads));
                future.complete(Boolean.TRUE);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to save the ender chest of " + uuid, e);
                future.complete(Boolean.FALSE);
            } finally {
                pending.remove(uuid, payloads);
            }
        });
        try {
            return Boolean.TRUE.equals(future.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, "Timed out while saving the ender chest of " + uuid, e);
            return false;
        }
    }

    private CachedChest decode(byte[][] payloads) {
        ItemStack[] items = new ItemStack[CHEST_SIZE];
        boolean[] unreadable = new boolean[CHEST_SIZE];
        MinecraftItemCodec codec = codec();
        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            byte[] payload = payloads[slot];
            if (payload == null || payload.length == 0) {
                continue;
            }
            try {
                items[slot] = codec.deserialize(payload);
            } catch (ItemCodecException e) {
                // The bytes are kept in the payload, they are written back unchanged.
                unreadable[slot] = true;
                logger.warning("Slot " + slot + " of an ender chest could not be read: " + e.getMessage());
            }
        }
        return new CachedChest(items, payloads, unreadable);
    }

    /**
     * The content of a chest.
     */
    public static final class CachedChest {

        /** The items of the chest. */
        public final ItemStack[] items;
        /** The raw payload of every slot. */
        public final byte[][] payloads;
        /** The slots that could not be read. */
        public final boolean[] unreadable;
        /** The bytes of the row that could not be parsed. */
        public byte[] trailing;

        CachedChest(ItemStack[] items, byte[][] payloads, boolean[] unreadable) {
            this.items = items;
            this.payloads = payloads;
            this.unreadable = unreadable;
        }

        static CachedChest empty() {
            return new CachedChest(new ItemStack[CHEST_SIZE], new byte[CHEST_SIZE][], new boolean[CHEST_SIZE]);
        }

        /**
         * The number of slots that could not be read.
         *
         * @return the number of unreadable slots
         */
        public int unreadableCount() {
            int count = 0;
            for (boolean slot : unreadable) {
                if (slot) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * The result of a save.
     *
     * @param success     whether the data was written
     * @param failedSlots the slots that could not be serialized
     * @param refused     whether the write was refused to protect the stored data
     * @param error       the error message
     */
    public record SaveResult(boolean success, List<Integer> failedSlots, boolean refused, String error) {
    }

    /**
     * The result of a merge with the vanilla ender chest.
     *
     * @param remaining   the content the vanilla ender chest has to keep
     * @param mergedCount the number of items that were moved into the stored chest
     * @param failedCount the number of items that could not be serialized
     * @param success     whether the merge was carried out
     * @param skipped     whether the import was skipped because the content is already stored
     */
    public record MergeResult(List<ItemStack> remaining, int mergedCount, int failedCount,
                              boolean success, boolean skipped) {
    }
}
