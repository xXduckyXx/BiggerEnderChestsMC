package com.echest.enderchest.util;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small helpers to find the classes of the server implementation.
 *
 * <p>The plugin uses reflection in exactly one place, the animation of the ender chest block. The
 * helper methods that were copy pasted into the different classes before are collected here, so
 * that there is only one implementation of them.
 */
public final class CraftReflection {

    private CraftReflection() {
    }

    /**
     * Finds a class of the server implementation.
     *
     * <p>The class is searched in the packages of CraftBukkit, with and without the version of
     * the server in the name, and finally in every package that starts with
     * {@code org.bukkit.craftbukkit}.
     *
     * @param simpleName the simple name of the class, for example {@code CraftWorld}
     * @param logger     logger for diagnostics
     * @return the class or {@code null} when it was not found
     */
    public static Class<?> findCraftClass(String simpleName, Logger logger) {
        try {
            return Class.forName("org.bukkit.craftbukkit." + simpleName);
        } catch (ClassNotFoundException ignored) {
            // The server uses the versioned package names, try them below.
        }

        try {
            Class<?> craftServer = Class.forName("org.bukkit.craftbukkit.CraftServer");
            String packageName = craftServer.getPackage().getName();
            String versionSuffix = packageName.substring(packageName.lastIndexOf('.') + 1);
            return Class.forName("org.bukkit.craftbukkit." + versionSuffix + "." + simpleName);
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Fall through to the scan below.
        }

        try {
            for (Package pkg : Package.getPackages()) {
                String name = pkg.getName();
                if (name.startsWith("org.bukkit.craftbukkit") && name.endsWith("inventory")) {
                    try {
                        return Class.forName(name + "." + simpleName);
                    } catch (ClassNotFoundException ignored) {
                        // Try the next package.
                    }
                }
            }
        } catch (LinkageError e) {
            logger.log(Level.FINE, "Could not scan the packages of the server", e);
        }
        return null;
    }

    /**
     * Finds a method by its name and its parameter types.
     *
     * @param clazz   the class
     * @param name    the name of the method
     * @param params  the parameter types
     * @return the method or {@code null} when it was not found
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
