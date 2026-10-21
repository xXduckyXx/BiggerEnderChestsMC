package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotCodecTest {

    @Test
    void encodesAndDecodesEverySlot() {
        byte[][] slots = new byte[54][];
        slots[0] = new byte[]{1, 2, 3};
        slots[27] = new byte[]{9};
        slots[53] = new byte[]{0, 0, 0, 0};

        byte[] encoded = SlotCodec.encode(slots);
        SlotCodec.Decoded decoded = SlotCodec.decode(encoded, 54);

        assertEquals(54, decoded.slots().length);
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.slots()[0]);
        assertNull(decoded.slots()[1]);
        assertArrayEquals(new byte[]{9}, decoded.slots()[27]);
        assertArrayEquals(new byte[]{0, 0, 0, 0}, decoded.slots()[53]);
        assertFalse(decoded.hasTrailing());
    }

    @Test
    void keepsTheLayoutOfThePreviousReleases() {
        // The framing must not change, otherwise existing databases cannot be read any more.
        byte[] encoded = SlotCodec.encode(new byte[][]{{7, 7}});

        assertEquals(1 + 4 + 2, encoded.length);
        assertEquals(1, encoded[0]);
        assertEquals(2, encoded[4]);
        assertEquals(7, encoded[5]);
        assertEquals(7, encoded[6]);
    }

    @Test
    void emptyBlobDecodesToEmptySlots() {
        SlotCodec.Decoded decoded = SlotCodec.decode(new byte[0], 54);

        assertEquals(54, decoded.slots().length);
        for (byte[] slot : decoded.slots()) {
            assertNull(slot);
        }
        assertFalse(decoded.hasTrailing());
    }

    @Test
    void truncatedBlobKeepsTheReadableSlotsAndReportsTheRest() {
        byte[][] slots = new byte[54][];
        slots[0] = new byte[]{1, 2, 3};
        slots[1] = new byte[]{4, 5};
        byte[] encoded = SlotCodec.encode(slots);

        // Cut the payload of the second slot in half.
        byte[] truncated = Arrays.copyOf(encoded, 14);

        SlotCodec.Decoded decoded = SlotCodec.decode(truncated, 54);

        assertArrayEquals(new byte[]{1, 2, 3}, decoded.slots()[0]);
        assertNull(decoded.slots()[1], "an incomplete slot must not be used");
        assertNull(decoded.slots()[2]);
        assertTrue(decoded.hasTrailing(), "the unreadable bytes must be reported");
    }

    @Test
    void corruptLengthDoesNotSwallowTheRestOfTheBlob() {
        byte[][] slots = new byte[54][];
        slots[0] = new byte[]{1, 2, 3};
        byte[] encoded = SlotCodec.encode(slots);
        // Corrupt the length of the slot.
        encoded[1] = 0;
        encoded[2] = 0;
        encoded[3] = 0;
        encoded[4] = (byte) 0xFF;

        SlotCodec.Decoded decoded = SlotCodec.decode(encoded, 54);

        assertNull(decoded.slots()[0]);
        assertTrue(decoded.hasTrailing());
        assertEquals(encoded.length - 5, decoded.trailing().length);
    }

    @Test
    void trailingBytesAreKeptAndWrittenBack() {
        byte[] extra = "some unknown bytes".getBytes(StandardCharsets.UTF_8);
        byte[][] slots = new byte[54][];
        slots[0] = new byte[]{1};
        byte[] encoded = SlotCodec.encode(slots, extra);

        SlotCodec.Decoded decoded = SlotCodec.decode(encoded, 54);
        assertArrayEquals(extra, decoded.trailing());

        // Writing the content again must not throw the unknown bytes away.
        byte[] rewritten = SlotCodec.encode(decoded.slots(), decoded.trailing());
        SlotCodec.Decoded second = SlotCodec.decode(rewritten, 54);
        assertArrayEquals(extra, second.trailing());
        assertArrayEquals(new byte[]{1}, second.slots()[0]);
    }

    @Test
    void detectsLegacyJson() {
        assertTrue(SlotCodec.looksLikeJson("[{}]".getBytes(StandardCharsets.UTF_8)));
        assertTrue(SlotCodec.looksLikeJson(" {\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SlotCodec.looksLikeJson(new byte[]{1, 2, 3}));
        assertFalse(SlotCodec.looksLikeJson(null));
    }

    @Test
    void decodeNeverThrowsOnGarbage() {
        byte[] garbage = new byte[64];
        Arrays.fill(garbage, (byte) 0x7F);

        SlotCodec.Decoded decoded = SlotCodec.decode(garbage, 54);

        assertNotNull(decoded.slots());
        assertEquals(54, decoded.slots().length);
    }
}
