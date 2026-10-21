package com.echest.enderchest.storage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what stays in the vanilla ender chest after a merge.
 *
 * <p>The rule is simple and strict: <em>an item that was moved into the stored chest must not
 * stay in the vanilla ender chest</em>. If it stayed there, the next join would move it a second
 * time, and every join would add another copy of it. That is how the chests of the players were
 * duplicated over and over again.
 *
 * <p>Only two kinds of items stay in the vanilla ender chest:
 *
 * <ul>
 *   <li>items that did not fit into the stored chest</li>
 *   <li>items that could not be serialized</li>
 * </ul>
 *
 * <p>Nothing is ever deleted, the items that stay are still in the vanilla ender chest.
 *
 * <p>This class is free of any Minecraft dependency and can be unit tested without a server.
 */
public final class VanillaMerge {

    private VanillaMerge() {
    }

    /**
     * Decides which slots of the vanilla ender chest were moved into the stored chest.
     *
     * @param incoming      the payloads of the vanilla ender chest, {@code null} for an empty slot
     * @param leftoverSlots the slots of the incoming content that did not fit
     * @param failedSlots   the slots of the incoming content that could not be serialized
     * @return one flag per slot, {@code true} when the item was moved into the stored chest
     */
    public static boolean[] movedSlots(byte[][] incoming, int[] leftoverSlots,
                                       List<Integer> failedSlots) {
        int slotCount = incoming == null ? 0 : incoming.length;
        boolean[] moved = new boolean[slotCount];

        Set<Integer> stays = new HashSet<>();
        if (leftoverSlots != null) {
            for (int slot : leftoverSlots) {
                stays.add(slot);
            }
        }
        if (failedSlots != null) {
            stays.addAll(failedSlots);
        }

        for (int slot = 0; slot < slotCount; slot++) {
            byte[] payload = incoming[slot];
            if (payload == null || payload.length == 0) {
                continue;
            }
            moved[slot] = !stays.contains(slot);
        }
        return moved;
    }
}
