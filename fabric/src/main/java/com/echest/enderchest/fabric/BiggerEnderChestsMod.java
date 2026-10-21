package com.echest.enderchest.fabric;

import com.echest.enderchest.migration.JoinAction;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bigger ender chests for Fabric.
 *
 * <p>The mod only runs on the server, the window of the chest is the normal chest window of
 * Minecraft with six rows, so a vanilla client can join the server.
 */
public class BiggerEnderChestsMod implements ModInitializer {

    /** The id of the mod. */
    public static final String MOD_ID = "biggerenderchests";

    private static final Logger LOGGER = LoggerFactory.getLogger("BiggerEnderChests");

    private static ChestService service;
    private static net.minecraft.server.MinecraftServer server;

    @Override
    public void onInitialize() {
        Path database = Path.of("config", MOD_ID, "enderchest.db");
        try {
            service = new ChestService(database, java.util.logging.Logger.getLogger(MOD_ID));
        } catch (SQLException e) {
            LOGGER.error("Could not open the ender chest database, the mod is disabled", e);
            throw new IllegalStateException("Could not open the ender chest database", e);
        }

        ServerLifecycleEvents.SERVER_STARTING.register(starting -> {
            server = starting;
            service.setServer(starting);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Save every open chest before the server stops, otherwise the items of a player
            // that is still in the window are lost.
            EnderChestCommands.saveAll(service);
            service.flush();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            service.setServer(server);
            migrateOnJoin(handler.player, server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            EnderChestCommands.saveAndClose(handler.player, service);
            service.evict(handler.player.getUUID());
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!world.getBlockState(hitResult.getBlockPos()).is(Blocks.ENDER_CHEST)) {
                return InteractionResult.PASS;
            }
            if (serverPlayer.isShiftKeyDown() && isPlaceableBlock(serverPlayer.getMainHandItem())) {
                // Vanilla lets the player place a block against the ender chest while sneaking.
                return InteractionResult.PASS;
            }
            openChest(serverPlayer);
            // The vanilla ender chest window must not open.
            return InteractionResult.FAIL;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EnderChestCommands.register(dispatcher));

        LOGGER.info("Bigger ender chests enabled.");
    }

    /**
     * The running server.
     *
     * @return the server, {@code null} when the server is not running
     */
    public static net.minecraft.server.MinecraftServer server() {
        return server;
    }

    /**
     * The service that stores the chests.
     *
     * @return the service
     */
    public static ChestService service() {
        return service;
    }

    /**
     * Opens the bigger ender chest of a player.
     *
     * @param player the player
     * @return {@code true} when the window was opened
     */
    public static boolean openChest(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ChestService.CachedChest chest;
        try {
            chest = service.load(uuid);
        } catch (SQLException e) {
            LOGGER.error("Could not read the ender chest of {}", uuid, e);
            player.displayClientMessage(Component.literal(
                            "Your ender chest could not be opened, please contact an admin.")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }

        SimpleContainer container = new SimpleContainer(ChestService.CHEST_SIZE);
        for (int slot = 0; slot < ChestService.CHEST_SIZE; slot++) {
            ItemStack stack = chest.items[slot];
            if (stack != null && !stack.isEmpty()) {
                container.setItem(slot, stack);
            } else if (chest.unreadable[slot]) {
                container.setItem(slot, unreadablePlaceholder());
            }
        }

        MenuConstructor factory = (syncId, inventory, playerEntity) ->
                new EnderChestMenu(MenuType.GENERIC_9x6, syncId, inventory, container, 6, service, uuid);
        player.openMenu(new SimpleMenuProvider(factory, Component.translatable("container.enderchest")));

        if (chest.unreadableCount() > 0) {
            player.displayClientMessage(Component.literal(chest.unreadableCount()
                            + " item(s) in your ender chest could not be read. They are kept as they are, "
                            + "please contact an admin.").withStyle(ChatFormatting.YELLOW), false);
        }
        return true;
    }

    /**
     * Moves the vanilla ender chest of a player into the database when they join.
     *
     * @param player the player
     * @param server the server
     */
    private static void migrateOnJoin(ServerPlayer player, net.minecraft.server.MinecraftServer server) {
        UUID uuid = player.getUUID();
        try {
            List<ItemStack> vanilla = new ArrayList<>();
            boolean hasItems = false;
            for (int slot = 0; slot < player.getEnderChestInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getEnderChestInventory().getItem(slot);
                vanilla.add(stack);
                if (stack != null && !stack.isEmpty()) {
                    hasItems = true;
                }
            }

            JoinAction action = JoinAction.decide(service.isExported(uuid), service.hasChest(uuid), hasItems);
            switch (action) {
                case SKIP_EXPORTED -> LOGGER.info("The ender chest of {} was exported to the vanilla "
                        + "ender chest, it is not imported again.", uuid);
                case IMPORT -> {
                    ChestService.MergeResult result = service.mergeVanillaContents(uuid, vanilla);
                    if (!result.success()) {
                        if (result.skipped()) {
                            LOGGER.info("The vanilla ender chest of {} was already imported, "
                                    + "it is not imported again.", uuid);
                        } else {
                            player.displayClientMessage(Component.literal("Your ender chest could not be "
                                            + "moved into the bigger ender chest. Please contact an admin.")
                                    .withStyle(ChatFormatting.RED), false);
                        }
                        return;
                    }
                    writeVanillaChest(player, result.remaining());
                    player.displayClientMessage(Component.literal("Your vanilla ender chest was moved into the "
                            + "bigger ender chest.").withStyle(ChatFormatting.GREEN), false);
                    LOGGER.info("Imported the vanilla ender chest of {}.", uuid);
                }
                case MERGE -> {
                    ChestService.MergeResult result = service.mergeVanillaContents(uuid, vanilla);
                    if (!result.success()) {
                        if (result.skipped()) {
                            LOGGER.info("The vanilla ender chest of {} was already imported, "
                                    + "it is not imported again.", uuid);
                        } else {
                            player.displayClientMessage(Component.literal("The items in your vanilla ender "
                                            + "chest could not be moved, they were left where they are.")
                                    .withStyle(ChatFormatting.RED), false);
                        }
                        return;
                    }
                    writeVanillaChest(player, result.remaining());
                    if (result.mergedCount() > 0) {
                        player.displayClientMessage(Component.literal(result.mergedCount()
                                        + " item(s) from your vanilla ender chest were moved into the "
                                        + "bigger ender chest.").withStyle(ChatFormatting.GREEN), false);
                    }
                    if (result.failedCount() > 0) {
                        player.displayClientMessage(Component.literal(result.failedCount()
                                        + " item(s) in your vanilla ender chest could not be moved, "
                                        + "they stayed there.").withStyle(ChatFormatting.YELLOW), false);
                    }
                    LOGGER.info("Merged the vanilla ender chest of {}: {} item(s) moved, {} kept.",
                            uuid, result.mergedCount(), result.failedCount());
                }
                case NOTHING -> {
                    // Nothing to do.
                }
                default -> {
                    // Nothing to do.
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Could not migrate the ender chest of {}", uuid, e);
        }
    }

    private static void writeVanillaChest(ServerPlayer player, List<ItemStack> contents) {
        var vanilla = player.getEnderChestInventory();
        for (int slot = 0; slot < vanilla.getContainerSize(); slot++) {
            ItemStack stack = slot < contents.size() ? contents.get(slot) : null;
            vanilla.setItem(slot, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
    }

    private static boolean isPlaceableBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem;
    }

    private static ItemStack unreadablePlaceholder() {
        ItemStack placeholder = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        placeholder.set(DataComponents.CUSTOM_NAME,
                Component.literal("Unreadable item").withStyle(ChatFormatting.RED));
        return placeholder;
    }
}
