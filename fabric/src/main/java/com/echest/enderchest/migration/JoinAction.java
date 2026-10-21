package com.echest.enderchest.migration;

/**
 * Decides what has to happen with the vanilla ender chest of a player when they join.
 *
 * <p>The decision is kept in its own class, free of any Bukkit dependency, so that it can be
 * unit tested without a server.
 */
public enum JoinAction {

    /** There is nothing to do. */
    NOTHING,

    /**
     * The player has no chest in the database yet, the content of the vanilla ender chest is
     * imported and the vanilla chest is cleared.
     */
    IMPORT,

    /**
     * The player already has a chest in the database, the content of the vanilla ender chest is
     * merged into the free slots of the stored chest. Items that do not fit stay in the vanilla
     * chest, nothing is ever deleted.
     */
    MERGE,

    /**
     * The chest of the player was exported to the vanilla ender chest, the database stays empty
     * and the vanilla chest is not touched.
     */
    SKIP_EXPORTED;

    /**
     * Decides what to do when a player joins.
     *
     * @param exported         the chest of the player was exported to the vanilla ender chest
     * @param hasStoredChest   the player has a chest in the database
     * @param vanillaHasItems  the vanilla ender chest of the player is not empty
     * @return the action
     */
    public static JoinAction decide(boolean exported, boolean hasStoredChest, boolean vanillaHasItems) {
        if (exported) {
            return SKIP_EXPORTED;
        }
        if (!hasStoredChest) {
            return vanillaHasItems ? IMPORT : NOTHING;
        }
        return vanillaHasItems ? MERGE : NOTHING;
    }
}
