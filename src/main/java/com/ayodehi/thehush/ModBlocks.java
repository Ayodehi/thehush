package com.ayodehi.thehush;

import com.ayodehi.thehush.block.DarkSoulLanternBlock;
import com.ayodehi.thehush.block.SnuffedTorchBlock;
import com.ayodehi.thehush.block.SnuffedWallTorchBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TheHushMod.MODID);

    private static final ResourceKey<LootTable> SNUFFED_TORCH_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(TheHushMod.MODID, "blocks/snuffed_torch"));

    /** A torch the Wick has been at: same shape, black head, a thread of smoke, no light. */
    public static final DeferredBlock<SnuffedTorchBlock> SNUFFED_TORCH = BLOCKS.registerBlock("snuffed_torch",
            SnuffedTorchBlock::new, p -> p.noCollision().instabreak().sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY));

    public static final DeferredBlock<SnuffedWallTorchBlock> SNUFFED_WALL_TORCH = BLOCKS.registerBlock("snuffed_wall_torch",
            SnuffedWallTorchBlock::new, p -> p.noCollision().instabreak().sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY)
                    .overrideLootTable(Optional.of(SNUFFED_TORCH_LOOT))
                    .overrideDescription("block." + TheHushMod.MODID + ".snuffed_torch"));

    /** A soul lantern the Deep Dark has put out: the cage, the cold glass, no light. Flint relights it. */
    public static final DeferredBlock<DarkSoulLanternBlock> DARK_SOUL_LANTERN = BLOCKS.registerBlock("dark_soul_lantern",
            DarkSoulLanternBlock::new, p -> p.mapColor(MapColor.METAL).forceSolidOn().strength(3.5F).sound(SoundType.LANTERN)
                    .noOcclusion().pushReaction(PushReaction.DESTROY));

    private ModBlocks() {}
}
