package com.echest.enderchest.fabric;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Converts an item stack into bytes and back.
 *
 * <p>The item is encoded with the codec of Minecraft itself, through the registry lookup of the
 * server. The result is the NBT of the item stack with the data version of the server in it,
 * written compressed. That is the same layout the Paper version of this plugin uses, so both
 * versions can in principle read each other, but that was not tested end to end.
 */
public final class MinecraftItemCodec {

    private final RegistryOps<Tag> ops;
    private final int dataVersion;

    /**
     * Creates a codec.
     *
     * @param lookup the registry lookup of the server
     */
    public MinecraftItemCodec(HolderLookup.Provider lookup) {
        this.ops = lookup.createSerializationContext(NbtOps.INSTANCE);
        this.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /**
     * Serializes an item stack.
     *
     * @param stack the item
     * @return the bytes of the item
     * @throws ItemCodecException when the item cannot be serialized
     */
    public byte[] serialize(ItemStack stack) throws ItemCodecException {
        try {
            Tag nbt = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            if (!(nbt instanceof CompoundTag tag)) {
                throw new ItemCodecException("the item did not encode into a compound tag");
            }
            tag.putInt(SharedConstants.DATA_VERSION_TAG, dataVersion);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ItemCodecException("could not write the item", e);
        } catch (RuntimeException e) {
            throw new ItemCodecException("could not encode the item", e);
        }
    }

    /**
     * Deserializes an item stack.
     *
     * @param payload the bytes of the item
     * @return the item
     * @throws ItemCodecException when the item cannot be read
     */
    public ItemStack deserialize(byte[] payload) throws ItemCodecException {
        if (payload == null || payload.length == 0) {
            throw new ItemCodecException("the payload is empty");
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            return ItemStack.CODEC.parse(ops, tag).getOrThrow();
        } catch (IOException e) {
            throw new ItemCodecException("could not read the item", e);
        } catch (RuntimeException e) {
            throw new ItemCodecException("could not decode the item", e);
        }
    }
}
