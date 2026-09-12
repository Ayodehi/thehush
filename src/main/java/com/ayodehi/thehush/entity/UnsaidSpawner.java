package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignDefinition;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

/**
 * Where the Unsaid rise: the soul sand valleys of the Nether, once the road has begun and until it goes
 * down into the Deep Dark. Every few seconds each player there has a small chance of one drifting up
 * out of sight, sixteen to thirty-two blocks off; never more than two near a player at once.
 */
public final class UnsaidSpawner {
    private static final int CHECK_EVERY_TICKS = 100;
    private static final int MAX_NEAR = 2;
    private static final double NEAR = 48.0;
    private static final String FIRST_STAGE = "geared";
    private static final String LAST_STAGE = "deep_dark";

    private UnsaidSpawner() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0 || !Config.UNSAID.get()) return;
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server)) return;
        CampaignDefinition def = cm.definition(server);
        CampaignState s = cm.state(server);
        if (def == null || !inStages(def, s)) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !(p.level() instanceof ServerLevel level) || level.dimension() != Level.NETHER) continue;
            if (!level.getBiome(p.blockPosition()).is(Biomes.SOUL_SAND_VALLEY)) continue;
            if (level.getRandom().nextInt(Config.UNSAID_RARITY.get()) != 0) continue;
            if (level.getEntitiesOfClass(UnsaidEntity.class, p.getBoundingBox().inflate(NEAR)).size() >= MAX_NEAR) continue;
            Vec3 spot = airSpot(level, p);
            if (spot == null) continue;
            UnsaidEntity u = ModEntities.UNSAID.get().create(level, EntitySpawnReason.NATURAL);
            if (u == null) continue;
            u.snapTo(spot.x, spot.y, spot.z, level.getRandom().nextFloat() * 360F, 0F);
            if (level.addFreshEntity(u)) TheHushMod.LOGGER.debug("One of the Unsaid has risen near {} at {}", p.getName().getString(), spot);
        }
    }

    private static boolean inStages(CampaignDefinition def, CampaignState s) {
        int at = def.indexOf(s.stage);
        int first = def.indexOf(FIRST_STAGE);
        int last = def.indexOf(LAST_STAGE);
        if (first < 0) first = 1;
        if (last < 0) last = def.stages.size() - 2;
        return at >= first && at <= last;
    }

    /** Open air 16 to 32 blocks from the player, roughly at their height, where they are not looking. */
    private static Vec3 airSpot(ServerLevel level, ServerPlayer p) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 16 + random.nextDouble() * 16;
            double x = p.getX() + Math.cos(angle) * dist;
            double z = p.getZ() + Math.sin(angle) * dist;
            double y = p.getY() + random.nextInt(9) - 3;
            BlockPos c = BlockPos.containing(x, y, z);
            if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()) continue;
            Vec3 spot = new Vec3(x, y, z);
            if (EchoEntity.seenBy(level, p, spot.add(0, 1.5, 0))) continue;
            return spot;
        }
        return null;
    }
}
