package com.echest.enderchest.util;

import org.bukkit.inventory.ItemStack;

public class ItemSerializationException extends RuntimeException {

    private final ItemStack item;
    private final int slot;

    public ItemSerializationException(ItemStack item, int slot, String message) {
        super(message);
        this.item = item;
        this.slot = slot;
    }

    public ItemSerializationException(ItemStack item, int slot, String message, Throwable cause) {
        super(message, cause);
        this.item = item;
        this.slot = slot;
    }

    public ItemStack getItem() {
        return item;
    }

    public int getSlot() {
        return slot;
    }

    @Override
    public String getMessage() {
        String type = item != null ? item.getType().name() : "unknown";
        int amount = item != null ? item.getAmount() : 0;
        return "slot " + slot + " (" + type + " x" + amount + "): " + super.getMessage();
    }
}
