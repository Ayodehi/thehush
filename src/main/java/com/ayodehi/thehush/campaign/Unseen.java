package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.network.HushDropPayload;
import com.ayodehi.thehush.voice.VoiceService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Things heard and not seen. The Hush listens, and now and then, to someone alone in the dark, it lets
 * itself be heard: their own footsteps walked back to them a beat late from behind; a heartbeat under the
 * sculk; every sound in the world gone for a breath and then one click; a knock at the door with nobody
 * there; something turning over far below; the village bell rung once at night by no one. Each is a sound
 * sent to that one player alone (the bell excepted), so nothing is ever there to find. The Traveller hears
 * the ones that matter and says little.
 */
public final class Unseen {
    public enum Kind { FOOTSTEPS, HEARTBEAT, DROP, KNOCK, BELOW, BELL }

    private static final double ALONE_RADIUS = 24.0;
    private static final long FIRST_DELAY_TICKS = 2 * 1200L;
    private static final long RETRY_TICKS = 20 * 20L;
    private static final int FOOTSTEP_TICKS = 160;
    private static final int DROP_TICKS = 100;

    private static final class Ear {
        long nextTick;
        long footstepsUntil;
        double stepAcc;
        @Nullable Vec3 lastPos;
        @Nullable Kind last;
    }

    private record Pending(long tick, Runnable run) {}

    private static final Map<UUID, Ear> ears = new HashMap<>();
    private static final List<Pending> pending = new ArrayList<>();
    private static long lastBellNight = -10;

    private Unseen() {}

    public static void clear() {
        ears.clear();
        pending.clear();
        lastBellNight = -10;
    }

