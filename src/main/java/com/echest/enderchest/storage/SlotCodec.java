package com.echest.enderchest.storage;

import java.util.Arrays;

/**
 * Binary framing for the {@code contents} blob of a single ender chest row.
 *
 * <p>The layout is byte for byte the same as the one written by the previous releases of this
 * plugin, so databases created by an older version keep working without any migration:
 *
 * <pre>
 *   for every slot:
 *     boolean present   (1 byte)
 *     if present:
 *       int    length    (4 bytes, big endian)
 *       byte[] payload   (length bytes)
 * </pre>
 *
 * <p>A payload is the serialized form of a single item stack. The bytes are produced by
 * {@link ItemCodec} and are opaque to this class, which only knows about slot boundaries.
 *
 * <p>This class is deliberately free of any Bukkit dependency so that it can be unit tested
 * without a running server.
 */
public final class SlotCodec {

    /** Upper bound for the size of a single slot payload (1 MiB). */
    public static final int MAX_SLOT_BYTES = 1_048_576;

    private SlotCodec() {
    }

    /**
     * Encodes the given slots into the binary representation stored in the database.
     *
     * @param slots        payload per slot, {@code null} or empty means "empty slot"
     * @param trailing     optional bytes that could not be parsed from a previous row. They are
     *                     appended verbatim so that unknown data is never thrown away.
     * @return the encoded blob
     */
    public static byte[] encode(byte[][] slots, byte[] trailing) {
        if (slots == null) {
            throw new IllegalArgumentException("slots must not be null");
        }
        int size = 0;
        for (byte[] slot : slots) {
            size += slot == null || slot.length == 0 ? 1 : 5 + slot.length;
        }
        if (trailing != null) {
            size += trailing.length;
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (byte[] slot : slots) {
            if (slot == null || slot.length == 0) {
                out[pos++] = 0;
                continue;
            }
            out[pos++] = 1;
            out[pos++] = (byte) (slot.length >>> 24);
            out[pos++] = (byte) (slot.length >>> 16);
            out[pos++] = (byte) (slot.length >>> 8);
            out[pos++] = (byte) slot.length;
            System.arraycopy(slot, 0, out, pos, slot.length);
            pos += slot.length;
        }
        if (trailing != null && trailing.length > 0) {
            System.arraycopy(trailing, 0, out, pos, trailing.length);
        }
        return out;
    }

    /**
     * Encodes the given slots without any trailing data.
     *
     * @param slots payload per slot
     * @return the encoded blob
     */
    public static byte[] encode(byte[][] slots) {
        return encode(slots, null);
    }

    /**
     * Decodes a blob previously produced by {@link #encode(byte[][])}.
     *
     * <p>Decoding never throws: a truncated or corrupt blob simply yields fewer slots. The bytes
     * that could not be interpreted are returned as {@link Decoded#trailing()} so that the caller
     * can store them again instead of silently dropping them.
     *
     * @param data      the blob, may be {@code null}
     * @param slotCount number of slots the chest has
     * @return the decoded slots
     */
    public static Decoded decode(byte[] data, int slotCount) {
        byte[][] slots = new byte[slotCount][];
        if (data == null || data.length == 0) {
            return new Decoded(slots, null);
        }
        int pos = 0;
        for (int slot = 0; slot < slotCount && pos < data.length; slot++) {
            boolean present = data[pos++] != 0;
            if (!present) {
                continue;
            }
            if (pos + 4 > data.length) {
                break;
            }
            int length = readInt(data, pos);
            pos += 4;
            if (length <= 0 || length > MAX_SLOT_BYTES) {
                // The length is not trustworthy, the rest of the blob cannot be resynchronised.
                break;
            }
            if (pos + length > data.length) {
                break;
            }
            slots[slot] = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
        }
        byte[] trailing = pos < data.length ? Arrays.copyOfRange(data, pos, data.length) : null;
        return new Decoded(slots, trailing);
    }

    /**
     * Checks whether the blob uses the very old JSON format instead of the binary framing.
     *
     * @param data the blob
     * @return {@code true} when the blob looks like JSON
     */
    public static boolean looksLikeJson(byte[] data) {
        if (data == null) {
            return false;
        }
        for (byte b : data) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '[' || b == '{';
        }
        return false;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Result of decoding a blob.
     *
     * @param slots    the decoded slot payloads, {@code null} for empty slots
     * @param trailing bytes that could not be parsed, may be {@code null}
     */
    public record Decoded(byte[][] slots, byte[] trailing) {

        public boolean hasTrailing() {
            return trailing != null && trailing.length > 0;
        }
    }
}
