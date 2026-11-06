package com.echest.enderchest.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderChestHolderTest {

    @Test
    void detectsADragThatTouchesALockedSlot() {
        EnderChestHolder holder = new EnderChestHolder(UUID.randomUUID());
        holder.lockSlot(12);

        assertTrue(holder.touchesLockedSlot(List.of(0, 12, 13)));
        assertTrue(holder.touchesLockedSlot(Set.of(12)));
    }

    @Test
    void ignoresADragThatDoesNotTouchALockedSlot() {
        EnderChestHolder holder = new EnderChestHolder(UUID.randomUUID());
        holder.lockSlot(12);

        assertFalse(holder.touchesLockedSlot(List.of(0, 1, 2)));
    }

    @Test
    void treatsAnEmptyOrNullDragAsUnlocked() {
        EnderChestHolder holder = new EnderChestHolder(UUID.randomUUID());
        holder.lockSlot(12);

        assertFalse(holder.touchesLockedSlot(null));
        assertFalse(holder.touchesLockedSlot(List.of()));
    }

    @Test
    void ignoresNullEntriesInTheRawSlots() {
        EnderChestHolder holder = new EnderChestHolder(UUID.randomUUID());
        holder.lockSlot(12);

        assertFalse(holder.touchesLockedSlot(java.util.Arrays.asList(null, 3)));
    }
}
