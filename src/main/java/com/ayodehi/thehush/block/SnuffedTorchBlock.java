package com.ayodehi.thehush.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A torch with its head gone black: a thread of smoke now and then, no light. Flint and steel relights it. */
public class SnuffedTorchBlock extends TorchBlock {
    public SnuffedTorchBlock(BlockBehaviour.Properties properties) {
        super(ParticleTypes.SMOKE, properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5, 0.0, 0.01, 0.0);
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        return relight(stack, state, level, pos, player, hand, Blocks.TORCH.defaultBlockState());
    }

    /** Flint and steel, or a fire charge, brings a snuffed torch back. */
    static InteractionResult relight(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                     InteractionHand hand, BlockState lit) {
        if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!level.isClientSide()) {
            level.setBlock(pos, lit, Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 0.9F + level.getRandom().nextFloat() * 0.2F);
            if (stack.is(Items.FLINT_AND_STEEL)) stack.hurtAndBreak(1, player, hand);
            else if (!player.hasInfiniteMaterials()) stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}
