package com.echest.enderchest.api;
/*

Oh hello there reader.

thank you for reading the code :3
it means a lot to me that your interested in this project :P
feel free to edit whatever :D

*/
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

/**
 * The API of the plugin.
 *
 * <p>The plugin registers itself in the service manager of Paper:
 * <pre>
 *   EnderChestAPI api = Bukkit.getServicesManager().load(EnderChestAPI.class);
 * </pre>
 *
 * <p>Add {@code depend: [EnderChestDB]} to the plugin.yml of your plugin, so that it is loaded
 * after this one.
 */
public interface EnderChestAPI {

    /**
     * The content of the ender chest of a player.
     *
     * <p>When the chest of the player is open, the content of the window is returned, it is the
     * current state of the chest.
     *
     * @param uuid the player
     * @return the 54 slots of the chest, {@code null} entries are empty slots
     */
    ItemStack @NotNull [] getEnderChest(@NotNull UUID uuid);

    /**
     * Replaces the content of the ender chest of a player.
     *
     * <p>More than 54 slots are truncated, the chest has 54 slots.
     *
     * @param uuid     the player
     * @param contents the new content
     */
    void setEnderChest(@NotNull UUID uuid, ItemStack @NotNull [] contents);

    /**
     * Whether a player has an ender chest in the database.
     *
     * @param uuid the player
     * @return {@code true} when the player has a chest
     */
    boolean hasEnderChest(@NotNull UUID uuid);

    /**
     * Empties the ender chest of a player.
     *
     * @param uuid the player
     */
    void clearEnderChest(@NotNull UUID uuid);
}
