package com.echest.enderchest.storage;

import com.echest.enderchest.util.ItemSerializationException;
import org.bukkit.inventory.ItemStack;

/**
 * Converts a single item stack to and from its stored representation.
 *
 * <p>Implementations must never swallow errors: a failure has to be reported as an
 * {@link ItemSerializationException} so that the caller can keep the original bytes instead of
 * silently dropping the item.
 */
public interface ItemCodec {

    /**
     * Serializes an item stack.
     *
     * @param item the item, may be {@code null} or air
     * @return the payload, or {@code null} for an empty slot
     * @throws ItemSerializationException when the item cannot be serialized
     */
    byte[] serialize(ItemStack item) throws ItemSerializationException;

    /**
     * Deserializes a payload.
     *
     * @param payload the payload, may be {@code null} or empty
     * @return the item, or {@code null} for an empty slot
     * @throws ItemSerializationException when the payload cannot be read
     */
    ItemStack deserialize(byte[] payload) throws ItemSerializationException;
}
