package com.echest.enderchest.migration;

import com.echest.enderchest.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class MigrationManager {

    private final JavaPlugin plugin;
    private final DatabaseManager db;

    private Class<?> nbtIoClass;
    private Class<?> compoundTagClass;
    private Class<?> listTagClass;
    private Class<?> nmsItemStackClass;
    private Class<?> craftItemStackClass;
    private Object registryAccess;

    private String craftItemStackClassName = null;

    public MigrationManager(JavaPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        initReflection();
    }

    @SuppressWarnings("unchecked")
    private void initReflection() {
        try {
            nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
            compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            listTagClass = Class.forName("net.minecraft.nbt.ListTag");
            nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");

            craftItemStackClass = resolveCraftItemStack();
            if (craftItemStackClass != null) {
                craftItemStackClassName = craftItemStackClass.getName();
                plugin.getLogger().info("MigrationManager: resolved CraftItemStack via: " + craftItemStackClassName);
            } else {
                plugin.getLogger().warning("CraftItemStack not found - offline player migration will use fallback");
            }

            registryAccess = resolveRegistryAccess();
            if (registryAccess != null) {
                plugin.getLogger().info("MigrationManager: resolved RegistryAccess successfully");
            } else {
                plugin.getLogger().warning("Could not cache RegistryAccess, offline migration may use fallback");
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("NBT reflection classes not available - offline migration limited: " + e.getMessage());
        }
    }

    private Class<?> resolveCraftItemStack() {
        try {
            return Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> craftServerClass = Class.forName("org.bukkit.craftbukkit.CraftServer");
            String packageName = craftServerClass.getPackage().getName();
            String fullName = packageName + ".inventory.CraftItemStack";
            return Class.forName(fullName);
        } catch (Exception ignored) {
        }

        try {
            for (Package pkg : Package.getPackages()) {
                if (pkg.getName().startsWith("org.bukkit.craftbukkit") && pkg.getName().endsWith("inventory")) {
                    try {
                        return Class.forName(pkg.getName() + ".CraftItemStack");
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private Object resolveRegistryAccess() {
        try {
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");

            Class<?> craftServerClass = null;
            try {
                craftServerClass = Class.forName("org.bukkit.craftbukkit.CraftServer");
            } catch (ClassNotFoundException ignored) {
            }
            if (craftServerClass == null) {
                try {
                    for (Package pkg : Package.getPackages()) {
                        if (pkg.getName().startsWith("org.bukkit.craftbukkit")) {
                            try {
                                craftServerClass = Class.forName(pkg.getName() + ".CraftServer");
                                break;
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (craftServerClass == null) return null;

            Method getServer = craftServerClass.getMethod("getServer");
            Object server = getServer.invoke(null);
            Method registryAccessMethod = minecraftServerClass.getMethod("registryAccess");
            return registryAccessMethod.invoke(server);
        } catch (Exception e) {
            return null;
        }
    }

    public void runMigration() {
        final AtomicBoolean migrationComplete = new AtomicBoolean(false);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getLogger().info("Starting ender chest migration...");
                Set<UUID> migrated = db.getAllUUIDs();
                int totalMigrated = 0;
                int onlineMigrated = 0;
                int offlineMigrated = 0;
                int offlineFailures = 0;
                int offlineSkipped = 0;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    if (migrated.contains(uuid)) continue;
                    ItemStack[] contents = player.getEnderChest().getContents();
                    ItemStack[] storage = new ItemStack[54];
                    System.arraycopy(contents, 0, storage, 0, Math.min(contents.length, 27));
                    db.saveEnderChest(uuid, storage);
                    player.getEnderChest().clear();
                    onlineMigrated++;
                    plugin.getLogger().info("Migrated online player: " + player.getName());
                }

                for (World world : Bukkit.getWorlds()) {
                    File playerDataDir = new File(world.getWorldFolder(), "playerdata");
                    if (!playerDataDir.exists() || !playerDataDir.isDirectory()) continue;

                    File[] datFiles = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
                    if (datFiles == null) continue;

                    for (int fileIdx = 0; fileIdx < datFiles.length; fileIdx++) {
                        File datFile = datFiles[fileIdx];
                        String name = datFile.getName().replace(".dat", "");
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(name);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }

                        if (migrated.contains(uuid)) continue;
                        if (Bukkit.getPlayer(uuid) != null && Bukkit.getPlayer(uuid).isOnline()) continue;

                        ItemStack[] items = readOfflineEnderChest(datFile);
                        if (items != null) {
                            ItemStack[] storage = new ItemStack[54];
                            System.arraycopy(items, 0, storage, 0, Math.min(items.length, 27));
                            db.saveEnderChest(uuid, storage);
                            clearOfflineEnderChest(datFile);
                            offlineMigrated++;
                            plugin.getLogger().info("Migrated offline player: " + uuid);
                        } else {
                            offlineFailures++;
                            plugin.getLogger().warning("Skipped migration for offline player " + uuid + " - keeping original .dat file intact");
                        }

                        int totalProcessed = totalMigrated + onlineMigrated + offlineMigrated + offlineFailures + offlineSkipped;
                        if (totalProcessed > 0 && totalProcessed % 100 == 0) {
                            plugin.getLogger().info("Migration progress: processed " + totalProcessed + " player files...");
                        }
                    }
                }

                totalMigrated = onlineMigrated + offlineMigrated;
                plugin.getLogger().info("Migration summary: " + totalMigrated + " total migrated ("
                        + onlineMigrated + " online, " + offlineMigrated + " offline)"
                        + (offlineFailures > 0 ? ", " + offlineFailures + " failed/skipped" : ""));
                plugin.getLogger().info("Migration complete.");
            } catch (Exception e) {
                plugin.getLogger().severe("Migration failed: " + e.getMessage());
                e.printStackTrace();
            }
            migrationComplete.set(true);
        });

        try {
            while (!migrationComplete.get()) {
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Migration wait interrupted");
        }
    }

    private ItemStack[] readOfflineEnderChest(File datFile) {
        if (nbtIoClass == null || craftItemStackClass == null) return null;

        try {
            Object compound;
            try (DataInputStream dis = new DataInputStream(new GZIPInputStream(new FileInputStream(datFile)))) {
                Method readMethod = findReadMethod();
                if (readMethod == null) {
                    plugin.getLogger().warning("Could not find NbtIo.read method");
                    return null;
                }
                if (readMethod.getParameterCount() == 1) {
                    compound = readMethod.invoke(null, dis);
                } else {
                    Object accounter = getNbtAccounter();
                    compound = readMethod.invoke(null, dis, accounter);
                }
            }

            Method getListMethod = compoundTagClass.getMethod("getList", String.class, int.class);
            Object enderItems = getListMethod.invoke(compound, "EnderItems", 10);

            Method sizeMethod = listTagClass.getMethod("size");
            int size = (int) sizeMethod.invoke(enderItems);

            ItemStack[] items = new ItemStack[Math.min(size, 27)];
            Method getCompoundMethod = listTagClass.getMethod("getCompound", int.class);

            Method nmsFromTagMethod = findItemStackFromTagMethod();
            if (nmsFromTagMethod == null) {
                plugin.getLogger().warning("Could not find ItemStack.of/parse/parseOptional method");
                return items;
            }

            Method asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);

            int failedItems = 0;
            for (int i = 0; i < items.length; i++) {
                try {
                    Object itemCompound = getCompoundMethod.invoke(enderItems, i);
                    Object nmsItem;
                    if (nmsFromTagMethod.getParameterCount() == 1) {
                        nmsItem = nmsFromTagMethod.invoke(null, itemCompound);
                    } else {
                        Object result = nmsFromTagMethod.invoke(null, registryAccess, itemCompound);
                        if (result != null && result.getClass().getName().equals("java.util.Optional")) {
                            Method orElse = result.getClass().getMethod("orElse", Object.class);
                            nmsItem = orElse.invoke(result, (Object) null);
                        } else {
                            nmsItem = result;
                        }
                    }
                    if (nmsItem != null) {
                        items[i] = (org.bukkit.inventory.ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
                    } else {
                        failedItems++;
                        plugin.getLogger().warning("Migration: slot " + i + " in " + datFile.getName()
                                + " failed to convert (null NMS ItemStack)");
                    }
                } catch (Exception e) {
                    failedItems++;
                    plugin.getLogger().warning("Migration: slot " + i + " in " + datFile.getName()
                            + " failed to convert: " + e.getMessage());
                }
            }

            if (failedItems > 0) {
                plugin.getLogger().warning("Migration: " + datFile.getName() + " had "
                        + failedItems + " failed item(s) — preserving original .dat file");
                return null;
            }

            return items;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read offline ender chest for " + datFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private void clearOfflineEnderChest(File datFile) {
        if (nbtIoClass == null) return;

        try {
            Object compound;
            try (DataInputStream dis = new DataInputStream(new GZIPInputStream(new FileInputStream(datFile)))) {
                Method readMethod = findReadMethod();
                if (readMethod == null) return;
                if (readMethod.getParameterCount() == 1) {
                    compound = readMethod.invoke(null, dis);
                } else {
                    Object accounter = getNbtAccounter();
                    compound = readMethod.invoke(null, dis, accounter);
                }
            }

            Method putMethod = compoundTagClass.getMethod("put", String.class, Object.class);
            Method createListMethod = listTagClass.getMethod("create");
            Object emptyList = createListMethod.invoke(null);
            putMethod.invoke(compound, "EnderItems", emptyList);

            Method writeMethod = nbtIoClass.getMethod("write", compoundTagClass, DataOutputStream.class);
            try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(datFile)))) {
                writeMethod.invoke(null, compound, dos);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clear offline ender chest for " + datFile.getName() + ": " + e.getMessage());
        }
    }

    private Method findReadMethod() {
        for (Method m : nbtIoClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().equals("read")
                    && m.getParameterCount() >= 1
                    && m.getParameterCount() <= 2
                    && m.getReturnType().equals(compoundTagClass)) {
                return m;
            }
        }
        return null;
    }

    private Method findItemStackFromTagMethod() {
        for (Method m : nmsItemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().equals("of")
                    && m.getParameterCount() == 1
                    && m.getReturnType().equals(nmsItemStackClass)) {
                return m;
            }
        }
        for (Method m : nmsItemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().equals("parseOptional")
                    && m.getParameterCount() >= 2) {
                return m;
            }
        }
        for (Method m : nmsItemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().equals("parse")
                    && m.getParameterCount() >= 2) {
                return m;
            }
        }
        return null;
    }

    private Object getNbtAccounter() {
        try {
            Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
            java.lang.reflect.Field unlimitedField = nbtAccounterClass.getField("UNLIMITED");
            return unlimitedField.get(null);
        } catch (Exception e) {
            return null;
        }
    }
}
