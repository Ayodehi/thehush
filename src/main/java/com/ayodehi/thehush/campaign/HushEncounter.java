package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.network.HushSilencePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The boss, built from vanilla parts: sculk sensors, wardens, note blocks, a bell, the darkness effect,
 * and a four-phase controller. Ticks once a second from the campaign. Nothing here persists across a
 * restart; a restart simply puts the chamber back to Listening.
 */
public final class HushEncounter {
    public enum Phase { IDLE, LISTENING, CALLING, ANSWER, SOUND, DONE }

    private static final int MAX_WARDENS = 3;
    private static final int LISTENING_LIMIT_SECONDS = 240;
    private static final int CALLING_LIMIT_SECONDS = 90;
    private static final int ANSWER_LIMIT_SECONDS = 30;
    private static final int SOUND_SECONDS = 12;

    private Phase phase = Phase.IDLE;
    private int seconds;
    private int bellRings;
    private int spawnCooldown;
    private final List<UUID> wardens = new ArrayList<>();
    private @Nullable UUID marker;
    private int markerSeconds;
    private int confessionLine;
    /** After a failed Answer the chamber needs a breath before it can call again. */
    private int calmSeconds;
    private boolean sanitized;
    private final List<int[]> restored = new ArrayList<>();

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    public String describe() {
        return phase + " (" + seconds + "s, " + wardens.size() + " warden(s), bell rung " + bellRings + ")";
    }

    // ---- lifecycle ----

    public void start(MinecraftServer server, CampaignState s) {
        if (running()) return;
        ServerLevel quiet = Quiet.level(server);
        if (quiet == null) return;
        phase = Phase.LISTENING;
        seconds = 0;
        bellRings = 0;
        spawnCooldown = 0;
        TheHushMod.LOGGER.info("The Hush: Listening");
    }

    public void reset(MinecraftServer server) {
        ServerLevel quiet = Quiet.level(server);
        if (quiet != null) {
            for (UUID id : wardens) {
                Entity e = quiet.getEntity(id);
                if (e != null) e.discard();
            }
            dropMarker(quiet);
        }
        wardens.clear();
        sanitized = false;
        phase = Phase.IDLE;
        seconds = 0;
        bellRings = 0;
        confessionLine = 0;
        calmSeconds = 0;
    }

    // ---- ticking (once a second) ----

