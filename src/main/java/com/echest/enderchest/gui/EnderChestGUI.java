package com.echest.enderchest.gui;

import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.util.ItemSerializationException;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EnderChestGUI {

    private static final Map<UUID, Inventory> openGuis = new ConcurrentHashMap<>();

    public static void open(Player player, DatabaseManager db) {
        UUID uuid = player.getUniqueId();

        if (isOpen(uuid)) {
            saveAndClose(player, db);
        }

        ItemStack[] contents = db.loadEnderChest(uuid);

        List<String> failures = db.serializeItemsToNbtBytesWithValidation(contents);
        if (!failures.isEmpty()) {
            player.sendMessage(Component.text("Warning: " + failures.size() + " item(s) could not be saved. Contact an admin.").color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            Bukkit.getLogger().warning("Ender chest pre-validation for " + player.getName() + " had " + failures.size() + " failures: " + String.join(", ", failures));
        }

        Inventory gui = Bukkit.createInventory(player, 54, Component.text("Ender Chest"));
        gui.setContents(contents);
        player.openInventory(gui);

        openGuis.put(uuid, gui);
    }

    public static boolean isOpen(UUID uuid) {
        return openGuis.containsKey(uuid);
    }

    public static boolean isOurGUI(Inventory inv) {
        return openGuis.containsValue(inv);
    }

    public static UUID getPlayerForGUI(Inventory inv) {
        for (Map.Entry<UUID, Inventory> entry : openGuis.entrySet()) {
            if (entry.getValue().equals(inv)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static void saveAndClose(Player player, DatabaseManager db) {
        UUID uuid = player.getUniqueId();
        Inventory gui = openGuis.remove(uuid);
        if (gui != null) {
            db.saveEnderChest(uuid, gui.getContents());

            List<String> failures = db.serializeItemsToNbtBytesWithValidation(gui.getContents());
            if (!failures.isEmpty()) {
                player.sendMessage(Component.text("Warning: Some/all items failed to save. Contact an admin.").color(net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
    }

    public static void updateContents(UUID uuid, ItemStack[] contents) {
        Inventory gui = openGuis.get(uuid);
        if (gui != null) {
            gui.setContents(contents);
        }
    }

    public static void remove(UUID uuid) {
        openGuis.remove(uuid);
    }
}
