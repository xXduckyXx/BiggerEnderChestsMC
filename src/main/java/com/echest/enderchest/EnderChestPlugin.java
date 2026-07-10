package com.echest.enderchest;

import com.echest.enderchest.api.EnderChestAPI;
import com.echest.enderchest.command.EnderChestCommand;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import com.echest.enderchest.listener.PlayerListener;
import com.echest.enderchest.migration.MigrationManager;
import com.echest.enderchest.util.AnimationUtil;
import com.echest.enderchest.util.ItemSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public class EnderChestPlugin extends JavaPlugin implements EnderChestAPI {

    private static EnderChestPlugin instance;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        ItemSerializer.init(this);
        AnimationUtil.init(this);
        this.databaseManager = new DatabaseManager(this);

        if (!getConfig().getBoolean("migrated", false)) {
            getLogger().info("Running initial ender chest migration...");
            MigrationManager migrationManager = new MigrationManager(this, databaseManager);
            migrationManager.runMigration();
            getConfig().set("migrated", true);
            saveConfig();
            getLogger().info("Migration complete.");
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this, databaseManager), this);

        var command = getCommand("enderchest");
        if (command != null) {
            command.setExecutor(new EnderChestCommand(this, databaseManager));
        }

        getLogger().info("EnderChestDB enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Override
    public ItemStack @NotNull [] getEnderChest(@NotNull UUID uuid) {
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
