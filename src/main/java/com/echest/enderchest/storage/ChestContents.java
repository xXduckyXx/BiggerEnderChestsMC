package com.echest.enderchest.storage;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * The content of a single ender chest.
 *
 * <p>Besides the items themselves the content keeps the raw payload of every slot. Payloads of
 * slots that could not be read (for example because they were written by a newer Minecraft
 * version) are kept as they are, so that a later save can write them back untouched instead of
 * destroying them.
 */
public final class ChestContents {

    private final ItemStack[] items;
    private final byte[][] payloads;
    private final boolean[] unreadable;

    private ChestContents(ItemStack[] items, byte[][] payloads, boolean[] unreadable) {
        this.items = items;
        this.payloads = payloads;
        this.unreadable = unreadable;
    }

    /**
     * Creates an empty chest.
     *
     * @param slotCount number of slots
     * @return the empty content
     */
    public static ChestContents empty(int slotCount) {
        return new ChestContents(new ItemStack[slotCount], new byte[slotCount][],
                new boolean[slotCount]);
    }

    /**
     * Creates a chest content.
     *
     * @param items      the items, {@code null} entries are empty slots
     * @param payloads   the raw payload of every slot
     * @param unreadable slots that could not be read
     * @return the content
     */
    public static ChestContents of(ItemStack[] items, byte[][] payloads, boolean[] unreadable) {
        return new ChestContents(items, payloads, unreadable);
    }

    /**
     * The items of this chest.
     *
     * @return a copy of the item array
     */
    public ItemStack[] items() {
        return Arrays.copyOf(items, items.length);
    }

    /**
     * The item of a single slot.
     *
     * @param slot the slot
     * @return the item or {@code null}
     */
    public ItemStack item(int slot) {
        return items[slot];
    }

    /**
     * The raw payload of every slot.
     *
     * @return the payloads
     */
    public byte[][] payloads() {
        return payloads;
    }

    /**
     * The raw payload of a single slot.
     *
     * @param slot the slot
     * @return the payload or {@code null}
     */
    public byte[] payload(int slot) {
        return payloads[slot];
    }

    /**
     * Whether the payload of a slot could not be read.
     *
     * @param slot the slot
     * @return {@code true} when the slot holds data that could not be deserialized
     */
    public boolean isUnreadable(int slot) {
        return unreadable[slot];
    }

    /**
     * The slots that could not be read.
     *
     * @return a copy of the unreadable flags
     */
    public boolean[] unreadableSlots() {
        return Arrays.copyOf(unreadable, unreadable.length);
    }

    /**
     * Whether the chest contains data that could not be read.
     *
     * @return {@code true} when at least one slot is unreadable
     */
    public boolean hasUnreadable() {
        for (boolean slot : unreadable) {
            if (slot) {
                return true;
            }
        }
        return false;
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
