package com.ayodehi.thehush.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The wall-mounted snuffed torch. */
public class SnuffedWallTorchBlock extends WallTorchBlock {
    public SnuffedWallTorchBlock(BlockBehaviour.Properties properties) {
        super(ParticleTypes.SMOKE, properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) != 0) return;
        Direction opposite = state.getValue(FACING).getOpposite();
        level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5 + 0.27 * opposite.getStepX(), pos.getY() + 0.95,
                pos.getZ() + 0.5 + 0.27 * opposite.getStepZ(), 0.0, 0.01, 0.0);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        return SnuffedTorchBlock.relight(stack, state, level, pos, player, hand,
                Blocks.WALL_TORCH.defaultBlockState().setValue(FACING, state.getValue(FACING)));
    }
}
