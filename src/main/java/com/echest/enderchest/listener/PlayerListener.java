package com.echest.enderchest.listener;

import com.echest.enderchest.EnderChestPlugin;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import com.echest.enderchest.gui.EnderChestHolder;
import com.echest.enderchest.migration.JoinAction;
import com.echest.enderchest.util.AnimationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerListener implements Listener {

    private final EnderChestPlugin plugin;
    private final DatabaseManager db;
    private final Map<UUID, Location> lastInteractedChest = new ConcurrentHashMap<>();

    /** Players whose chest changed since the last save. */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private boolean flushScheduled;

    public PlayerListener(EnderChestPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    /**
     * Moves the vanilla ender chest of a player into the database when they join.
     *
     * <p>The content of the vanilla ender chest is never thrown away. When the player already has
     * a chest in the database, the items are merged into the free slots of that chest, everything
     * that does not fit stays in the vanilla ender chest.
     *
     * <p>The content that was imported is remembered in the database. The same content is never
     * imported a second time, so a server that was killed before the player file was written
     * cannot duplicate the items of a player.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        ItemStack[] vanilla = player.getEnderChest().getContents();
        boolean hasItems = containsItem(vanilla);

        JoinAction action = JoinAction.decide(db.isExported(uuid), db.hasEnderChest(uuid), hasItems);

        switch (action) {
            case SKIP_EXPORTED -> {
                plugin.getLogger().info("The ender chest of " + player.getName()
                        + " was exported to the vanilla ender chest, it is not imported again.");
            }
            case IMPORT -> importVanillaChest(player, vanilla);
            case MERGE -> mergeVanillaChest(player, vanilla);
            case NOTHING -> {
                // Nothing to do.
            }
            default -> {
                // Nothing to do.
            }
        }
    }

    /**
     * Moves the content of the vanilla ender chest into the stored chest of a player.
     *
     * <p>The merge never overwrites the stored chest, the items are only added. That way a failed
     * database read cannot destroy the chest of a player.
     *
     * @param player the player
     * @param vanilla the content of the vanilla ender chest
     */
    private void importVanillaChest(Player player, ItemStack[] vanilla) {
        UUID uuid = player.getUniqueId();
        DatabaseManager.VanillaMergeResult result = db.mergeVanillaContents(uuid, vanilla);

        if (!result.success()) {
            if (result.skipped()) {
                plugin.getLogger().info("The vanilla ender chest of " + player.getName()
                        + " was already imported, it is not imported again.");
                return;
            }
            plugin.getLogger().severe("Could not import the ender chest of " + player.getName()
                    + ", the vanilla ender chest was left untouched.");
            player.sendMessage(Component.text("Your ender chest could not be moved into the bigger "
                    + "ender chest. Please contact an admin.").color(NamedTextColor.RED));
            return;
        }

        // Only the items that really made it into the stored chest are removed from the
        // vanilla ender chest, everything else stays there.
        player.getEnderChest().setContents(result.remaining());
        player.sendMessage(Component.text("Your vanilla ender chest was moved into the bigger "
                + "ender chest.").color(NamedTextColor.GREEN));
        plugin.getLogger().info("Imported the vanilla ender chest of " + player.getName() + ".");
    }

    private void mergeVanillaChest(Player player, ItemStack[] vanilla) {
        UUID uuid = player.getUniqueId();
        DatabaseManager.VanillaMergeResult result = db.mergeVanillaContents(uuid, vanilla);

        if (!result.success()) {
            if (result.skipped()) {
                plugin.getLogger().info("The vanilla ender chest of " + player.getName()
                        + " was already imported, it is not imported again.");
                return;
            }
            plugin.getLogger().severe("Could not merge the vanilla ender chest of " + player.getName()
                    + ", the vanilla ender chest was left untouched.");
            player.sendMessage(Component.text("The items in your vanilla ender chest could not be "
                    + "moved, they were left where they are. Please contact an admin.")
                    .color(NamedTextColor.RED));
            return;
        }

        // Only the items that really made it into the stored chest are removed from the
        // vanilla ender chest, everything else stays there.
        player.getEnderChest().setContents(result.remaining());

        if (result.mergedCount() > 0) {
            player.sendMessage(Component.text(result.mergedCount() + " item(s) from your vanilla "
                    + "ender chest were moved into the bigger ender chest.")
                    .color(NamedTextColor.GREEN));
        }
        if (result.failedCount() > 0) {
            player.sendMessage(Component.text(result.failedCount() + " item(s) in your vanilla "
                    + "ender chest could not be moved, they stayed there. Please contact an admin.")
                    .color(NamedTextColor.YELLOW));
        }
        plugin.getLogger().info("Merged the vanilla ender chest of " + player.getName() + ": "
                + result.mergedCount() + " item(s) moved, " + result.failedCount() + " kept.");
    }

    private boolean containsItem(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the bigger ender chest when a player right clicks an ender chest block.
     *
     * <p>The handler runs at the end of the event chain and ignores cancelled events, so a
     * protection plugin can still deny the interaction with the block.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (EnderChestGUI.isOpen(uuid)) return;

        if (isPlacingABlockAgainstTheChest(player)) {
            // Vanilla lets the player place a block against the ender chest while sneaking,
            // that has to keep working.
            return;
        }

        if (event.useInteractedBlock() == Event.Result.DENY) {
            // A protection plugin denied the interaction with the block, the chest stays closed.
            return;
        }

        event.setCancelled(true);

        Location loc = event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);
        Location blockLoc = event.getClickedBlock().getLocation();

        player.playSound(loc, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.BLOCKS, 1.0f, 1.0f);
        player.spawnParticle(Particle.PORTAL, loc, 40, 0.5, 0.5, 0.5, 0.1);

        lastInteractedChest.put(uuid, blockLoc);
        AnimationUtil.playOpenAnimation(player, blockLoc);

        EnderChestGUI.open(player, db);
    }

    /**
     * Replaces the vanilla ender chest window with the bigger one.
     *
     * <p>Only the ender chest of the player that opens it is replaced. When an admin tool shows
     * the ender chest of another player, that window belongs to somebody else and must not be
     * hijacked.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.isCancelled()) {
            // Another plugin does not want this window to be opened.
            return;
        }
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) {
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Player owner)
                || !owner.getUniqueId().equals(event.getPlayer().getUniqueId())) {
            // The window belongs to another player, an admin tool is showing their ender chest.
            return;
        }

        event.setCancelled(true);
        if (event.getPlayer() instanceof Player player) {
            if (EnderChestGUI.isOpen(player.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> EnderChestGUI.open(player, db));
        }
    }

    /**
     * Whether the player is sneaking with a block in their hand, so that vanilla places the
     * block against the ender chest instead of opening it.
     *
     * @param player the player
     * @return {@code true} when a block is placed
     */
    private boolean isPlacingABlockAgainstTheChest(Player player) {
        if (!player.isSneaking()) {
            return false;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Material type = item.getType();
        return type.isBlock() && type.isItem();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!EnderChestGUI.isOurGUI(event.getInventory())) return;

        EnderChestGUI.saveAndClose(player, db);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, SoundCategory.BLOCKS, 1.0f, 1.0f);

        Location blockLoc = lastInteractedChest.remove(player.getUniqueId());
        if (blockLoc != null) {
            AnimationUtil.playCloseAnimation(player, blockLoc);
        }
    }

    /**
     * Saves the chest of a player shortly after a change. The save is done on the next tick so
     * that a burst of clicks only writes once.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!EnderChestGUI.isOurGUI(event.getInventory())) return;

        if (event.getInventory().getHolder() instanceof EnderChestHolder holder
                && holder.isLocked(event.getRawSlot())) {
            // The slot holds data that could not be read, the player must not move it.
            event.setCancelled(true);
            return;
        }

        if (!plugin.getConfig().getBoolean("save-on-interaction", true)) return;

        markDirty(event.getView().getPlayer().getUniqueId());
    }

    /**
     * Saves the chest of a player shortly after a drag.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!EnderChestGUI.isOurGUI(event.getInventory())) return;
        if (!plugin.getConfig().getBoolean("save-on-interaction", true)) return;

        markDirty(event.getView().getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (EnderChestGUI.isOpen(uuid)) {
            // The close event is not fired reliably while the player disconnects.
            EnderChestGUI.saveAndClose(event.getPlayer(), db);
        }
        EnderChestGUI.remove(uuid);
        db.evictCache(uuid);
        lastInteractedChest.remove(uuid);
    }

    private void markDirty(UUID uuid) {
        dirty.add(uuid);
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        plugin.getServer().getScheduler().runTask(plugin, this::flushDirty);
    }

    private void flushDirty() {
        flushScheduled = false;
        for (UUID uuid : dirty) {
            EnderChestGUI.save(uuid, db);
        }
        dirty.clear();
    }
}
