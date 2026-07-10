package com.echest.enderchest.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ItemSerializer {

    private static boolean paperBytesAvailable = false;
    private static java.lang.reflect.Method paperSerializeBytes;
    private static java.lang.reflect.Method paperDeserializeBytes;
    private static java.util.logging.Logger logger;

    private static Class<?> nbtIoClass;
    private static Class<?> compoundTagClass;
    private static Class<?> nmsItemStackClass;
    private static Class<?> craftItemStackClass;

    private static Method nbtWrite;
    private static Method nbtRead;
    private static Method nmsSave;
    private static Method nmsOf;
    private static Method asBukkitCopy;
    private static Method asNMSCopy;
    private static Constructor<?> compoundTagCtor;
    private static java.lang.reflect.Field handleField;
    private static boolean nbtAvailable = false;

    private static String craftItemStackClassName = null;

    public static void init(JavaPlugin plugin) {
        logger = plugin.getLogger();

        try {
            paperSerializeBytes = ItemStack.class.getMethod("serializeAsBytes");
            paperDeserializeBytes = ItemStack.class.getMethod("deserializeBytes", byte[].class);
            paperBytesAvailable = true;
            plugin.getLogger().info("ItemSerializer: using Paper serializeAsBytes/deserializeBytes (no NMS reflection needed)");
        } catch (NoSuchMethodException e) {
            plugin.getLogger().info("ItemSerializer: Paper byte methods not available, falling back to NBT reflection");
        }

        initNbtReflection(plugin);
    }

    private static Class<?> resolveCraftItemStack() {
        try {
            return Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> craftServerClass = Class.forName("org.bukkit.craftbukkit.CraftServer");
            String packageName = craftServerClass.getPackage().getName();
            String versionSuffix = packageName.substring(packageName.lastIndexOf('.') + 1);

            String[] prefixes = {"", versionSuffix + "."};
            for (String prefix : prefixes) {
                String fullName = "org.bukkit.craftbukkit" + (prefix.isEmpty() ? "" : "." + prefix) + "inventory.CraftItemStack";
                try {
                    return Class.forName(fullName);
                } catch (ClassNotFoundException ignored) {
                }
            }
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

    private static void initNbtReflection(JavaPlugin plugin) {
        try {
            nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
            compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");

            craftItemStackClass = resolveCraftItemStack();
            if (craftItemStackClass != null) {
                craftItemStackClassName = craftItemStackClass.getName();
                plugin.getLogger().info("ItemSerializer: resolved CraftItemStack via: " + craftItemStackClassName);
            } else {
                plugin.getLogger().warning("ItemSerializer: CraftItemStack not found — NBT serialization will be unavailable");
            }

            nbtWrite = findMethod(nbtIoClass, void.class, new Class<?>[]{compoundTagClass, DataOutput.class});
            nbtRead = findMethod(nbtIoClass, compoundTagClass, new Class<?>[]{DataInput.class});

            if (nbtRead == null) {
                try {
                    Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
                    nbtRead = findMethod(nbtIoClass, compoundTagClass, new Class<?>[]{DataInput.class, nbtAccounterClass});
                } catch (ClassNotFoundException ignored) {}
            }

            nmsSave = findMethod(nmsItemStackClass, compoundTagClass, new Class<?>[]{compoundTagClass});

            nmsOf = findStaticMethod(nmsItemStackClass, new Class<?>[]{compoundTagClass}, nmsItemStackClass);

            if (nmsOf == null) {
                for (Method m : nmsItemStackClass.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && m.getName().equals("parseOptional")
                            && m.getParameterCount() >= 2) {
                        nmsOf = m;
                        break;
                    }
                }
            }
            if (nmsOf == null) {
                for (Method m : nmsItemStackClass.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && m.getName().equals("parse")
                            && m.getParameterCount() >= 2) {
                        nmsOf = m;
                        break;
                    }
                }
            }

            if (craftItemStackClass != null) {
                asBukkitCopy = findStaticMethod(craftItemStackClass, new Class<?>[]{nmsItemStackClass}, ItemStack.class);
                asNMSCopy = findStaticMethod(craftItemStackClass, new Class<?>[]{ItemStack.class}, nmsItemStackClass);
                try {
                    handleField = craftItemStackClass.getDeclaredField("handle");
                    handleField.setAccessible(true);
                } catch (Exception ignored) {
                }
            }

            compoundTagCtor = compoundTagClass.getDeclaredConstructor();

            if (nbtWrite != null && nbtRead != null && nmsSave != null && nmsOf != null) {
                nbtAvailable = true;
                plugin.getLogger().info("ItemSerializer: NBT reflection fallback OK");
            } else {
                StringBuilder missing = new StringBuilder();
                if (nbtWrite == null) missing.append(" NbtIo.write");
                if (nbtRead == null) missing.append(" NbtIo.read");
                if (nmsSave == null) missing.append(" ItemStack.save");
                if (nmsOf == null) missing.append(" ItemStack.of/parse");
                plugin.getLogger().warning("ItemSerializer: NBT reflection incomplete, missing:" + missing);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("ItemSerializer: NBT reflection init failed: " + e.getMessage());
        }
    }

    private static Method findMethod(Class<?> clazz, Class<?> returnType, Class<?>[] params) {
        for (Method m : clazz.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType().equals(returnType) &&
                    paramsMatch(m.getParameterTypes(), params)) {
                return m;
            }
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> clazz, Class<?>[] params, Class<?> returnType) {
        for (Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) &&
                    (returnType == null || returnType.isAssignableFrom(m.getReturnType())) &&
                    paramsMatch(m.getParameterTypes(), params)) {
                return m;
            }
        }
        return null;
    }

    private static boolean paramsMatch(Class<?>[] actual, Class<?>[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].isAssignableFrom(expected[i]) && !expected[i].isAssignableFrom(actual[i])) {
                return false;
            }
        }
        return true;
    }

    public static byte[] serialize(ItemStack item) throws ItemSerializationException {
        if (item == null || item.getType().isAir()) return new byte[0];

        if (paperBytesAvailable) {
            try {
                return (byte[]) paperSerializeBytes.invoke(item);
            } catch (Exception e) {
                throw new ItemSerializationException(item, -1, "Paper serializeAsBytes failed", e);
            }
        }

        if (nbtAvailable) {
            try {
                Object nmsItem = toNMS(item);
                if (nmsItem == null) {
                    throw new ItemSerializationException(item, -1, "toNMS returned null");
                }
                Object tag = compoundTagCtor.newInstance();
                nmsSave.invoke(nmsItem, tag);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                nbtWrite.invoke(null, tag, dos);
                dos.close();
                return baos.toByteArray();
            } catch (ItemSerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new ItemSerializationException(item, -1, "NBT serialize failed", e);
            }
        }

        throw new ItemSerializationException(item, -1, "No serialization method available (Paper bytes or NBT reflection)");
    }

    public static ItemStack deserialize(byte[] data) {
        if (data == null || data.length == 0) return null;

        if (paperBytesAvailable) {
            try {
                return (ItemStack) paperDeserializeBytes.invoke(null, (Object) data);
            } catch (Exception e) {
                logger.warning("ItemSerializer: Paper deserializeBytes failed: " + e.getMessage());
            }
        }

        if (nbtAvailable) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                DataInputStream dis = new DataInputStream(bais);

                Object tag;
                if (nbtRead.getParameterCount() == 1) {
                    tag = nbtRead.invoke(null, dis);
                } else {
                    Object nbtAccounter = null;
                    try {
                        Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
                        java.lang.reflect.Field unlimitedField = nbtAccounterClass.getField("UNLIMITED");
                        nbtAccounter = unlimitedField.get(null);
                    } catch (Exception ignored) {}
                    if (nbtAccounter != null) {
                        tag = nbtRead.invoke(null, dis, nbtAccounter);
                    } else {
                        tag = nbtRead.invoke(null, dis);
                    }
                }
                dis.close();

                Object nmsItem;
                if (nmsOf.getParameterCount() == 1) {
                    nmsItem = nmsOf.invoke(null, tag);
                } else {
                    Object registryAccess = getRegistryAccess();
                    Object result = nmsOf.invoke(null, registryAccess, tag);
                    if (result != null && result.getClass().getName().equals("java.util.Optional")) {
                        Method orElse = result.getClass().getMethod("orElse", Object.class);
                        nmsItem = orElse.invoke(result, (Object) null);
                    } else {
                        nmsItem = result;
                    }
                }
                if (nmsItem == null) return null;

                if (asBukkitCopy != null) {
                    return (ItemStack) asBukkitCopy.invoke(null, nmsItem);
                }
            } catch (Exception e) {
                logger.warning("ItemSerializer: NBT deserialize failed: " + e.getMessage());
            }
        }

        return null;
    }

    private static Object toNMS(ItemStack item) {
        if (craftItemStackClass == null) return null;
        try {
            if (handleField != null && craftItemStackClass.isInstance(item)) {
                return handleField.get(item);
            }
            if (asNMSCopy != null) {
                return asNMSCopy.invoke(null, item);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object getRegistryAccess() {
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
            Method registryAccess = minecraftServerClass.getMethod("registryAccess");
            return registryAccess.invoke(server);
        } catch (Exception e) {
            return null;
        }
    }
}