    // ---- scheduling ----

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!pending.isEmpty()) {
            Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.tick <= now) {
                    it.remove();
                    try {
                        p.run.run();
                    } catch (RuntimeException e) {
                        TheHushMod.LOGGER.debug("An unseen sound failed", e);
                    }
                }
            }
        }
        if (!Config.UNSEEN.get()) return;
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server)) return;
        CampaignState s = cm.state(server);
        CampaignDefinition def = cm.definition(server);
        if (def == null || "ended".equals(s.stage)) return;
        int stage = Math.max(0, def.indexOf(s.stage));

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Ear ear = ears.computeIfAbsent(p.getUUID(), k -> {
                Ear e = new Ear();
                e.nextTick = now + FIRST_DELAY_TICKS;
                return e;
            });
            if (ear.footstepsUntil > now) trackFootsteps(p, ear, now);
            if (now % 20 != 0 || now < ear.nextTick) continue;
            if (!listening(p)) {
                ear.nextTick = now + RETRY_TICKS;
                continue;
            }
            Kind kind = choose(server, def, s, stage, p, ear);
            if (kind == null) {
                ear.nextTick = now + RETRY_TICKS;
                continue;
            }
            play(server, kind, p, ear, now);
            ear.last = kind;
            double scale = Math.max(0.5, 1.0 - 0.06 * stage);
            long gap = Math.round(Config.UNSEEN_MINUTES.get() * 1200L * scale * (0.6 + p.getRandom().nextDouble() * 0.8));
            ear.nextTick = now + Math.max(600L, gap);
        }
    }

    /** Survival, in the Overworld, alone, and in the dark or under a roof at night. */
    private static boolean listening(ServerPlayer p) {
        if (!p.isAlive() || p.isCreative() || p.isSpectator()) return false;
        if (!(p.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return false;
        for (ServerPlayer o : level.players()) {
            if (o != p && o.distanceToSqr(p) < ALONE_RADIUS * ALONE_RADIUS) return false;
        }
        BlockPos at = p.blockPosition();
        int light = level.getMaxLocalRawBrightness(at);
        boolean underground = !level.canSeeSky(at.above());
        return light <= 5 || (underground && light <= 8) || (isNight(level) && underground);
    }

    static boolean isNight(ServerLevel level) {
        long t = level.getOverworldClockTime() % 24000L;
        return t >= 13000 && t < 23000;
    }

    private static @Nullable Kind choose(MinecraftServer server, CampaignDefinition def, CampaignState s, int stage, ServerPlayer p, Ear ear) {
        ServerLevel level = (ServerLevel) p.level();
        RandomSource r = p.getRandom();
        List<Kind> can = new ArrayList<>();
        int firstNight = def.indexOf("first_night"), geared = def.indexOf("geared");
        can.add(Kind.FOOTSTEPS);
        if (stage >= firstNight && (NightSilence.sculkNear(level, p.blockPosition(), 16) != null || (stage >= geared && p.getY() < 0))) can.add(Kind.HEARTBEAT);
        AiVillagerEntity him = CampaignManager.get().traveller(server, p);
        if (stage >= firstNight && (him == null || !VoiceService.get().isSpeaking(him))) can.add(Kind.DROP);
        if (stage >= firstNight && isNight(level) && doorNear(level, p.blockPosition()) != null && noZombieNear(level, p)) can.add(Kind.KNOCK);
        if (stage >= geared && p.getY() < 0) can.add(Kind.BELOW);
        if (stage >= firstNight && bellDue(server, s, level, p)) can.add(Kind.BELL);
        if (ear.last != null && can.size() > 1) can.remove(ear.last);
        if (can.isEmpty()) return null;
        if (can.contains(Kind.BELL) && r.nextInt(2) == 0) return Kind.BELL; // rare to be eligible; take it
        return can.get(r.nextInt(can.size()));
    }

    // ---- the sounds ----

    /** Play one now, for testing; returns what happened. */
    public static String playNow(MinecraftServer server, Kind kind, ServerPlayer p) {
        Ear ear = ears.computeIfAbsent(p.getUUID(), k -> new Ear());
        String why = play(server, kind, p, ear, server.getTickCount());
        ear.last = kind;
        return why;
    }

    private static String play(MinecraftServer server, Kind kind, ServerPlayer p, Ear ear, long now) {
        ServerLevel level = (ServerLevel) p.level();
        CampaignManager cm = CampaignManager.get();
        AiVillagerEntity him = cm.traveller(server, p);
        boolean heHears = him != null && CampaignManager.withinEarshot(him, p);
        if (Config.CAMPAIGN_DEBUG.get()) TheHushMod.LOGGER.info("Unseen: {} for {}", kind.name().toLowerCase(Locale.ROOT), p.getName().getString());
        switch (kind) {
            case FOOTSTEPS -> {
                boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 0.002;
                if (moving) {
                    // Walking: their own steps come back to them a beat late from behind.
                    ear.footstepsUntil = now + FOOTSTEP_TICKS;
                    ear.stepAcc = 0;
                    ear.lastPos = p.position();
                    return "Their steps will be walked back to them for eight seconds.";
                }
                // Standing still: something walks up behind them and stops a few paces short.
                BlockState ground = level.getBlockState(p.blockPosition().below());
                SoundEvent step = ground.isAir() ? SoundEvents.STONE_STEP : ground.getSoundType().getStepSound();
                int steps = 6 + p.getRandom().nextInt(3);
                double from = 12, to = 3;
                for (int i = 0; i < steps; i++) {
                    double dist = from - (from - to) * i / (steps - 1);
                    Vec3 where = behind(p, dist);
                    float vol = 0.2F + 0.3F * i / (steps - 1);
                    float pitch = 0.8F + p.getRandom().nextFloat() * 0.08F;
                    at(now + 10L * i, () -> hear(p, step, where, vol, pitch));
                }
                return steps + " steps coming up behind them, stopping three blocks short.";
            }
            case HEARTBEAT -> {
                for (int i = 0; i < 5; i++) {
                    float vol = 0.22F + 0.06F * i;
                    at(now + 28L * i, () -> hear(p, SoundEvents.WARDEN_HEARTBEAT, p.position().add(0, -5, 0), vol, 0.8F));
                }
                return "A heartbeat, five times, from under their feet.";
            }
            case DROP -> {
                PacketDistributor.sendToPlayer(p, new HushDropPayload(DROP_TICKS));
                at(now + DROP_TICKS + 12, () -> hear(p, SoundEvents.SCULK_CLICKING, behind(p, 4), 0.7F, 0.7F));
                if (heHears) {
                    at(now + DROP_TICKS + 60, () -> cm.queueBeat(server, "Just now every sound in the world went out for a breath, and when it came back "
                            + "there was one click, from behind you. You know that quiet, and you will not name it. One line, low: ask if they heard it, "
                            + "or tell them to keep moving.", true));
                }
                return "Silence for five seconds, then one click from behind.";
            }
            case KNOCK -> {
                BlockPos door = doorNear(level, p.blockPosition());
                if (door == null) return "No door near them.";
                Vec3 d = Vec3.atCenterOf(door);
                int knocks = 2 + p.getRandom().nextInt(2);
                for (int i = 0; i < knocks; i++) {
                    at(now + 14L * i, () -> hear(p, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, d, 0.6F, 1.0F));
                }
                // Then someone walks away from it.
                Vec3 out = d.subtract(p.position()).multiply(1, 0, 1).normalize();
                BlockState ground = level.getBlockState(door.below());
                SoundEvent step = ground.getSoundType().getStepSound();
                for (int i = 1; i <= 4; i++) {
                    Vec3 where = d.add(out.scale(1.5 * i));
                    float vol = 0.35F - 0.05F * i;
                    at(now + 14L * knocks + 20L + 9L * i, () -> hear(p, step, where, vol, 0.9F));
                }
                return knocks + " knocks at the door, then footsteps going away.";
            }
            case BELOW -> {
                Vec3 under = p.position().add(p.getRandom().nextDouble() * 6 - 3, -10, p.getRandom().nextDouble() * 6 - 3);
                at(now, () -> hear(p, p.getRandom().nextBoolean() ? SoundEvents.WARDEN_DIG : SoundEvents.WARDEN_NEARBY_CLOSE, under, 0.5F, 0.85F));
                at(now + 45, () -> hear(p, SoundEvents.WARDEN_SNIFF, under, 0.4F, 0.9F));
                if (heHears && cm.state(server).flags.add("unseen_below_said")) {
                    at(now + 90, () -> cm.queueBeat(server, "Something large moved far below you both, and then breathed in. You went still. One line, "
                            + "if any: that it is asleep, and that they should be quiet while it is.", true));
                }
                return "Something moved ten blocks down, then sniffed.";
            }
            case BELL -> {
                BlockPos bell = villageBell(server, cm.state(server), level);
                if (bell == null) return "No village bell.";
                BlockState st = level.getBlockState(bell);
                if (!(st.getBlock() instanceof BellBlock b)) return "The bell is gone.";
                b.attemptToRing(level, bell, null);
                lastBellNight = level.getOverworldClockTime() / 24000L;
                if (heHears) {
                    at(now + 50, () -> cm.queueBeat(server, "The village bell has just rung once, in the dark, and nobody was at it. You did not look toward it. "
                            + "One line, or nothing.", true));
                }
                return "The village bell rang once.";
            }
        }
        return "";
    }

    /** The echo: each step they take is played again a beat later from a few blocks behind them. */
    private static void trackFootsteps(ServerPlayer p, Ear ear, long now) {
        Vec3 pos = p.position();
        if (ear.lastPos != null) {
            ear.stepAcc += Math.sqrt(pos.subtract(ear.lastPos).horizontalDistanceSqr());
            if (ear.stepAcc >= 0.9 && p.onGround()) {
                ear.stepAcc = 0;
                ServerLevel level = (ServerLevel) p.level();
                BlockState ground = level.getBlockState(p.blockPosition().below());
                SoundEvent step = ground.isAir() ? SoundEvents.STONE_STEP : ground.getSoundType().getStepSound();
                Vec3 where = behind(p, 6 + p.getRandom().nextInt(3));
                float pitch = 0.82F + p.getRandom().nextFloat() * 0.1F;
                at(now + 12 + p.getRandom().nextInt(8), () -> hear(p, step, where, 0.35F, pitch));
            }
        }
        ear.lastPos = pos;
    }

    // ---- helpers ----

    private static void at(long tick, Runnable run) {
        pending.add(new Pending(tick, run));
    }

    /** A sound only this player hears, at a place in the world. */
    private static void hear(ServerPlayer p, SoundEvent sound, Vec3 where, float volume, float pitch) {
        if (!p.isAlive()) return;
        p.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.HOSTILE,
                where.x, where.y, where.z, volume, pitch, p.getRandom().nextLong()));
    }

    private static Vec3 behind(ServerPlayer p, double blocks) {
        Vec3 look = p.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
        return p.position().subtract(look.normalize().scale(blocks));
    }

    private static @Nullable BlockPos doorNear(ServerLevel level, BlockPos c) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-6, -1, -6), c.offset(6, 2, 6))) {
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof DoorBlock && !st.getValue(DoorBlock.OPEN) && st.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                double d = q.distSqr(c);
                if (d < bestD) {
                    bestD = d;
                    best = q.immutable();
                }
            }
        }
        return best;
    }

    private static boolean noZombieNear(ServerLevel level, ServerPlayer p) {
        return level.getEntitiesOfClass(Zombie.class, p.getBoundingBox().inflate(16), z -> z.isAlive()).isEmpty();
    }

    private static boolean bellDue(MinecraftServer server, CampaignState s, ServerLevel level, ServerPlayer p) {
        long t = level.getOverworldClockTime() % 24000L;
        if (t < 15000 || t >= 21000) return false;
        long night = level.getOverworldClockTime() / 24000L;
        if (night - lastBellNight < 3) return false;
        BlockPos bell = villageBell(server, s, level);
        return bell != null && bell.distSqr(p.blockPosition()) < 64 * 64 && bell.distSqr(p.blockPosition()) > 8 * 8;
    }

    private static @Nullable BlockPos villageBell(MinecraftServer server, CampaignState s, ServerLevel level) {
        if (s.arrival == null || !s.arrivalVillage) return null;
        BlockPos arrival = new BlockPos(s.arrival[0], s.arrival[1], s.arrival[2]);
        if (!level.isLoaded(arrival)) return null;
        return level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), arrival, 48, PoiManager.Occupancy.ANY)
                .filter(pos -> level.getBlockState(pos).is(Blocks.BELL)).orElse(null);
    }
}
