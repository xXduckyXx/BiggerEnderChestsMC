package com.echest.enderchest.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class AnimationUtil {

    private static Class<?> craftWorldClass;
    private static Class<?> blockPosClass;
    private static Class<?> levelClass;
    private static Method getHandle;
    private static Method blockEvent;
    private static Method getBlockState;
    private static Method getBlock;
    private static Constructor<?> blockPosCtor;
    private static boolean available = false;
    private static String resolvedClassName = null;

    private static Class<?> resolveCraftWorld() {
        try {
            return Class.forName("org.bukkit.craftbukkit.CraftWorld");
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> craftServerClass = Class.forName("org.bukkit.craftbukkit.CraftServer");
            String packageName = craftServerClass.getPackage().getName();
            String fullName = packageName + ".CraftWorld";
            return Class.forName(fullName);
        } catch (Exception ignored) {
        }

        try {
            for (Package pkg : Package.getPackages()) {
                if (pkg.getName().startsWith("org.bukkit.craftbukkit")) {
                    try {
                        return Class.forName(pkg.getName() + ".CraftWorld");
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static void init(JavaPlugin plugin) {
        try {
            craftWorldClass = resolveCraftWorld();
            if (craftWorldClass == null) {
                throw new ClassNotFoundException("CraftWorld not found in any known package");
            }
            resolvedClassName = craftWorldClass.getName();
            plugin.getLogger().info("AnimationUtil: resolved CraftWorld via: " + resolvedClassName);

            blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            levelClass = Class.forName("net.minecraft.world.level.Level");
            Class<?> iBlockDataClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");

            getHandle = craftWorldClass.getMethod("getHandle");
            blockEvent = levelClass.getMethod("blockEvent", blockPosClass, blockClass, int.class, int.class);
            getBlockState = levelClass.getMethod("getBlockState", blockPosClass);
            getBlock = iBlockDataClass.getMethod("getBlock");

            blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
            available = true;
        } catch (Exception e) {
            plugin.getLogger().warning("AnimationUtil: block action reflection unavailable: " + e.getMessage());
        }
    }

    public static void playOpenAnimation(Player player, Location location) {
        if (!available) return;
        try {
            Object world = getHandle.invoke(location.getWorld());
            Object pos = blockPosCtor.newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Object state = getBlockState.invoke(world, pos);
            Object block = getBlock.invoke(state);
            blockEvent.invoke(world, pos, block, 1, 1);
        } catch (Exception e) {
        }
    }

    public static void playCloseAnimation(Player player, Location location) {
        if (!available) return;
        try {
            Object world = getHandle.invoke(location.getWorld());
            Object pos = blockPosCtor.newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Object state = getBlockState.invoke(world, pos);
            Object block = getBlock.invoke(state);
            blockEvent.invoke(world, pos, block, 1, 0);
        } catch (Exception e) {
        }
    }
}
