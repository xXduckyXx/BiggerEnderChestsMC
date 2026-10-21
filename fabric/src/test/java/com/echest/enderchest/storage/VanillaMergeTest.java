package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VanillaMerge}.
 *
 * <p>The test that matters most is {@link #movedItemsAreRemovedFromTheVanillaChest()}, it is the
 * regression test for the duplication bug: an item that was moved into the stored chest must not
 * stay in the vanilla ender chest, otherwise every join added another copy of it.
 */
class VanillaMergeTest {

    private static byte[][] payloads(String... values) {
        byte[][] result = new byte[values.length][];
        for (int slot = 0; slot < values.length; slot++) {
            result[slot] = values[slot] == null ? null : values[slot].getBytes();
        }
        return result;
    }

    @Test
    void movedItemsAreRemovedFromTheVanillaChest() {
        byte[][] incoming = payloads("diamond", "gold", null, "iron");
        boolean[] moved = VanillaMerge.movedSlots(incoming, new int[0], List.of());

        assertTrue(moved[0], "the diamond was moved into the stored chest");
        assertTrue(moved[1], "the gold was moved into the stored chest");
        assertFalse(moved[2], "the empty slot was not moved");
        assertTrue(moved[3], "the iron was moved into the stored chest");
    }

    @Test
    void itemsThatDoNotFitStayInTheVanillaChest() {
        byte[][] incoming = payloads("diamond", "gold");
        boolean[] moved = VanillaMerge.movedSlots(incoming, new int[]{1}, List.of());

        assertTrue(moved[0], "the diamond was moved into the stored chest");
        assertFalse(moved[1], "the gold did not fit, it stays in the vanilla ender chest");
    }

    @Test
    void itemsThatCouldNotBeSerializedStayInTheVanillaChest() {
        byte[][] incoming = payloads("diamond", "gold");
        boolean[] moved = VanillaMerge.movedSlots(incoming, new int[0], List.of(1));

        assertTrue(moved[0], "the diamond was moved into the stored chest");
        assertFalse(moved[1], "the gold could not be serialized, it stays in the vanilla ender chest");
    }

    @Test
    void emptySlotsAreNeverMoved() {
        byte[][] incoming = payloads(null, null);
        boolean[] moved = VanillaMerge.movedSlots(incoming, new int[0], List.of());

        assertFalse(moved[0]);
        assertFalse(moved[1]);
    }

    @Test
    void anEmptyChestMovesNothing() {
        boolean[] moved = VanillaMerge.movedSlots(new byte[27][], new int[0], List.of());

        for (boolean slot : moved) {
            assertFalse(slot, "an empty slot cannot be moved");
        }
    }
}
