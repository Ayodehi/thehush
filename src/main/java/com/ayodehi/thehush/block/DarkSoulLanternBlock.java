package com.ayodehi.thehush.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A soul lantern with its fire gone out: the same iron cage, the glass gone cold and grey, no light, and
 * now and then a wisp of soul drifting up out of it. Flint and steel, or a fire charge, relights it into
 * a soul lantern, hanging or standing as it was.
 */
public class DarkSoulLanternBlock extends LanternBlock {
    public DarkSoulLanternBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** A soul lantern in the same position (hanging, waterlogged) as this dark one. */
    public static BlockState litFrom(BlockState dark) {
        return Blocks.SOUL_LANTERN.defaultBlockState()
                .setValue(HANGING, dark.getValue(HANGING))
                .setValue(WATERLOGGED, dark.getValue(WATERLOGGED));
    }

    /** A dark lantern in the same position as a lit one. */
    public static BlockState darkFrom(BlockState lit, BlockState dark) {
        return dark.setValue(HANGING, lit.getValue(HANGING)).setValue(WATERLOGGED, lit.getValue(WATERLOGGED));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(14) == 0) {
            double y = pos.getY() + (state.getValue(HANGING) ? 0.6 : 0.5);
            level.addParticle(ParticleTypes.SOUL, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.2, y,
                    pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.2, 0.0, 0.02, 0.0);
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        return SnuffedTorchBlock.relight(stack, state, level, pos, player, hand, litFrom(state));
    }
}
