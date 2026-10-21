package com.echest.enderchest.migration;

import com.echest.enderchest.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Moves the ender chest of the players that are online into the database.
 *
 * <p>The migration only uses the public API of the server. The previous implementation read the
 * {@code .dat} files of the players itself, with a lot of reflection into the internals of the
 * server, and it rewrote those files afterwards. That never worked, and a failed rewrite of a
 * player file destroys the ender chest of that player.
 *
 * <p>Offline players are migrated when they join the server, the join handler of the plugin does
 * that. The migration that runs on the start of the server therefore only has to deal with the
 * players that are online at that moment, for example after a {@code /reload}.
 */
public class MigrationManager {

    private final JavaPlugin plugin;
    private final DatabaseManager db;

    /**
     * Creates the migration.
     *
     * @param plugin the plugin
     * @param db     the database
     */
    public MigrationManager(JavaPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    /**
     * Migrates the ender chest of every player that is online.
     *
     * <p>The method has to be called from the main thread, it touches the inventories of the
     * players. It blocks until the migration is done, the server thread is never blocked while a
     * background thread changes an inventory.
     *
     * @return {@code true} when the migration finished without an error
     */
    public boolean runMigration() {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("The ender chest migration was started from the wrong thread, "
                    + "it has to run on the main thread.");
            return false;
        }

        try {
            plugin.getLogger().info("Starting the ender chest migration of the online players...");
            int migrated = 0;
            int skipped = 0;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (db.hasEnderChest(player.getUniqueId())) {
                    // The chest of the player is already in the database, it must not be
                    // overwritten with the content of the vanilla ender chest.
                    skipped++;
                    continue;
                }
                if (!migrate(player)) {
                    return false;
                }
                migrated++;
            }

            plugin.getLogger().info("Migration of the online players finished: " + migrated
                    + " migrated, " + skipped + " already in the database.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "The ender chest migration failed", e);
            return false;
        }
    }

    /**
     * Moves the ender chest of a single player into the database.
     *
     * <p>The content of the vanilla ender chest is merged into the stored chest, the stored chest
     * is never overwritten. The vanilla ender chest is only cleared for the items that really
     * made it into the database.
     *
     * @param player the player
     * @return {@code true} on success
     */
    private boolean migrate(Player player) {
        ItemStack[] contents = player.getEnderChest().getContents();

        boolean empty = true;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                empty = false;
                break;
            }
        }

        if (empty) {
            // Nothing to migrate, but the row is created so the chest is not imported again.
            db.saveEnderChest(player.getUniqueId(), new ItemStack[DatabaseManager.CHEST_SIZE]);
            return true;
        }

        DatabaseManager.VanillaMergeResult result = db.mergeVanillaContents(
                player.getUniqueId(), contents);
        if (!result.success()) {
            plugin.getLogger().severe("Could not migrate the ender chest of " + player.getName()
                    + ", the vanilla ender chest was left untouched.");
            return false;
        }

        // Only the items that really made it into the database are removed from the vanilla
        // ender chest, everything else stays there.
        player.getEnderChest().setContents(result.remaining());
        plugin.getLogger().info("Migrated the ender chest of " + player.getName() + ": "
                + result.mergedCount() + " item(s) moved, " + result.failedCount() + " kept.");
        return true;
    }
}
