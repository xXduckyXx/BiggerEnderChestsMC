package com.echest.enderchest.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Marks an inventory as one of the bigger ender chests of this plugin.
 *
 * <p>The holder is used instead of scanning a map of open inventories, it makes the check for
 * "is this one of our inventories" a constant time operation. It also carries the owner of the
 * chest and the slots that hold data which could not be read.
 */
public final class EnderChestHolder implements InventoryHolder {

    private final UUID owner;
    private final Set<Integer> lockedSlots = Collections.synchronizedSet(new HashSet<>());
    private volatile Inventory inventory;

    /**
     * Creates a holder.
     *
     * @param owner the player the chest belongs to
     */
    public EnderChestHolder(UUID owner) {
        this.owner = owner;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Sets the inventory of this holder.
     *
     * @param inventory the inventory
     */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * The player the chest belongs to.
     *
     * @return the owner
     */
    public UUID owner() {
        return owner;
    }

    /**
     * Locks a slot, the player cannot take the item out of it.
     *
     * @param slot the slot
     */
    public void lockSlot(int slot) {
        lockedSlots.add(slot);
    }

    /**
     * Whether a slot is locked.
     *
     * @param slot the slot
     * @return {@code true} when the slot is locked
     */
    public boolean isLocked(int slot) {
        return lockedSlots.contains(slot);
    }

    /**
     * Whether at least one of the given raw slots is locked.
     *
     * <p>Drag events do not go through {@code InventoryClickEvent}, so they have to check the
     * locked slots themselves. Otherwise the placeholder of an unreadable slot could be dragged
     * into the chest and stored as a real item.
     *
     * @param rawSlots the raw slots of the event, may be {@code null}
     * @return {@code true} when a locked slot is involved
     */
    public boolean touchesLockedSlot(Collection<Integer> rawSlots) {
        if (rawSlots == null || rawSlots.isEmpty()) {
            return false;
        }
        synchronized (lockedSlots) {
            for (Integer slot : rawSlots) {
                if (slot != null && lockedSlots.contains(slot)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The locked slots.
     *
     * @return a copy of the locked slots
     */
    public Set<Integer> lockedSlots() {
        synchronized (lockedSlots) {
            return Set.copyOf(lockedSlots);
        }
    }
}
