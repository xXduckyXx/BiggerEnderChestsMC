package com.echest.enderchest.storage;

import com.echest.enderchest.util.ItemSerializationException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the very old JSON format that earlier releases of this plugin used.
 *
 * <p>The JSON format stored either a base64 encoded item or a Bukkit serialized map per slot.
 * Both are converted into the current payload format. Items that cannot be converted keep their
 * original bytes, they are marked as unreadable and written back unchanged so that no data is
 * thrown away silently.
 */
public final class LegacyJsonDecoder {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private LegacyJsonDecoder() {
    }

    /**
     * Decodes a legacy JSON row.
     *
     * @param data      the raw row
     * @param slotCount number of slots
     * @param codec     codec used to convert the items
     * @param logger    logger for diagnostics
     * @return the decoded content
     */
    public static ChestContents decode(byte[] data, int slotCount, ItemCodec codec, Logger logger) {
        byte[][] payloads = new byte[slotCount][];
        boolean[] unreadable = new boolean[slotCount];
        ItemStack[] items = new ItemStack[slotCount];

        String json = new String(data, StandardCharsets.UTF_8);
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                logger.warning("Legacy ender chest data is not a JSON array, ignoring it");
                return ChestContents.of(items, payloads, unreadable);
            }
            JsonArray array = root.getAsJsonArray();
            for (int slot = 0; slot < Math.min(array.size(), slotCount); slot++) {
                JsonElement element = array.get(slot);
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    decodeBase64(element.getAsString(), slot, codec, payloads, unreadable, items, logger);
                } else if (element.isJsonObject()) {
                    decodeMap(element, slot, codec, payloads, unreadable, items, logger);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse the legacy JSON ender chest data", e);
        }
        return ChestContents.of(items, payloads, unreadable);
    }

    private static void decodeBase64(String encoded, int slot, ItemCodec codec, byte[][] payloads,
                                     boolean[] unreadable, ItemStack[] items, Logger logger) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            logger.warning("Slot " + slot + " of the legacy ender chest data is not valid base64");
            unreadable[slot] = true;
            return;
        }
        try {
            ItemStack item = codec.deserialize(raw);
            if (item != null) {
                byte[] payload = codec.serialize(item);
                if (payload != null && payload.length > 0) {
                    payloads[slot] = payload;
                    items[slot] = item;
                    return;
                }
            }
        } catch (ItemSerializationException ignored) {
            // Fall through, the raw bytes are kept below.
        }
        // The item cannot be read with the current format, but its bytes are still worth keeping.
        payloads[slot] = raw;
        unreadable[slot] = true;
        logger.warning("Slot " + slot + " of the legacy ender chest data could not be converted, "
                + "the original bytes are kept");
    }

    private static void decodeMap(JsonElement element, int slot, ItemCodec codec, byte[][] payloads,
                                  boolean[] unreadable, ItemStack[] items, Logger logger) {
        try {
            Map<String, Object> map = GSON.fromJson(element, MAP_TYPE);
            ItemStack item = ItemStack.deserialize(map);
            if (item == null) {
                return;
            }
            byte[] payload = codec.serialize(item);
            if (payload != null && payload.length > 0) {
                payloads[slot] = payload;
                items[slot] = item;
            }
        } catch (Exception e) {
            logger.warning("Slot " + slot + " of the legacy ender chest data could not be read: "
                    + e.getMessage());
        }
    }
}
