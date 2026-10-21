package com.echest.enderchest.fabric;

import com.echest.enderchest.storage.ChestStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The commands of the mod.
 *
 * <p>The commands are registered with Brigadier. The permissions of the Paper version are mapped
 * to the permission levels of the server, a permission node of another permission plugin is not
 * used here:
 *
 * <ul>
 *   <li>{@code enderchestdb.command} is the operator level 1</li>
 *   <li>{@code enderchestdb.admin} is the operator level 2</li>
 * </ul>
 */
public final class EnderChestCommands {

    /** Permission level of the {@code enderchestdb.command} permission. */
    public static final PermissionLevel COMMAND_LEVEL = PermissionLevel.MODERATORS;

    /** Permission level of the {@code enderchestdb.admin} permission. */
    public static final PermissionLevel ADMIN_LEVEL = PermissionLevel.GAMEMASTERS;

    private EnderChestCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param dispatcher the command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Brigadier has no alias support, the tree is registered once per name.
        dispatcher.register(build("enderchest"));
        dispatcher.register(build("echest"));
        dispatcher.register(build("ec"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requires(source -> hasPermission(source, COMMAND_LEVEL))
                .executes(context -> open(context.getSource()))
                .then(Commands.literal("export")
                        .requires(source -> hasPermission(source, ADMIN_LEVEL))
                        .executes(context -> export(context.getSource(), null))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(context -> export(context.getSource(),
                                        context.getArgument("player", String.class)))))
                .then(Commands.literal("import")
                        .requires(source -> hasPermission(source, ADMIN_LEVEL))
                        .executes(context -> importChest(context.getSource(), null))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(context -> importChest(context.getSource(),
                                        context.getArgument("player", String.class)))))
                .then(Commands.literal("help")
                        .executes(EnderChestCommands::help))
                .then(Commands.literal("selftest")
                        .requires(source -> hasPermission(source, ADMIN_LEVEL))
                        .executes(context -> selftest(context.getSource())))
                .then(Commands.literal("backups")
                        .requires(source -> hasPermission(source, ADMIN_LEVEL))
                        .executes(context -> listBackups(context.getSource(), null))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(context -> listBackups(context.getSource(),
                                        context.getArgument("player", String.class)))))
                .then(Commands.literal("restore")
                        .requires(source -> hasPermission(source, ADMIN_LEVEL))
                        .executes(context -> restoreBackup(context.getSource(), null, 0))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(context -> restoreBackup(context.getSource(),
                                        context.getArgument("player", String.class), 0))
                                .then(Commands.argument("backup", IntegerArgumentType.integer(0))
                                        .executes(context -> restoreBackup(context.getSource(),
                                                context.getArgument("player", String.class),
                                                context.getArgument("backup", Integer.class))))));
    }

    /**
     * Checks the item serialization and the database on the running server.
     *
     * <p>The command writes a test row into the database and removes it again, it is meant for
     * admins that want to know whether the storage works.
     *
     * @param source the command source
     * @return always {@code true}
     */
    private static int selftest(CommandSourceStack source) {
        ChestService service = BiggerEnderChestsMod.service();
        MinecraftItemCodec codec = service.codec();

        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("Selftest").withStyle(ChatFormatting.GOLD));

        try {
            byte[] payload = codec.serialize(stack);
            ItemStack back = codec.deserialize(payload);
            boolean same = back.getCount() == stack.getCount()
                    && back.getItem() == stack.getItem()
                    && back.get(DataComponents.CUSTOM_NAME) != null;
            source.sendSuccess(() -> Component.literal("Item round trip: "
                    + (same ? "ok" : "FAILED") + " (" + payload.length + " bytes)")
                    .withStyle(same ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        } catch (ItemCodecException e) {
            source.sendSuccess(() -> Component.literal("Item round trip: FAILED (" + e.getMessage() + ")")
                    .withStyle(ChatFormatting.RED), false);
        }

        // A random id, every run of the self test has to start with an empty chest.
        UUID test = UUID.randomUUID();
        try {
            service.save(test, List.of(stack));

            // The merge must not duplicate the items. The vanilla ender chest is only cleared
            // for the items that really made it into the stored chest, and the same content is
            // never imported a second time.
            List<ItemStack> vanilla = new ArrayList<>();
            vanilla.add(new ItemStack(net.minecraft.world.item.Items.EMERALD, 7));
            while (vanilla.size() < 27) {
                vanilla.add(ItemStack.EMPTY);
            }
            ChestService.MergeResult merge = service.mergeVanillaContents(test, vanilla);
            boolean moved = merge.success() && merge.mergedCount() == 1;
            boolean cleared = true;
            for (ItemStack remaining : merge.remaining()) {
                if (remaining != null && !remaining.isEmpty()) {
                    cleared = false;
                }
            }
            ChestService.MergeResult again = service.mergeVanillaContents(test, vanilla);
            boolean noDupe = again.skipped() || again.mergedCount() == 0;
            ChestService.CachedChest merged = service.load(test);
            int copies = 0;
            for (ItemStack item : merged.items) {
                if (item != null && !item.isEmpty()
                        && item.getItem() == net.minecraft.world.item.Items.EMERALD) {
                    copies++;
                }
            }
            boolean single = copies == 1;
            boolean mergeOk = moved && cleared && noDupe && single;
            final int movedCount = merge.mergedCount();
            final boolean secondSkipped = again.skipped();
            final int emeraldCopies = copies;
            source.sendSuccess(() -> Component.literal("Merge without duplication: "
                    + (mergeOk ? "ok" : "FAILED") + " (moved=" + movedCount
                    + ", second merge skipped=" + secondSkipped + ", copies=" + emeraldCopies + ")")
                    .withStyle(mergeOk ? ChatFormatting.GREEN : ChatFormatting.RED), false);

            service.awaitWrites(10);
            // Drop the cache, the chest is read from the database file again.
            service.evict(test);
            ChestService.CachedChest chest = service.load(test);
            boolean stored = chest.items[0] != null && chest.items[0].getCount() == 5;
            source.sendSuccess(() -> Component.literal("Database round trip: "
                    + (stored ? "ok" : "FAILED"))
                    .withStyle(stored ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            service.deleteChest(test);
        } catch (java.sql.SQLException e) {
            source.sendSuccess(() -> Component.literal("Database round trip: FAILED (" + e.getMessage() + ")")
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    private static int open(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use this command.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return BiggerEnderChestsMod.openChest(player) ? 1 : 0;
    }

    private static int export(CommandSourceStack source, String playerName) {
        ServerPlayer player = resolve(source, playerName);
        if (player == null) {
            return 0;
        }
        ChestService service = BiggerEnderChestsMod.service();
        UUID uuid = player.getUUID();
        try {
            if (service.isExported(uuid)) {
                source.sendFailure(Component.literal("The ender chest of " + name(player)
                        + " is already in the vanilla ender chest.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (!service.hasChest(uuid)) {
                source.sendFailure(Component.literal(name(player)
                        + " does not have a stored ender chest.").withStyle(ChatFormatting.RED));
                return 0;
            }
            List<ItemStack> vanilla = service.exportToVanilla(uuid);
            if (vanilla == null) {
                source.sendFailure(Component.literal("The ender chest of " + name(player)
                        + " could not be exported. It has more items than the vanilla ender chest can "
                        + "hold, or it contains items that cannot be read.")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
            var container = player.getEnderChestInventory();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = slot < vanilla.size() ? vanilla.get(slot) : null;
                container.setItem(slot, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            player.displayClientMessage(Component.literal(
                    "Your ender chest was moved back into your vanilla ender chest.")
                    .withStyle(ChatFormatting.GREEN), false);
            source.sendSuccess(() -> Component.literal("The ender chest of " + name(player)
                    + " was moved into the vanilla ender chest.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (SQLException e) {
            source.sendFailure(Component.literal(
                    "The ender chest could not be exported, please contact an admin.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int importChest(CommandSourceStack source, String playerName) {
        ServerPlayer player = resolve(source, playerName);
        if (player == null) {
            return 0;
        }
        ChestService service = BiggerEnderChestsMod.service();
        UUID uuid = player.getUUID();
        try {
            var container = player.getEnderChestInventory();
            List<ItemStack> vanilla = new ArrayList<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                vanilla.add(container.getItem(slot));
            }
            ChestService.MergeResult result = service.importFromVanilla(uuid, vanilla);
            if (!result.success()) {
                source.sendFailure(Component.literal(
                        "The vanilla ender chest could not be imported, it was left untouched.")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = slot < result.remaining().size() ? result.remaining().get(slot) : null;
                container.setItem(slot, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            player.displayClientMessage(Component.literal(result.mergedCount()
                    + " item(s) of your vanilla ender chest were moved into the bigger ender chest.")
                    .withStyle(ChatFormatting.GREEN), false);
            source.sendSuccess(() -> Component.literal("Imported the vanilla ender chest of "
                    + name(player) + ".").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (SQLException e) {
            source.sendFailure(Component.literal(
                    "The vanilla ender chest could not be imported, please contact an admin.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Lists the backed up versions of the ender chest of a player.
     *
     * <p>The backups are the versions of the row before it was overwritten. They are used to undo
     * a bad write, for example a chest that was duplicated by an older release of the mod.
     *
     * @param source     the command source
     * @param playerName the player, {@code null} for the sender
     * @return the number of backups
     */
    private static int listBackups(CommandSourceStack source, String playerName) {
        ServerPlayer player = resolve(source, playerName);
        if (player == null) {
            return 0;
        }
        ChestService service = BiggerEnderChestsMod.service();
        try {
            List<ChestStore.Backup> backups = service.listBackups(player.getUUID());
            if (backups.isEmpty()) {
                source.sendSuccess(() -> Component.literal("There is no backup of the ender chest of "
                        + name(player) + ".").withStyle(ChatFormatting.YELLOW), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Backups of the ender chest of " + name(player)
                    + ", newest first:").withStyle(ChatFormatting.GOLD), false);
            for (int index = 0; index < backups.size(); index++) {
                final int number = index;
                ChestStore.Backup backup = backups.get(index);
                source.sendSuccess(() -> Component.literal("#" + number + " - "
                        + new Date(backup.createdAt()) + " - " + backup.reason()
                        + " - /enderchest restore " + name(player) + " " + number)
                        .withStyle(ChatFormatting.GRAY), false);
            }
            return backups.size();
        } catch (SQLException e) {
            source.sendFailure(Component.literal("The backups of the ender chest of " + name(player)
                    + " could not be read.").withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Restores a backed up version of the ender chest of a player.
     *
     * <p>The row that is replaced is backed up again, so the restore can be undone.
     *
     * @param source     the command source
     * @param playerName the player, {@code null} for the sender
     * @param index      the backup, {@code 0} is the newest one
     * @return always {@code 1} on success
     */
    private static int restoreBackup(CommandSourceStack source, String playerName, int index) {
        ServerPlayer player = resolve(source, playerName);
        if (player == null) {
            return 0;
        }
        ChestService service = BiggerEnderChestsMod.service();
        UUID uuid = player.getUUID();
        try {
            List<ChestStore.Backup> backups = service.listBackups(uuid);
            if (index < 0 || index >= backups.size()) {
                source.sendFailure(Component.literal("There is no backup #" + index + " of "
                        + name(player) + ". Use /enderchest backups " + name(player)
                        + " to see the list.").withStyle(ChatFormatting.RED));
                return 0;
            }
            ChestStore.Backup backup = backups.get(index);

            // The window saves itself when it is closed, so it has to be closed before the
            // backup is written, otherwise the window would write its content over it.
            if (player.containerMenu instanceof EnderChestMenu) {
                player.closeContainer();
            }

            int slots = service.restoreBackup(uuid, backup.contents());
            if (slots < 0) {
                source.sendFailure(Component.literal("The ender chest of " + name(player)
                        + " could not be restored.").withStyle(ChatFormatting.RED));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Restored backup #" + index + " of the ender chest of "
                    + name(player) + ", " + slots + " slot(s) with items.").withStyle(ChatFormatting.GREEN), true);
            player.displayClientMessage(Component.literal("Your ender chest was restored by an admin.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (SQLException e) {
            source.sendFailure(Component.literal("The ender chest of " + name(player)
                    + " could not be restored.").withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "/enderchest — open your ender chest"), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/enderchest export [player] — move the ender chest back into the vanilla ender chest"),
                false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/enderchest import [player] — move the vanilla ender chest into the bigger ender chest"),
                false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/enderchest backups [player] — list the backups of the ender chest"), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/enderchest restore [player] [backup] — restore a backup of the ender chest"), false);
        return 1;
    }

    private static ServerPlayer resolve(CommandSourceStack source, String playerName) {
        if (playerName != null) {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("That player is not online.")
                        .withStyle(ChatFormatting.RED));
                return null;
            }
            return target;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use this command.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return player;
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    private static boolean hasPermission(CommandSourceStack source, PermissionLevel level) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(level));
    }

    /**
     * Saves the chest of a player when they leave the server.
     *
     * @param player  the player
     * @param service the service
     */
    public static void saveAndClose(ServerPlayer player, ChestService service) {
        // The window saves itself when it is closed, this is only the fallback for the case that
        // the window was not closed properly.
        try {
            if (player.containerMenu instanceof EnderChestMenu menu) {
                service.save(menu.owner(), menu.getContainerItems());
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("BiggerEnderChests")
                    .error("Could not save the ender chest of {}", player.getUUID(), e);
        }
    }

    /**
     * Saves the chests of all players that are online.
     *
     * @param service the service
     */
    public static void saveAll(ChestService service) {
        net.minecraft.server.MinecraftServer server = BiggerEnderChestsMod.server();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            saveAndClose(player, service);
        }
    }
}
