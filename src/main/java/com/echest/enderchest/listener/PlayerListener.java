package com.echest.enderchest.listener;

import com.echest.enderchest.EnderChestPlugin;
import com.echest.enderchest.database.DatabaseManager;
import com.echest.enderchest.gui.EnderChestGUI;
import com.echest.enderchest.util.AnimationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerListener implements Listener {

    private final EnderChestPlugin plugin;
    private final DatabaseManager db;
    private final Map<UUID, Location> lastInteractedChest = new ConcurrentHashMap<>();

    public PlayerListener(EnderChestPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!db.hasEnderChest(uuid)) {
            ItemStack[] contents = player.getEnderChest().getContents();
            ItemStack[] storage = new ItemStack[54];
            System.arraycopy(contents, 0, storage, 0, Math.min(contents.length, 27));
            db.saveEnderChest(uuid, storage);
            player.getEnderChest().clear();
        } else {
            ItemStack[] vanillaContents = player.getEnderChest().getContents();
            boolean hasItems = false;
            for (ItemStack item : vanillaContents) {
                if (item != null && !item.getType().isAir()) {
                    hasItems = true;
                    break;
                }
            }
            if (hasItems) {
                plugin.getLogger().warning("Player " + player.getName()
                        + " had items in their vanilla ender chest that were NOT migrated (already in DB). These items will be cleared.");
            }
            player.getEnderChest().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (EnderChestGUI.isOpen(uuid)) return;

        Location loc = event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);
        Location blockLoc = event.getClickedBlock().getLocation();

        player.playSound(loc, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.BLOCKS, 1.0f, 1.0f);
        player.spawnParticle(Particle.PORTAL, loc, 40, 0.5, 0.5, 0.5, 0.1);

        lastInteractedChest.put(uuid, blockLoc);
        AnimationUtil.playOpenAnimation(player, blockLoc);

        EnderChestGUI.open(player, db);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST) {
            event.setCancelled(true);
            if (event.getPlayer() instanceof Player player) {
                if (EnderChestGUI.isOpen(player.getUniqueId())) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> EnderChestGUI.open(player, db));
            }
        }
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

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        EnderChestGUI.remove(uuid);
        db.evictCache(uuid);
        lastInteractedChest.remove(uuid);
    }
}
