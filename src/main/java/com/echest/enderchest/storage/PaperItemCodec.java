package com.echest.enderchest.storage;

import com.echest.enderchest.util.ItemSerializationException;
import org.bukkit.inventory.ItemStack;

/**
 * {@link ItemCodec} backed by the public Paper API.
 *
 * <p>Paper exposes {@link ItemStack#serializeAsBytes()} and {@link ItemStack#deserializeBytes(byte[])}
 * which store an item stack as (gzip compressed) NBT together with the data version of the server
 * that wrote it. Compared to the hand written NMS reflection that earlier releases of this plugin
 * used, this is a supported API, it survives server updates and it runs the vanilla data fixers
 * when an old item is read back.
 *
 * <p>No reflection is used at all here.
 */
public final class PaperItemCodec implements ItemCodec {

    /**
     * Checks whether the running server supports the Paper item serialization API.
     *
     * @return {@code true} when {@code ItemStack#serializeAsBytes} exists
     */
    public static boolean isSupported() {
        try {
            ItemStack.class.getMethod("serializeAsBytes");
            ItemStack.class.getMethod("deserializeBytes", byte[].class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Override
    public byte[] serialize(ItemStack item) throws ItemSerializationException {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try {
            byte[] bytes = item.serializeAsBytes();
            if (bytes == null || bytes.length == 0) {
                throw new ItemSerializationException(item, -1, "Paper returned an empty payload");
            }
            return bytes;
        } catch (ItemSerializationException e) {
            throw e;
        } catch (Throwable t) {
            throw new ItemSerializationException(item, -1, "Paper serializeAsBytes failed", t);
        }
    }

    @Override
    public ItemStack deserialize(byte[] payload) throws ItemSerializationException {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(payload);
            if (item == null || item.getType().isAir()) {
                return null;
            }
            return item;
        } catch (Throwable t) {
            throw new ItemSerializationException(null, -1, "Paper deserializeBytes failed", t);
        }
    }
}
