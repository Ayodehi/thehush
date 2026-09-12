package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModBlocks;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.block.DarkSoulLanternBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Act IV: the city's soul lanterns go out one at a time as the chosen approaches the frame. Every six to
 * ten seconds, while the chosen stands in the Deep Dark between the ancient_city stage and the crossing,
 * the lit soul lantern near them that is nearest the frame (but not right beside them, and not the one
 * they are staring at) is swapped for a dark soul lantern, with the sound of a fire going out and a
 * breath of souls. Only lanterns inside the ancient city structure and within reach of the frame are
 * touched, so the player's own lights are safe. Every swap is recorded, so the ending and a reset put the
 * lanterns back as they were.
 */
public final class LanternEvents {
    private static final int SCAN_RADIUS = 24;
    private static final int SCAN_HEIGHT = 12;
    private static final int KEEP_CLEAR = 4;
    private static final int FRAME_REACH = 64;
    private static final int MIN_GAP_TICKS = 6 * 20;
    private static final int MAX_GAP_TICKS = 10 * 20;

    private static long nextTick = Long.MIN_VALUE / 2;

    private LanternEvents() {}

    /** Called from the campaign's per-second tick with the chosen player (may be off-level). */
    public static void tick(MinecraftServer server, CampaignManager cm, CampaignState s, ServerPlayer chosen) {
        if (s.frame == null) return;
        CampaignDefinition def = cm.definition(server);
        if (def == null) return;
        int idx = def.indexOf(s.stage), from = def.indexOf("ancient_city"), until = def.indexOf("quiet");
        if (from < 0 || idx < from || (until >= 0 && idx >= until) || s.flags.contains("crossed")) return;
        ServerLevel level = server.overworld();
        if (chosen.level() != level) return;
        long now = level.getGameTime();
        if (now < nextTick) return;
        RandomSource random = level.getRandom();
        nextTick = now + MIN_GAP_TICKS + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS + 1);
        BlockPos at = chosen.blockPosition();
        if (!level.getBiome(at).is(Biomes.DEEP_DARK)) return;
        BlockPos frame = new BlockPos(s.frame[0], s.frame[1], s.frame[2]);
        if (at.distSqr(frame) > (long) (FRAME_REACH + SCAN_RADIUS) * (FRAME_REACH + SCAN_RADIUS)) return;

        BlockPos pick = pick(level, chosen, at, frame);
        if (pick == null) return;
        BlockState lit = level.getBlockState(pick);
        BlockState dark = DarkSoulLanternBlock.darkFrom(lit, ModBlocks.DARK_SOUL_LANTERN.get().defaultBlockState());
        Clues.setRecorded(level, pick, dark, s);
        s.save();
        double x = pick.getX() + 0.5, y = pick.getY() + 0.5, z = pick.getZ() + 0.5;
        level.playSound(null, pick, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 0.6F + random.nextFloat() * 0.2F);
        level.sendParticles(ParticleTypes.SOUL, x, y, z, 6, 0.2, 0.2, 0.2, 0.02);
        level.sendParticles(ParticleTypes.SMOKE, x, y + 0.2, z, 4, 0.1, 0.1, 0.1, 0.01);
        if (Config.CAMPAIGN_DEBUG.get()) TheHushMod.LOGGER.info("Lantern out at {} ({} blocks from the frame)", pick, Math.round(Math.sqrt(pick.distSqr(frame))));
    }

    /** The lit soul lantern near the chosen that is nearest the frame, not too close to them, not in their gaze, in the city. */
    private static @Nullable BlockPos pick(ServerLevel level, ServerPlayer chosen, BlockPos at, BlockPos frame) {
        Structure city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.ANCIENT_CITY);
        Vec3 eye = chosen.getEyePosition();
        Vec3 look = chosen.getLookAngle();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-SCAN_RADIUS, -SCAN_HEIGHT, -SCAN_RADIUS), at.offset(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS))) {
            if (!level.getBlockState(p).is(Blocks.SOUL_LANTERN)) continue;
            double toFrame = p.distSqr(frame);
            if (toFrame >= bestDist || toFrame > (long) FRAME_REACH * FRAME_REACH) continue;
            if (p.distSqr(at) < (long) KEEP_CLEAR * KEEP_CLEAR) continue;
            Vec3 to = Vec3.atCenterOf(p).subtract(eye);
            if (to.lengthSqr() > 1e-6 && to.normalize().dot(look) > 0.95) continue; // the one they are staring at stays lit
            if (city != null && !level.structureManager().getStructureAt(p, city).isValid()) continue;
            best = p.immutable();
            bestDist = toFrame;
        }
        return best;
    }
}
