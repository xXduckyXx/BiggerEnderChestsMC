package com.echest.enderchest.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays the lid animation of the ender chest block.
 *
 * <p>The animation is a small visual extra, it uses the block event of the server. When the
 * internals of the server cannot be found, the plugin still works, the chest just opens without
 * the animation.
 */
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
    private static Logger logger;

    /**
     * Prepares the reflection that is needed for the animation.
     *
     * @param plugin the plugin
     */
    public static void init(JavaPlugin plugin) {
        logger = plugin.getLogger();
        try {
            craftWorldClass = CraftReflection.findCraftClass("CraftWorld", logger);
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

    /**
     * Plays the opening animation of an ender chest block.
     *
     * @param player   the player, only used for the log message
     * @param location the location of the block
     */
    public static void playOpenAnimation(Player player, Location location) {
        playAnimation(player, location, 1);
    }

    /**
     * Plays the closing animation of an ender chest block.
     *
     * @param player   the player, only used for the log message
     * @param location the location of the block
     */
    public static void playCloseAnimation(Player player, Location location) {
        playAnimation(player, location, 0);
    }

    private static void playAnimation(Player player, Location location, int state) {
        if (!available) {
            return;
        }
        try {
            Object world = getHandle.invoke(location.getWorld());
            Object pos = blockPosCtor.newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Object blockState = getBlockState.invoke(world, pos);
            Object block = getBlock.invoke(blockState);
            blockEvent.invoke(world, pos, block, 1, state);
        } catch (Exception e) {
            // The animation is a nice to have, a failure must not spam the console.
            if (logger != null) {
                logger.log(Level.FINE, "Could not play the ender chest animation", e);
            }
        }
    }
}
