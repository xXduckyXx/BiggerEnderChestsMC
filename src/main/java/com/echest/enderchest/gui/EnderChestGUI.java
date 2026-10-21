package com.echest.enderchest.gui;

import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.storage.ChestContents;
import com.echest.enderchest.util.ItemSerializationException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bigger ender chest window.
 *
 * <p>The window is a normal chest inventory with 54 slots, so vanilla clients can use it without
 * any resource pack or mod. Which player a window belongs to is stored in its
 * {@link EnderChestHolder}.
 */
public final class EnderChestGUI {

    /** Number of slots of the bigger ender chest. */
    public static final int SIZE = 54;

    private static final Map<UUID, EnderChestHolder> openGuis = new ConcurrentHashMap<>();

    private EnderChestGUI() {
    }

    /**
     * Opens the bigger ender chest of a player.
     *
     * <p>The chest is only registered as open when the window could really be opened. If another
     * plugin cancels the event the player keeps their old window and the chest is not registered,
     * so it does not stay "open" until the player logs in again.
     *
     * @param player the player
     * @param db     the database
     * @return {@code true} when the window was opened
     */
    public static boolean open(Player player, DatabaseManager db) {
        UUID uuid = player.getUniqueId();

        if (isOpen(uuid)) {
            saveAndClose(player, db);
        }

        ChestContents contents = db.loadChest(uuid);

        EnderChestHolder holder = new EnderChestHolder(uuid);
        Inventory gui = Bukkit.createInventory(holder, SIZE, Component.text("Ender Chest"));
        holder.setInventory(gui);
        gui.setContents(contents.items());

        boolean[] unreadable = contents.unreadableSlots();
        for (int slot = 0; slot < unreadable.length; slot++) {
            if (unreadable[slot]) {
                holder.lockSlot(slot);
                gui.setItem(slot, unreadablePlaceholder());
            }
        }

        player.openInventory(gui);

        if (player.getOpenInventory().getTopInventory() != gui) {
            // Another plugin cancelled the open, the player still sees their old window.
            player.sendMessage(Component.text("Your ender chest could not be opened.")
                    .color(NamedTextColor.RED));
            return false;
        }

        openGuis.put(uuid, holder);

        if (contents.hasUnreadable()) {
            player.sendMessage(Component.text("Warning: " + contents.unreadableCount()
                            + " item(s) in your ender chest could not be read. They are kept as they are, "
                            + "please contact an admin.")
                    .color(NamedTextColor.YELLOW));
        }
        return true;
    }

    /**
     * Whether the bigger ender chest of a player is open.
     *
     * @param uuid the player
     * @return {@code true} when the window is open
     */
    public static boolean isOpen(UUID uuid) {
        return openGuis.containsKey(uuid);
    }

    /**
     * Whether an inventory is one of our bigger ender chests.
     *
     * @param inventory the inventory
     * @return {@code true} when it is one of ours
     */
    public static boolean isOurGUI(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof EnderChestHolder;
    }

    /**
     * The player a window belongs to.
     *
     * @param inventory the inventory
     * @return the owner or {@code null}
     */
    public static UUID getPlayerForGUI(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        if (inventory.getHolder() instanceof EnderChestHolder holder) {
            return holder.owner();
        }
        return null;
    }

    /**
     * The live content of an open window.
     *
     * @param uuid the player
     * @return the items of the window or {@code null} when the window is not open
     */
    public static ItemStack[] getContents(UUID uuid) {
        EnderChestHolder holder = openGuis.get(uuid);
        if (holder == null || holder.getInventory() == null) {
            return null;
        }
        return holder.getInventory().getContents();
    }

