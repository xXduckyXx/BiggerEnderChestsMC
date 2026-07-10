package com.echest.enderchest.api;
/*

Oh hello there reader.
thank you for reading the code :3
it means a lot to me that your interested in this project :P 
feel free to edit whatever :D

*/
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

public interface EnderChestAPI {

    ItemStack @NotNull [] getEnderChest(@NotNull UUID uuid);

    void setEnderChest(@NotNull UUID uuid, ItemStack @NotNull [] contents);

    boolean hasEnderChest(@NotNull UUID uuid);

    void clearEnderChest(@NotNull UUID uuid);
}
