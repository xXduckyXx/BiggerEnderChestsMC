package com.echest.enderchest.storage;

/**
 * Merges incoming slot payloads into the free slots of an existing chest.
 *
 * <p>The merge is deliberately implemented on raw payloads (and not on {@code ItemStack}s) so
 * that it is completely independent of Bukkit and can be unit tested without a server.
 *
 * <p>Nothing is ever overwritten: incoming payloads are placed in the first free slot of the
 * target. Everything that does not fit is returned as {@link Result#leftover()} so the caller can
 * put it back where it came from instead of deleting it.
 */
public final class PayloadMerger {

    private PayloadMerger() {
    }

    /**
     * Merges {@code incoming} into {@code existing}.
     *
     * @param existing the current content of the chest
     * @param incoming the payloads that should be added
     * @return the merged content and the payloads that did not fit
     */
    public static Result merge(byte[][] existing, byte[][] incoming) {
        int slotCount = Math.max(existing == null ? 0 : existing.length,
                incoming == null ? 0 : incoming.length);
        byte[][] merged = new byte[slotCount][];
        boolean[] occupied = new boolean[slotCount];
        if (existing != null) {
            for (int slot = 0; slot < existing.length && slot < slotCount; slot++) {
                byte[] payload = existing[slot];
                if (payload != null && payload.length > 0) {
                    merged[slot] = payload;
                    occupied[slot] = true;
                }
            }
        }

        byte[][] leftover = new byte[slotCount][];
        int[] leftoverSlots = new int[slotCount];
        int leftoverCount = 0;
        if (incoming != null) {
            for (int slot = 0; slot < incoming.length && slot < slotCount; slot++) {
                byte[] payload = incoming[slot];
                if (payload == null || payload.length == 0) {
                    continue;
                }
                int target = firstFree(occupied);
                if (target < 0) {
                    leftover[leftoverCount] = payload;
                    leftoverSlots[leftoverCount] = slot;
                    leftoverCount++;
                } else {
                    merged[target] = payload;
                    occupied[target] = true;
                }
            }
        }

        return new Result(merged, compact(leftover, leftoverCount),
                java.util.Arrays.copyOf(leftoverSlots, leftoverCount), leftoverCount);
    }

    /**
     * Counts the free (empty) slots of a chest.
     *
     * @param slots the payloads
     * @return number of free slots
     */
    public static int countFree(byte[][] slots) {
        if (slots == null) {
            return 0;
        }
        int free = 0;
        for (byte[] slot : slots) {
            if (slot == null || slot.length == 0) {
                free++;
            }
        }
        return free;
    }

    private static int firstFree(boolean[] occupied) {
        for (int slot = 0; slot < occupied.length; slot++) {
            if (!occupied[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private static byte[][] compact(byte[][] slots, int count) {
        byte[][] result = new byte[count][];
        System.arraycopy(slots, 0, result, 0, count);
        return result;
    }

    /**
     * Result of a merge operation.
     *
     * @param merged       the merged content
     * @param leftover     payloads that did not fit into the chest
     * @param leftoverSlots the slots of the incoming content the leftovers came from
     * @param count        number of leftover payloads
     */
    public record Result(byte[][] merged, byte[][] leftover, int[] leftoverSlots, int count) {

        public boolean hasLeftover() {
            return count > 0;
        }
    }
}
