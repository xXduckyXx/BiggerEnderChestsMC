package com.echest.enderchest.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides what is written to the database when a chest is saved.
 *
 * <p>The rule is simple and strict: <em>no item may ever disappear because of a technical
 * problem</em>.
 *
 * <ul>
 *   <li>A slot that could be serialized is written as it is.</li>
 *   <li>A slot that could not be serialized keeps the bytes it had before. The item in the GUI
 *       cannot be stored, but the stored item is not destroyed either.</li>
 *   <li>A slot that held data which could not be read keeps its bytes as well, even when the
 *       slot looks empty in the GUI.</li>
 *   <li>If a slot could not be serialized <em>and</em> there are no old bytes to fall back to,
 *       the whole write is refused. Writing an empty slot would delete the item.</li>
 * </ul>
 *
 * <p>This class is free of any Bukkit dependency and can be unit tested without a server.
 */
public final class SavePlanner {

    private SavePlanner() {
    }

    /**
     * Plans a save.
     *
     * @param newPayloads        the freshly serialized payloads, {@code null} for empty slots
     * @param failedSlots        slots whose item could not be serialized
     * @param previousPayloads   the payloads that are currently stored, may be {@code null}
     * @param previousUnreadable slots of the stored data that could not be read, may be {@code null}
     * @return the plan
     */
    public static Plan plan(byte[][] newPayloads, List<Integer> failedSlots,
                            byte[][] previousPayloads, boolean[] previousUnreadable) {
        int slotCount = newPayloads.length;
        byte[][] payloads = new byte[slotCount][];
        System.arraycopy(newPayloads, 0, payloads, 0, slotCount);

        List<Integer> failed = failedSlots == null ? List.of() : new ArrayList<>(failedSlots);
        boolean refused = false;

        for (int slot : failed) {
            if (slot < 0 || slot >= slotCount) {
                continue;
            }
            byte[] preserved = previousPayloads != null && slot < previousPayloads.length
                    ? previousPayloads[slot] : null;
            if (preserved != null && preserved.length > 0) {
                // Keep the bytes that are already stored, the item in the GUI stays in the GUI.
                payloads[slot] = preserved;
            } else {
                // There is nothing to fall back to, writing the slot empty would delete the item.
                refused = true;
            }
        }

        if (previousPayloads != null && previousUnreadable != null) {
            for (int slot = 0; slot < slotCount; slot++) {
                if (slot >= previousPayloads.length || slot >= previousUnreadable.length) {
                    break;
                }
                if (!previousUnreadable[slot]) {
                    continue;
                }
                byte[] preserved = previousPayloads[slot];
                if (preserved != null && preserved.length > 0 && payloads[slot] == null) {
                    // The slot holds data we cannot read. Never drop it.
                    payloads[slot] = preserved;
                }
            }
        }

        return new Plan(payloads, Collections.unmodifiableList(failed), refused);
    }

    /**
     * The result of {@link #plan(byte[][], List, byte[][], boolean[])}.
     *
     * @param payloads    the payloads that have to be written
     * @param failedSlots the slots that could not be serialized
     * @param refused     whether the write must be refused to protect the stored data
     */
    public record Plan(byte[][] payloads, List<Integer> failedSlots, boolean refused) {

        public boolean hasFailedSlots() {
            return !failedSlots.isEmpty();
        }
    }
}
