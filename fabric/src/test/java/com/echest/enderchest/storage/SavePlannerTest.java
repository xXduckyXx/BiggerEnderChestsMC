package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavePlannerTest {

    @Test
    void writesTheSerializedSlotsUnchanged() {
        byte[][] payloads = new byte[54][];
        payloads[0] = new byte[]{1};

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(), null, null);

        assertFalse(plan.refused());
        assertFalse(plan.hasFailedSlots());
        assertArrayEquals(new byte[]{1}, plan.payloads()[0]);
    }

    @Test
    void keepsTheOldBytesWhenAnItemCannotBeSerialized() {
        byte[][] payloads = new byte[54][];
        byte[][] previous = new byte[54][];
        previous[4] = new byte[]{42};

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(4), previous, null);

        assertFalse(plan.refused(), "the old bytes can be kept, the write may go through");
        assertEquals(List.of(4), plan.failedSlots());
        assertArrayEquals(new byte[]{42}, plan.payloads()[4]);
    }

    @Test
    void refusesTheWriteWhenThereIsNothingToFallBackTo() {
        byte[][] payloads = new byte[54][];

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(7), new byte[54][], new boolean[54]);

        assertTrue(plan.refused(), "writing an empty slot would delete the item");
        assertEquals(List.of(7), plan.failedSlots());
    }

    @Test
    void keepsUnreadableSlotsEvenIfTheyLookEmpty() {
        byte[][] payloads = new byte[54][];
        byte[][] previous = new byte[54][];
        previous[2] = new byte[]{99};
        boolean[] unreadable = new boolean[54];
        unreadable[2] = true;

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(), previous, unreadable);

        assertFalse(plan.refused());
        assertArrayEquals(new byte[]{99}, plan.payloads()[2]);
    }

    @Test
    void dropsReadableSlotsThePlayerEmptied() {
        byte[][] payloads = new byte[54][];
        byte[][] previous = new byte[54][];
        previous[2] = new byte[]{99};
        boolean[] unreadable = new boolean[54];

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(), previous, unreadable);

        assertNull(plan.payloads()[2], "a slot the player emptied may be empty again");
    }

    @Test
    void aNewItemReplacesAnUnreadableSlot() {
        byte[][] payloads = new byte[54][];
        payloads[2] = new byte[]{5};
        byte[][] previous = new byte[54][];
        previous[2] = new byte[]{99};
        boolean[] unreadable = new boolean[54];
        unreadable[2] = true;

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(), previous, unreadable);

        assertArrayEquals(new byte[]{5}, plan.payloads()[2]);
    }

    @Test
    void ignoresOutOfRangeSlots() {
        byte[][] payloads = new byte[4][];

        SavePlanner.Plan plan = SavePlanner.plan(payloads, List.of(9, -1), null, null);

        assertFalse(plan.refused());
        assertNotNull(plan.payloads());
    }
}
