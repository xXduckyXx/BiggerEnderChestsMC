package com.echest.enderchest.command;

import com.echest.enderchest.EnderChestPlugin;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import com.echest.enderchest.storage.ChestStore;
import com.echest.enderchest.util.ItemSerializationException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;


/**
 * The {@code /enderchest} command.
 */
public class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChestPlugin plugin;
    private final DatabaseManager db;

    public EnderChestCommand(EnderChestPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "toggle":
                    return toggle(sender);
                case "export":
                    return export(sender, args);
                case "import":
                    return importChest(sender, args);
                case "backups":
                    return listBackups(sender, args);
                case "restore":
                    return restoreBackup(sender, args);
                case "selftest":
                    return selftest(sender);
                case "help":
                    sendHelp(sender);
                    return true;
                default:
                    break;
            }
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

    private boolean toggle(CommandSender sender) {
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

    /**
     * Moves the content of the stored chest back into the vanilla ender chest.
     *
     * <p>This is the way out for a server that wants to uninstall the plugin, the items end up in
     * the vanilla ender chest again.
     *
     * @param sender the sender
     * @param args   the command arguments
     * @return always {@code true}
     */
    private boolean export(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            if (!sender.hasPermission("enderchestdb.admin")) {
                sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            target = player;
        }

        if (db.isExported(target.getUniqueId())) {
            sender.sendMessage(Component.text("The ender chest of " + target.getName()
                    + " is already in the vanilla ender chest.").color(NamedTextColor.YELLOW));
            return true;
        }
        if (!db.hasEnderChest(target.getUniqueId())) {
            sender.sendMessage(Component.text(target.getName() + " does not have a stored ender chest.",
                    NamedTextColor.YELLOW));
            return true;
        }

        ItemStack[] vanilla = db.exportToVanilla(target.getUniqueId());
        if (vanilla == null) {
            sender.sendMessage(Component.text("The ender chest of " + target.getName() + " could not be "
                            + "exported. It has more items than the vanilla ender chest can hold, "
                            + "or it contains items that cannot be read. Please move some items out "
                            + "of it first.")
                    .color(NamedTextColor.RED));
            return true;
        }

        target.getEnderChest().setContents(vanilla);
        target.sendMessage(Component.text("Your ender chest was moved back into your vanilla ender chest.")
                .color(NamedTextColor.GREEN));
        sender.sendMessage(Component.text("The ender chest of " + target.getName()
                + " was moved into the vanilla ender chest.").color(NamedTextColor.GREEN));
        plugin.getLogger().info("Exported the ender chest of " + target.getName() + ".");
        return true;
    }

    /**
     * Moves the content of the vanilla ender chest back into the stored chest.
     *
     * @param sender the sender
     * @param args   the command arguments
     * @return always {@code true}
     */
    private boolean importChest(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            if (!sender.hasPermission("enderchestdb.admin")) {
                sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            target = player;
        }

        ItemStack[] vanilla = target.getEnderChest().getContents();
        DatabaseManager.VanillaMergeResult result = db.importFromVanilla(target.getUniqueId(), vanilla);
        if (!result.success()) {
            sender.sendMessage(Component.text("The vanilla ender chest of " + target.getName()
                    + " could not be imported, it was left untouched.").color(NamedTextColor.RED));
            return true;
        }

        target.getEnderChest().setContents(result.remaining());
        target.sendMessage(Component.text(result.mergedCount() + " item(s) of your vanilla ender chest "
                + "were moved into the bigger ender chest.").color(NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Imported the vanilla ender chest of " + target.getName() + ".")
                .color(NamedTextColor.GREEN));
        return true;
    }

    /**
     * Lists the backed up versions of the ender chest of a player.
     *
     * <p>The backups are the versions of the row before it was overwritten. They are used to undo
     * a bad write, for example a chest that was duplicated by an older release of the plugin.
     *
     * @param sender the sender
     * @param args   the command arguments
     * @return always {@code true}
     */
    private boolean listBackups(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return true;
        }

        List<ChestStore.Backup> backups = db.listBackups(target.getUniqueId());
        if (backups.isEmpty()) {
            sender.sendMessage(Component.text("There is no backup of the ender chest of "
                    + target.getName() + ".").color(NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("Backups of the ender chest of " + target.getName()
                + ", newest first:").color(NamedTextColor.GOLD));
        for (int index = 0; index < backups.size(); index++) {
            final int number = index;
            ChestStore.Backup backup = backups.get(index);
            sender.sendMessage(Component.text("#" + number + " - "
                    + new Date(backup.createdAt()) + " - " + backup.reason()
                    + " - /enderchest restore " + target.getName() + " " + number)
                    .color(NamedTextColor.GRAY));
        }
        return true;
    }

    /**
     * Restores a backed up version of the ender chest of a player.
     *
     * <p>The row that is replaced is backed up again, so the restore can be undone.
     *
     * @param sender the sender
     * @param args   the command arguments
     * @return always {@code true}
     */
    private boolean restoreBackup(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return true;
        }

        int index = 0;
        if (args.length > 2) {
            try {
                index = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("The backup has to be a number.", NamedTextColor.RED));
                return true;
            }
        }

        List<ChestStore.Backup> backups = db.listBackups(target.getUniqueId());
        if (index < 0 || index >= backups.size()) {
            sender.sendMessage(Component.text("There is no backup #" + index + " of "
                    + target.getName() + ". Use /enderchest backups " + target.getName()
                    + " to see the list.").color(NamedTextColor.RED));
            return true;
        }
        ChestStore.Backup backup = backups.get(index);

        // The window saves itself when it is closed, so it has to be closed before the backup is
        // written, otherwise the window would write its content over it.
        if (EnderChestGUI.isOpen(target.getUniqueId())) {
            EnderChestGUI.saveAndClose(target, db);
            target.closeInventory();
        }

        int slots = db.restoreBackup(target.getUniqueId(), backup.contents());
        if (slots < 0) {
            sender.sendMessage(Component.text("The ender chest of " + target.getName()
                    + " could not be restored.").color(NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("Restored backup #" + index + " of the ender chest of "
                + target.getName() + ", " + slots + " slot(s) with items.").color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Your ender chest was restored by an admin.")
                .color(NamedTextColor.GREEN));
        plugin.getLogger().info("Restored backup #" + index + " of the ender chest of "
                + target.getName() + ".");
        return true;
    }

    /**
     * Checks the item serialization, the database and the merge on the running server.
     *
     * <p>The command writes a test row into the database and removes it again, it is meant for
     * admins that want to know whether the storage works.
     *
     * @param sender the sender
     * @return always {@code true}
     */
    private boolean selftest(CommandSender sender) {
        if (!sender.hasPermission("enderchestdb.admin")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return true;
        }

        ItemStack stack = new ItemStack(Material.DIAMOND, 5);
        try {
            byte[] payload = db.codec().serialize(stack);
            ItemStack back = db.codec().deserialize(payload);
            boolean same = back != null && back.getAmount() == stack.getAmount()
                    && back.getType() == stack.getType();
            sender.sendMessage(Component.text("Item round trip: " + (same ? "ok" : "FAILED")
                    + " (" + payload.length + " bytes)")
                    .color(same ? NamedTextColor.GREEN : NamedTextColor.RED));
        } catch (ItemSerializationException e) {
            sender.sendMessage(Component.text("Item round trip: FAILED (" + e.getMessage() + ")")
                    .color(NamedTextColor.RED));
        }

        // A random id, every run of the self test has to start with an empty chest.
        UUID test = UUID.randomUUID();
        try {
            db.saveEnderChest(test, new ItemStack[]{stack});

            // The merge must not duplicate the items. The vanilla ender chest is only cleared for
            // the items that really made it into the stored chest, and the same content is never
            // imported a second time.
            ItemStack[] vanilla = new ItemStack[27];
            vanilla[0] = new ItemStack(Material.EMERALD, 7);
            DatabaseManager.VanillaMergeResult merge = db.mergeVanillaContents(test, vanilla);
            boolean moved = merge.success() && merge.mergedCount() == 1;
            boolean cleared = true;
            for (ItemStack remaining : merge.remaining()) {
                if (remaining != null && !remaining.getType().isAir()) {
                    cleared = false;
                }
            }
            DatabaseManager.VanillaMergeResult again = db.mergeVanillaContents(test, vanilla);
            boolean noDupe = again.skipped() || again.mergedCount() == 0;
            ItemStack[] chest = db.loadEnderChest(test);
            int copies = 0;
            for (ItemStack item : chest) {
                if (item != null && item.getType() == Material.EMERALD) {
                    copies++;
                }
            }
            boolean single = copies == 1;
            boolean ok = moved && cleared && noDupe && single;
            sender.sendMessage(Component.text("Merge without duplication: " + (ok ? "ok" : "FAILED")
                    + " (moved=" + merge.mergedCount() + ", second merge skipped=" + again.skipped()
                    + ", copies=" + copies + ")")
                    .color(ok ? NamedTextColor.GREEN : NamedTextColor.RED));

            // The backups have to be restorable, they are the way to repair a chest that was
            // duplicated by an older release of the plugin.
            List<ChestStore.Backup> backups = db.listBackups(test);
            boolean backupOk = false;
            if (!backups.isEmpty()) {
                int restored = db.restoreBackup(test, backups.get(0).contents());
                int emeralds = 0;
                for (ItemStack item : db.loadEnderChest(test)) {
                    if (item != null && item.getType() == Material.EMERALD) {
                        emeralds++;
                    }
                }
                backupOk = restored >= 0 && emeralds == 0;
            }
            sender.sendMessage(Component.text("Backup restore: " + (backupOk ? "ok" : "FAILED"))
                    .color(backupOk ? NamedTextColor.GREEN : NamedTextColor.RED));

            // The chest has to be in the database file, not only in the cache.
            db.evictCache(test);
            ItemStack[] storedChest = db.loadEnderChest(test);
            boolean stored = storedChest.length > 0 && storedChest[0] != null
                    && storedChest[0].getAmount() == 5;
            sender.sendMessage(Component.text("Database round trip: " + (stored ? "ok" : "FAILED"))
                    .color(stored ? NamedTextColor.GREEN : NamedTextColor.RED));

            db.deleteChest(test);
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text("The self test failed: " + e)
                    .color(NamedTextColor.RED));
            plugin.getLogger().log(Level.SEVERE, "The self test failed", e);
        }
        return true;
    }

    /**
     * Resolves the player a command is about.
     *
     * @param sender     the sender
     * @param args       the command arguments
     * @param nameIndex  the index of the player name in the arguments
     * @return the player, {@code null} when the command failed
     */
    private Player resolveTarget(CommandSender sender, String[] args, int nameIndex) {
        if (!sender.hasPermission("enderchestdb.admin")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return null;
        }
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayerExact(args[nameIndex]);
            if (target == null) {
                sender.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("Only players can use this command.");
        return null;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/enderchest — open your ender chest", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest export [player] — move the ender chest back into "
                + "the vanilla ender chest", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest import [player] — move the vanilla ender chest "
                + "into the bigger ender chest", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest backups [player] — list the backups of the ender "
                + "chest (admin only)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest restore [player] [backup] — restore a backup of "
                + "the ender chest (admin only)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest selftest — check the item serialization, the "
                + "database and the merge (admin only)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/enderchest toggle — enable or disable the command "
                + "(admin only)", NamedTextColor.YELLOW));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("toggle", "export", "import", "backups", "restore",
                    "selftest", "help")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    completions.add(option);
                }
            }
        } else if (args.length == 2 && sender.hasPermission("enderchestdb.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    completions.add(player.getName());
                }
            }
        }
        return completions;
    }
}