    /**
     * The live content of an open window, with the placeholders of the unreadable slots removed.
     *
     * @param uuid the player
     * @return the items of the window or {@code null} when the window is not open
     */
    public static ItemStack[] getApiContents(UUID uuid) {
        EnderChestHolder holder = openGuis.get(uuid);
        if (holder == null || holder.getInventory() == null) {
            return null;
        }
        ItemStack[] contents = holder.getInventory().getContents();
        for (int slot : holder.lockedSlots()) {
            if (slot >= 0 && slot < contents.length) {
                // The placeholder is not a real item, the original data is kept in the database.
                contents[slot] = null;
            }
        }
        return contents;
    }

    /**
     * Saves the window of a player without closing it.
     *
     * @param uuid the player
     * @param db   the database
     * @return the result of the save
     */
    public static DatabaseManager.SaveResult save(UUID uuid, DatabaseManager db) {
        EnderChestHolder holder = openGuis.get(uuid);
        if (holder == null || holder.getInventory() == null) {
            return new DatabaseManager.SaveResult(true, List.of(), false, null);
        }
        return db.saveEnderChest(uuid, contentsForSave(holder));
    }

    /**
     * Saves the window of a player and closes it.
     *
     * @param player the player
     * @param db     the database
     */
    public static void saveAndClose(Player player, DatabaseManager db) {
        UUID uuid = player.getUniqueId();
        EnderChestHolder holder = openGuis.remove(uuid);
        if (holder != null && holder.getInventory() != null) {
            DatabaseManager.SaveResult result = db.saveEnderChest(uuid, contentsForSave(holder));
            warnAboutFailedSlots(player, result);
        }
    }

    /**
     * Saves every open window. Used by the autosave and when the plugin is disabled.
     *
     * @param db the database
     */
    public static void saveAll(DatabaseManager db) {
        for (Map.Entry<UUID, EnderChestHolder> entry : openGuis.entrySet()) {
            EnderChestHolder holder = entry.getValue();
            if (holder.getInventory() == null) {
                continue;
            }
            db.saveEnderChest(entry.getKey(), contentsForSave(holder));
        }
    }

    /**
     * Closes every open window without saving it.
     */
    public static void closeAll() {
        openGuis.clear();
    }

    /**
     * Warns a player about the items that could not be stored.
     *
     * @param player the player
     * @param result the result of the save
     */
    public static void warnAboutFailedSlots(Player player, DatabaseManager.SaveResult result) {
        if (result == null) {
            return;
        }
        if (result.refused()) {
            player.sendMessage(Component.text("Your ender chest could not be saved, the stored items "
                            + "were kept. Please contact an admin.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (result.hasFailedSlots()) {
            player.sendMessage(Component.text(result.failedSlots().size()
                            + " item(s) could not be saved and were left in the chest. "
                            + "Please contact an admin.")
                    .color(NamedTextColor.RED));
        }
    }

    /**
     * Updates the content of an open window.
     *
     * @param uuid     the player
     * @param contents the new content
     */
    public static void updateContents(UUID uuid, ItemStack[] contents) {
        EnderChestHolder holder = openGuis.get(uuid);
        if (holder != null && holder.getInventory() != null) {
            holder.getInventory().setContents(contents);
        }
    }

    /**
     * Removes a window from the list of open windows without saving it.
     *
     * @param uuid the player
     */
    public static void remove(UUID uuid) {
        openGuis.remove(uuid);
    }

    /**
     * The content of a window as it has to be stored.
     *
     * <p>Slots that hold data which could not be read are shown as a placeholder. The placeholder
     * must never be stored, the original bytes of the slot are kept instead.
     *
     * @param holder the window
     * @return the content
     */
    private static ItemStack[] contentsForSave(EnderChestHolder holder) {
        ItemStack[] contents = holder.getInventory().getContents();
        for (int slot : holder.lockedSlots()) {
            if (slot >= 0 && slot < contents.length) {
                contents[slot] = null;
            }
        }
        return contents;
    }

    private static ItemStack unreadablePlaceholder() {
        ItemStack placeholder = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Unreadable item")
                    .color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("This item could not be read, the original data is "
                    + "kept and written back when the chest is saved.")
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            placeholder.setItemMeta(meta);
        }
        return placeholder;
    }
}
