package com.echest.enderchest.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinActionTest {

    @Test
    void importsTheVanillaChestWhenThePlayerHasNoStoredChest() {
        assertEquals(JoinAction.IMPORT, JoinAction.decide(false, false, true));
    }

    @Test
    void doesNothingWhenThereIsNothingToMove() {
        assertEquals(JoinAction.NOTHING, JoinAction.decide(false, false, false));
        assertEquals(JoinAction.NOTHING, JoinAction.decide(false, true, false));
    }

    @Test
    void mergesInsteadOfDeletingWhenThePlayerAlreadyHasAChest() {
        assertEquals(JoinAction.MERGE, JoinAction.decide(false, true, true));
    }

    @Test
    void neverImportsAnExportedChestAgain() {
        assertEquals(JoinAction.SKIP_EXPORTED, JoinAction.decide(true, false, true));
        assertEquals(JoinAction.SKIP_EXPORTED, JoinAction.decide(true, true, true));
        assertEquals(JoinAction.SKIP_EXPORTED, JoinAction.decide(true, false, false));
    }
}
