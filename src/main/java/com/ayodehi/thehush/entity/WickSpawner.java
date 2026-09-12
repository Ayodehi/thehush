package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.List;

/**
 * Where Wicks come from: caves a player has lit. Every few seconds each player underground and deep enough
 * is checked; a single torch within reach and no Wick already about is enough for a small chance of one
 * appearing in the dark at the edge of the light, out of sight.
 */
public final class WickSpawner {
    private static final int CHECK_EVERY_TICKS = 100;
    private static final int MAX_Y = 20;
    private static final int MIN_TORCHES = 1;
    private static final double NO_WICK_WITHIN = 64.0;

    private WickSpawner() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0 || !Config.WICKS.get()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !(p.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) continue;
            BlockPos at = p.blockPosition();
            if (at.getY() > MAX_Y || level.canSeeSky(at.above())) continue;
            if (level.getRandom().nextInt(Config.WICK_RARITY.get()) != 0) continue;
            if (!level.getEntitiesOfClass(WickEntity.class, p.getBoundingBox().inflate(NO_WICK_WITHIN)).isEmpty()) continue;
            if (countTorches(level, at, 24) < MIN_TORCHES) continue;
            BlockPos spot = darkSpot(level, p);
            if (spot == null) continue;
            WickEntity w = ModEntities.WICK.get().create(level, EntitySpawnReason.NATURAL);
            if (w == null) continue;
            w.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
            if (level.addFreshEntity(w)) TheHushMod.LOGGER.debug("A Wick has come for {}'s torches at {}", p.getName().getString(), spot);
        }
    }

    private static int countTorches(ServerLevel level, BlockPos center, int r) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -8, -r), center.offset(r, 8, r))) {
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) n++;
        }
        return n;
    }

    /** A dark, walkable, out-of-sight spot 16 to 32 blocks from the player. */
    private static BlockPos darkSpot(ServerLevel level, ServerPlayer p) {
        var random = level.getRandom();
        BlockPos base = p.blockPosition();
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 16 + random.nextInt(17);
            int x = base.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * dist);
            for (int y = base.getY() + 4; y >= base.getY() - 8; y--) {
                BlockPos c = new BlockPos(x, y, z);
                if (!level.isLoaded(c)) break;
                if (level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isSolid()
                        && level.getBrightness(LightLayer.BLOCK, c) <= 2 && !level.canSeeSky(c)
                        && !WickEntity.anyPlayerSees(level, c, 48)) {
                    return c;
                }
            }
        }
        return null;
    }
}
