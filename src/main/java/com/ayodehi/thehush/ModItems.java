package com.ayodehi.thehush;

import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TheHushMod.MODID);

    // Entity types are registered before items, so the holder resolves here.
    public static final DeferredItem<SpawnEggItem> AI_VILLAGER_SPAWN_EGG = ITEMS.registerItem(
            "ai_villager_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(ModEntities.AI_VILLAGER.get()));

    public static final DeferredItem<SpawnEggItem> PILGRIM_SPAWN_EGG = ITEMS.registerItem(
            "pilgrim_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(ModEntities.PILGRIM.get()));

    public static final DeferredItem<SpawnEggItem> WICK_SPAWN_EGG = ITEMS.registerItem(
            "wick_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(ModEntities.WICK.get()));

    public static final DeferredItem<SpawnEggItem> ECHO_SPAWN_EGG = ITEMS.registerItem(
            "echo_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(ModEntities.ECHO.get()));

    public static final DeferredItem<SpawnEggItem> UNSAID_SPAWN_EGG = ITEMS.registerItem(
            "unsaid_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(ModEntities.UNSAID.get()));

    /** Blocks register before items, so the block holders resolve here. */
    public static final DeferredItem<StandingAndWallBlockItem> SNUFFED_TORCH = ITEMS.registerItem("snuffed_torch",
            p -> new StandingAndWallBlockItem(ModBlocks.SNUFFED_TORCH.get(), ModBlocks.SNUFFED_WALL_TORCH.get(), Direction.DOWN, p),
            p -> p.useBlockDescriptionPrefix());

    public static final DeferredItem<BlockItem> DARK_SOUL_LANTERN = ITEMS.registerSimpleBlockItem(ModBlocks.DARK_SOUL_LANTERN);

    private ModItems() {}
}
