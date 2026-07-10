package com.echest.enderchest.command;

import com.echest.enderchest.EnderChestPlugin;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class EnderChestCommand implements CommandExecutor {

    private final EnderChestPlugin plugin;
    private final DatabaseManager db;

    public EnderChestCommand(EnderChestPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("toggle")) {
            if (!sender.hasPermission("enderchestdb.admin")) {
                sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            boolean current = plugin.getConfig().getBoolean("commands-enabled", true);
            plugin.getConfig().set("commands-enabled", !current);
            plugin.saveConfig();
            sender.sendMessage(Component.text("/enderchest commands " + (!current ? "enabled" : "disabled") + ".", NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!plugin.getConfig().getBoolean("commands-enabled", true)) {
            player.sendMessage(Component.text("Ender chest commands are currently disabled.", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("enderchestdb.command")) {
            player.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (EnderChestGUI.isOpen(player.getUniqueId())) {
            return true;
        }

        EnderChestGUI.open(player, db);
        return true;
    }
}
