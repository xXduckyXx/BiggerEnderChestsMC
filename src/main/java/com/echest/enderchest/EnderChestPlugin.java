package com.echest.enderchest;

import com.echest.enderchest.api.EnderChestAPI;
import com.echest.enderchest.command.EnderChestCommand;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import com.echest.enderchest.listener.PlayerListener;
import com.echest.enderchest.migration.MigrationManager;
import com.echest.enderchest.storage.PaperItemCodec;
import com.echest.enderchest.util.AnimationUtil;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Level;

public class EnderChestPlugin extends JavaPlugin implements EnderChestAPI {

    private static EnderChestPlugin instance;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (!PaperItemCodec.isSupported()) {
            getLogger().severe("This plugin needs the Paper API (ItemStack#serializeAsBytes), "
                    + "which is not available on this server. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AnimationUtil.init(this);

        try {
            this.databaseManager = new DatabaseManager(this, new PaperItemCodec());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the ender chest database, disabling the plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!getConfig().getBoolean("migrated", false)) {
            getLogger().info("Running initial ender chest migration...");
            MigrationManager migrationManager = new MigrationManager(this, databaseManager);
            if (migrationManager.runMigration()) {
                getConfig().set("migrated", true);
                saveConfig();
                getLogger().info("Migration complete.");
            } else {
                getLogger().warning("The migration did not finish, it will be retried on the next start. "
                        + "Players that already joined have their items in the database.");
            }
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this, databaseManager), this);

        var command = getCommand("enderchest");
        if (command != null) {
            EnderChestCommand executor = new EnderChestCommand(this, databaseManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Other plugins can use the API through the service manager.
        getServer().getServicesManager().register(EnderChestAPI.class, this, this, ServicePriority.Normal);

        int autosaveSeconds = Math.max(0, getConfig().getInt("autosave-seconds", 60));
        if (autosaveSeconds > 0) {
            long period = autosaveSeconds * 20L;
            getServer().getScheduler().runTaskTimer(this,
                    () -> EnderChestGUI.saveAll(databaseManager), period, period);
            getLogger().info("Autosaving open ender chests every " + autosaveSeconds + " seconds.");
        }

        getLogger().info("EnderChestDB enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            // Save every open window before the database is closed, otherwise the items of a
            // player who is still in the window when the server stops would be lost.
            EnderChestGUI.saveAll(databaseManager);
            EnderChestGUI.closeAll();
            databaseManager.close();
        }
        getServer().getServicesManager().unregister(EnderChestAPI.class, this);
    }

    /**
     * The ender chest of a player.
     *
     * <p>When the chest of the player is open, the content of the window is returned, it is the
     * current state of the chest. The slots that hold data which could not be read are empty in
     * the result, they are kept in the database.
     *
     * @param uuid the player
     * @return the items of the chest
     */
    @Override
    public ItemStack @NotNull [] getEnderChest(@NotNull UUID uuid) {
        ItemStack[] open = EnderChestGUI.getApiContents(uuid);
        if (open != null) {
            return open;
        }
        return databaseManager.loadEnderChest(uuid);
    }

    @Override
    public void setEnderChest(@NotNull UUID uuid, ItemStack @NotNull [] contents) {
        if (contents.length > 54) {
            getLogger().warning("setEnderChest called with " + contents.length
                    + " slots (max 54), truncating. Caller: " + getCallerInfo());
        }
        ItemStack[] storage = Arrays.copyOf(contents, 54);

        databaseManager.saveEnderChest(uuid, storage);

        if (EnderChestGUI.isOpen(uuid)) {
            getLogger().warning("setEnderChest overwriting open GUI contents for " + uuid
                    + " — updating live GUI to prevent data loss");
            EnderChestGUI.updateContents(uuid, storage);
        }
    }

    @Override
    public boolean hasEnderChest(@NotNull UUID uuid) {
        return databaseManager.hasEnderChest(uuid);
    }

    @Override
    public void clearEnderChest(@NotNull UUID uuid) {
        ItemStack[] empty = new ItemStack[54];
        databaseManager.saveEnderChest(uuid, empty);

        if (EnderChestGUI.isOpen(uuid)) {
            getLogger().warning("clearEnderChest called while GUI is open for " + uuid + " — updating live GUI");
            EnderChestGUI.updateContents(uuid, empty);
        }
    }

    public static EnderChestPlugin getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    private String getCallerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 5); i++) {
            String className = stack[i].getClassName();
            if (!className.equals(EnderChestPlugin.class.getName())) {
                return className + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber();
            }
        }
        return "unknown";
    }
}
