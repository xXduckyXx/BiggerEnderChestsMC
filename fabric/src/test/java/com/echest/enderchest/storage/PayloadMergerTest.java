package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadMergerTest {

    @Test
    void mergesIntoFreeSlotsWithoutOverwriting() {
        byte[][] existing = new byte[54][];
        existing[0] = new byte[]{1};
        existing[5] = new byte[]{2};

        byte[][] incoming = new byte[54][];
        incoming[0] = new byte[]{3};
        incoming[1] = new byte[]{4};

        PayloadMerger.Result result = PayloadMerger.merge(existing, incoming);

        assertArrayEquals(new byte[]{1}, result.merged()[0]);
        assertArrayEquals(new byte[]{2}, result.merged()[5]);
        assertArrayEquals(new byte[]{3}, result.merged()[1]);
        assertArrayEquals(new byte[]{4}, result.merged()[2]);
        assertFalse(result.hasLeftover());
    }

    @Test
    void keepsTheOrderOfTheIncomingSlots() {
        byte[][] existing = new byte[54][];
        byte[][] incoming = new byte[54][];
        incoming[7] = new byte[]{7};
        incoming[2] = new byte[]{2};

        PayloadMerger.Result result = PayloadMerger.merge(existing, incoming);

        // The items are placed in the order of their original slot.
        assertArrayEquals(new byte[]{2}, result.merged()[0]);
        assertArrayEquals(new byte[]{7}, result.merged()[1]);
    }

    @Test
    void reportsTheItemsThatDoNotFit() {
        byte[][] existing = new byte[2][];
        existing[0] = new byte[]{1};
        existing[1] = new byte[]{2};

        byte[][] incoming = new byte[3][];
        incoming[0] = new byte[]{3};
        incoming[1] = new byte[]{4};
        incoming[2] = new byte[]{5};

        PayloadMerger.Result result = PayloadMerger.merge(existing, incoming);

        assertEquals(2, result.count());
        assertTrue(result.hasLeftover());
        assertArrayEquals(new byte[]{1}, result.merged()[0]);
        assertArrayEquals(new byte[]{2}, result.merged()[1]);
        assertArrayEquals(new byte[]{3}, result.merged()[2]);
        assertArrayEquals(new byte[]{4}, result.leftover()[0]);
        assertArrayEquals(new byte[]{5}, result.leftover()[1]);
        assertArrayEquals(new int[]{1, 2}, result.leftoverSlots());
    }

    @Test
    void countsFreeSlots() {
        byte[][] slots = new byte[54][];
        slots[0] = new byte[]{1};

        assertEquals(53, PayloadMerger.countFree(slots));
        assertEquals(0, PayloadMerger.countFree(null));
    }

    @Test
    void ignoresEmptySlots() {
        byte[][] existing = new byte[54][];
        byte[][] incoming = new byte[54][];
        incoming[3] = new byte[0];

        PayloadMerger.Result result = PayloadMerger.merge(existing, incoming);

        assertFalse(result.hasLeftover());
        assertNull(result.merged()[0]);
    }
}