    public void tick(MinecraftServer server, CampaignManager cm, CampaignState s) {
        ServerLevel quiet = Quiet.level(server);
        if (quiet == null) return;
        ServerPlayer chosen = cm.chosen(server);
        if (chosen == null || !Quiet.isQuiet(chosen.level())) {
            if (running() && phase != Phase.SOUND) {
                // They left the throat entirely (or died): the chamber falls quiet again.
                reset(server);
            }
            return;
        }
        // Whoever is in the Quiet stays in the dark, always.
        chosen.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false));
        AiVillagerEntity traveller = cm.traveller(server, chosen);
        if (traveller != null && Quiet.isQuiet(traveller.level())) {
            traveller.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false));
        }
        if (!sanitized) {
            // A restart mid-Answer would leave him with the boosted health and a stale lead target.
            sanitized = true;
            if (traveller != null) {
                var hp = traveller.getAttribute(Attributes.MAX_HEALTH);
                if (hp != null && hp.getBaseValue() > 20.0) {
                    restoreHealth(traveller);
                    traveller.stopLeading();
                }
            }
        }
        if (phase == Phase.IDLE) {
            if (Quiet.inThroat(chosen.blockPosition())) start(server, s);
            return;
        }
        if (phase == Phase.DONE) return;
        seconds++;
        wardens.removeIf(id -> quiet.getEntity(id) == null);
        switch (phase) {
            case LISTENING -> tickListening(quiet, chosen);
            case CALLING -> tickCalling(quiet, chosen, traveller);
            case ANSWER -> tickAnswer(server, quiet, chosen, traveller, s);
            case SOUND -> tickSound(server, quiet, chosen, traveller, cm, s);
            default -> { }
        }
    }

    private void tickListening(ServerLevel quiet, ServerPlayer chosen) {
        if (spawnCooldown > 0) spawnCooldown--;
        if (spawnCooldown == 0 && wardens.size() < MAX_WARDENS && sensorActiveNear(quiet, chosen.blockPosition(), 16)) {
            spawnWarden(quiet, chosen);
            spawnCooldown = 10;
        }
        double toHeart = chosen.blockPosition().distSqr(Quiet.HEART);
        if (calmSeconds > 0) {
            calmSeconds--;
            if (toHeart <= 8 * 8) return; // step away from the heart before it can call again
        }
        if (toHeart <= 6 * 6 || seconds >= LISTENING_LIMIT_SECONDS) {
            phase = Phase.CALLING;
            seconds = 0;
            TheHushMod.LOGGER.info("The Hush: Calling");
            shriek(quiet);
        }
    }

    private void tickCalling(ServerLevel quiet, ServerPlayer chosen, @Nullable AiVillagerEntity traveller) {
        chosen.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 1, false, false));
        if (seconds % 5 == 0) lightAllSensors(quiet);
        if (seconds % 10 == 0) shriek(quiet);
        for (int i = 0; i < 3 && wardens.size() < MAX_WARDENS; i++) spawnWarden(quiet, chosen); // never spin if spawning is refused
        LivingEntity target = markerSeconds > 0 ? markerEntity(quiet) : null;
        if (markerSeconds > 0 && --markerSeconds == 0) dropMarker(quiet);
        retarget(quiet, target != null ? target : chosen);
        if (seconds >= CALLING_LIMIT_SECONDS || bellRings >= 3) {
            if (traveller == null || !traveller.isAlive() || !Quiet.isQuiet(traveller.level())) {
                return; // he is not here to answer yet; the Calling goes on until he walks back in
            }
            phase = Phase.ANSWER;
            seconds = 0;
            confessionLine = 0;
            TheHushMod.LOGGER.info("The Hush: The Answer");
            traveller.stopFollowing();
            traveller.startLeading(Quiet.HEART.north(2));
            // They listen; they do not get to finish him before the player has had their thirty seconds.
            var hp = traveller.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(600.0);
            traveller.setHealth(600F);
            ConversationManager.get().tellQuietly(chosen, "Every ear in the chamber turns toward him. Reach the heart and ring the bell while they listen.");
        }
    }

    private void tickAnswer(MinecraftServer server, ServerLevel quiet, ServerPlayer chosen, @Nullable AiVillagerEntity traveller, CampaignState s) {
        if (traveller == null || !traveller.isAlive() || !Quiet.isQuiet(traveller.level())) {
            fail(server, quiet, chosen, null, s);
            return;
        }
        if (seconds % 5 == 1 && confessionLine < TheHush.CONFESSION.length) {
            ConversationManager.get().speak(traveller, TheHush.CONFESSION[confessionLine++]);
            quiet.sendParticles(ParticleTypes.SONIC_BOOM, traveller.getX(), traveller.getY() + 1, traveller.getZ(), 1, 0, 0, 0, 0);
        }
        retarget(quiet, traveller);
        traveller.setHealth(600F);
        if (seconds >= ANSWER_LIMIT_SECONDS) fail(server, quiet, chosen, traveller, s);
    }

    private static void restoreHealth(@Nullable AiVillagerEntity traveller) {
        if (traveller == null) return;
        var hp = traveller.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(20.0);
        if (traveller.isAlive()) traveller.setHealth(Math.min(traveller.getHealth(), 20F));
    }

    private void fail(MinecraftServer server, ServerLevel quiet, ServerPlayer chosen, @Nullable AiVillagerEntity traveller, CampaignState s) {
        TheHushMod.LOGGER.info("The Hush: the answer failed");
        s.failedAttempts++;
        s.save();
        if (traveller != null && traveller.isAlive()) {
            traveller.stopLeading();
            restoreHealth(traveller);
            traveller.hurtServer(quiet, quiet.damageSources().genericKill(), Float.MAX_VALUE);
        }
        for (UUID id : wardens) {
            Entity e = quiet.getEntity(id);
            if (e != null) e.discard();
        }
        wardens.clear();
        dropMarker(quiet);
        chosen.removeEffect(MobEffects.DARKNESS);
        ConversationManager.get().tellQuietly(chosen, "The bell went unrung. The chamber settles, and waits for him to come back.");
        phase = Phase.LISTENING;
        seconds = 0;
        bellRings = 0;
        spawnCooldown = 20;
        calmSeconds = 45;
    }

    private void tickSound(MinecraftServer server, ServerLevel quiet, ServerPlayer chosen, @Nullable AiVillagerEntity traveller, CampaignManager cm, CampaignState s) {
        BlockPos h = Quiet.HEART_BELL;
        quiet.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 4.0F, 0.8F + 0.05F * seconds);
        for (int z = 8; z <= 32; z += 8) {
            quiet.playSound(null, Quiet.THROAT.minX(), Quiet.THROAT.minY() + 1, z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.RECORDS, 3.0F, 0.5F + 0.1F * ((seconds + z) % 12));
            quiet.playSound(null, Quiet.THROAT.maxX(), Quiet.THROAT.minY() + 1, z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.RECORDS, 3.0F, 0.5F + 0.1F * ((seconds * 3 + z) % 12));
        }
        if (traveller != null && seconds - 1 < TheHush.END_POEM.length && seconds >= 1) {
            ConversationManager.get().speak(traveller, TheHush.END_POEM[seconds - 1]);
        }
        // Sculk gives way to stone in a widening sphere from the heart.
        int r = seconds * 4;
        for (BlockPos pos : BlockPos.betweenClosed(Quiet.HEART.offset(-r, -r, -r), Quiet.HEART.offset(r, r, r))) {
            if (pos.distSqr(Quiet.HEART) > (long) r * r) continue;
            BlockState st = quiet.getBlockState(pos);
            if (st.is(Blocks.SCULK) || st.is(Blocks.SCULK_SENSOR) || st.is(Blocks.SCULK_SHRIEKER) || st.is(Blocks.SCULK_CATALYST) || st.is(Blocks.SCULK_VEIN)) {
                quiet.setBlock(pos, st.is(Blocks.SCULK_VEIN) ? Blocks.AIR.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        if (seconds == 1) {
            for (UUID id : wardens) {
                if (quiet.getEntity(id) instanceof Warden w) {
                    w.setPose(Pose.DIGGING);
                    w.setNoAi(true);
                    quiet.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 3.0F, 1.0F);
                }
            }
            for (ServerPlayer p : quiet.players()) PacketDistributor.sendToPlayer(p, new HushSilencePayload(false));
        }
        if (seconds == 5) {
            for (UUID id : wardens) {
                Entity e = quiet.getEntity(id);
                if (e != null) e.discard();
            }
            wardens.clear();
        }
        if (seconds >= SOUND_SECONDS) {
            phase = Phase.DONE;
            chosen.removeEffect(MobEffects.DARKNESS);
            if (traveller != null) traveller.removeEffect(MobEffects.DARKNESS);
            cm.setFlag(server, "hush_answered");
            TheHushMod.LOGGER.info("The Hush: answered");
        }
    }

    // ---- inputs ----

    /** A bell rung anywhere in the throat pulls the wardens; the pedestal bell in the Answer ends it. */
    public void onBellRung(MinecraftServer server, ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!Quiet.isQuiet(level) || !running()) return;
        if (!Quiet.THROAT.isInside(pos) && !pos.equals(Quiet.HEART_BELL)) return;
        bellRings++;
        if (phase == Phase.ANSWER && pos.equals(Quiet.HEART_BELL)) {
            phase = Phase.SOUND;
            seconds = 0;
            AiVillagerEntity t = CampaignManager.get().traveller(server, player);
            if (t != null) {
                t.stopLeading();
                restoreHealth(t);
            }
            TheHushMod.LOGGER.info("The Hush: Sound");
            return;
        }
        if (phase == Phase.CALLING || phase == Phase.LISTENING) pull(level, pos, 8);
    }

    public void onNoteBlock(ServerLevel level, BlockPos pos) {
        if (!Quiet.isQuiet(level) || !running()) return;
        if (Quiet.THROAT.inflatedBy(2).isInside(pos)) pull(level, pos, 4);
    }

    /** Give the wardens something else to hear for a few seconds: an invisible, silent, very tough bird at the bell. */
    private void pull(ServerLevel level, BlockPos at, int secondsOfPull) {
        dropMarker(level);
        Chicken c = EntityTypes.CHICKEN.create(level, EntitySpawnReason.EVENT);
        if (c == null) return;
        c.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0F, 0F);
        c.setNoAi(true);
        c.setSilent(true);
        c.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 30, 0, false, false));
        var hp = c.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1024.0);
        c.setHealth(1024F);
        if (level.addFreshEntity(c)) {
            marker = c.getUUID();
            markerSeconds = secondsOfPull;
            retarget(level, c);
            for (UUID id : wardens) {
                if (level.getEntity(id) instanceof Warden w) w.increaseAngerAt(c, 150, false);
            }
        }
    }

    private @Nullable LivingEntity markerEntity(ServerLevel level) {
        return marker == null ? null : level.getEntity(marker) instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    private void dropMarker(ServerLevel level) {
        if (marker != null) {
            Entity e = level.getEntity(marker);
            if (e != null) e.discard();
            marker = null;
        }
        markerSeconds = 0;
    }

    // ---- helpers ----

    private boolean sensorActiveNear(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 3, r))) {
            if (!Quiet.THROAT.isInside(pos)) continue;
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.SCULK_SENSOR) && SculkSensorBlock.getPhase(st) == SculkSensorPhase.ACTIVE) return true;
        }
        return false;
    }

    private void lightAllSensors(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(Quiet.THROAT.minX(), Quiet.THROAT.minY(), Quiet.THROAT.minZ(), Quiet.THROAT.maxX(), Quiet.THROAT.minY(), Quiet.THROAT.maxZ())) {
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.SCULK_SENSOR) && SculkSensorBlock.canActivate(st)) {
                ((SculkSensorBlock) st.getBlock()).activate(null, level, pos, st, 15, 15);
            }
        }
    }

    private void shriek(ServerLevel level) {
        BlockPos h = Quiet.HEART;
        level.playSound(null, h.getX(), h.getY() + 2, h.getZ(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 4.0F, 0.7F);
        level.sendParticles(new net.minecraft.core.particles.ShriekParticleOption(0), h.getX() + 0.5, h.getY() + 3, h.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private void spawnWarden(ServerLevel level, ServerPlayer chosen) {
        BlockPos best = null;
        double bestD = -1;
        for (BlockPos p : Quiet.WARDEN_SPAWNS) {
            double d = p.distSqr(chosen.blockPosition());
            if (d > bestD && d > 36) {
                bestD = d;
                best = p;
            }
        }
        if (best == null) best = Quiet.WARDEN_SPAWNS.get(0);
        Warden w = EntityTypes.WARDEN.create(level, EntitySpawnReason.TRIGGERED);
        if (w == null) return;
        w.snapTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 0F, 0F);
        w.setPersistenceRequired();
        if (level.addFreshEntity(w)) {
            wardens.add(w.getUUID());
            w.setPose(Pose.EMERGING);
            level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 3.0F, 1.0F);
            w.increaseAngerAt(chosen, 20, false);
        }
    }

    private void retarget(ServerLevel level, LivingEntity target) {
        for (UUID id : wardens) {
            if (level.getEntity(id) instanceof Warden w && w.canTargetEntity(target)) w.setAttackTarget(target);
        }
    }
}
