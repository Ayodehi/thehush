package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * The villagers avoid him and cannot say why. Any vanilla villager near a campaign persona looks away
 * from him, forgets any intention of going over to chat, and, if he is close, drifts off a few blocks.
 * Runs whether or not the campaign has begun: they were avoiding him before the player ever spoke to him.
 */
public final class Avoidance {
    private static final double NOTICE = 8.0;
    private static final double TOO_CLOSE = 5.0;
    private static final int EVERY_TICKS = 10;
    private static int nudged;

    private Avoidance() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % EVERY_TICKS != 0 || !Config.CAMPAIGN_ENABLED.get()) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (AiVillagerEntity him : level.getEntities(EntityTypeTest.forClass(AiVillagerEntity.class),
                    v -> v.isAlive() && !v.persona().campaign().isEmpty())) {
                Vec3 at = him.position();
                for (Villager v : level.getEntitiesOfClass(Villager.class, him.getBoundingBox().inflate(NOTICE),
                        o -> !(o instanceof AiVillagerEntity) && o.isAlive() && !o.isSleeping() && !o.isBaby())) {
                    shy(v, him, at);
                }
            }
        }
        if (nudged > 0 && Config.CAMPAIGN_DEBUG.get() && server.getTickCount() % 100 == 0) {
            TheHushMod.LOGGER.info("Villagers keeping their distance: {} nudge(s) in the last five seconds", nudged);
            nudged = 0;
        }
    }

    private static void shy(Villager v, AiVillagerEntity him, Vec3 at) {
        Brain<Villager> brain = v.getBrain();
        if (brain.getMemory(MemoryModuleType.INTERACTION_TARGET).map(t -> t == him).orElse(false)) {
            brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        }
        Vec3 away = v.position().subtract(at);
        away = away.horizontalDistanceSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : new Vec3(away.x, 0, away.z).normalize();
        // Look anywhere but at him: a point a few blocks off on the far side.
        brain.setMemoryWithExpiry(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(v.getEyePosition().add(away.scale(4))), 2L * EVERY_TICKS + 5);
        if (v.distanceToSqr(him) < TOO_CLOSE * TOO_CLOSE) {
            // Whatever they were about to do, they do it somewhere else: a few steps off, at a walk, not a
            // run (their panic pace is 0.5; a stroll is 0.4). A target already leading away from him is kept.
            boolean alreadyLeaving = brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .map(w -> w.getTarget().currentPosition().distanceToSqr(at) > TOO_CLOSE * TOO_CLOSE).orElse(false);
            if (alreadyLeaving) return;
            Vec3 to = LandRandomPos.getPosAway(v, 6, 3, at);
            if (to == null) to = v.position().add(away.scale(5));
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(to, 0.45F, 1));
            nudged++;
        }
    }
}
