package com.echest.enderchest.fabric;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.List;
import java.util.UUID;

/**
 * The window of the bigger ender chest.
 *
 * <p>It is a normal chest window with six rows, so a vanilla client can use it without a mod.
 * The window saves the chest of the player when it is closed.
 */
public final class EnderChestMenu extends ChestMenu {

    private final ChestService service;
    private final UUID owner;
    private final SimpleContainer container;

    /**
     * Creates the window.
     *
     * @param type            the type of the menu, the generic chest of six rows
     * @param syncId          the id of the window
     * @param playerInventory the inventory of the player
     * @param container       the container with the 54 slots
     * @param rows            the number of rows
     * @param service         the service that stores the chest
     * @param owner           the player the chest belongs to
     */
    public EnderChestMenu(MenuType<ChestMenu> type, int syncId, Inventory playerInventory,
                          SimpleContainer container, int rows, ChestService service, UUID owner) {
        super(type, syncId, playerInventory, container, rows);
        this.service = service;
        this.owner = owner;
        this.container = container;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // The window is closed, the content of the chest is stored now.
        service.save(owner, container.getItems());
    }

    /**
     * The player the chest belongs to.
     *
     * @return the owner
     */
    public UUID owner() {
        return owner;
    }

    /**
     * The content of the chest.
     *
     * @return the 54 slots
     */
    public List<net.minecraft.world.item.ItemStack> getContainerItems() {
        return container.getItems();
    }
}
