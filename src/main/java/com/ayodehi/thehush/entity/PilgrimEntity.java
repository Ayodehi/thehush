package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignState;
import com.ayodehi.thehush.conversation.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * A hooded thing that only moves when no one is looking. Watched, it is stone: still, silent, unhurtable.
 * Unwatched, it comes in bursts. Its touch sends the player far away and takes one of the Traveller's
 * notes about them. Soul fire in view, or sunlight, crumbles it. It borrows the villager body and renderer
 * and nothing of the villager mind.
 */
public class PilgrimEntity extends Villager implements Skinned {
    public static final String SKIN = "thehush:textures/entity/pilgrim.png";
    private static final EntityDataAccessor<Boolean> DATA_FROZEN = SynchedEntityData.defineId(PilgrimEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_CRUMBLING = SynchedEntityData.defineId(PilgrimEntity.class, EntityDataSerializers.BOOLEAN);
    private static final double OBSERVE_RANGE = 48.0;
    private static final double HUNT_RANGE = 48.0;
    private static final int LUNGE_MIN_TICKS = 20 * 8;
    private static final int LUNGE_MAX_TICKS = 20 * 15;
    private static final double LUNGE_FROM = 10.0;
    private static final double LUNGE_TO = 40.0;
    private static final double TOUCH_DISTANCE = 1.5;
    private static final int CRUMBLE_TICKS = 60;
    private static final double SPEED = 0.6;
    /** Ticks of being unwatched before it may simply be closer. */
    private int lungeTimer = LUNGE_MIN_TICKS;
    /** Where it last knew the player to be, and for how much longer it will bother going there. */
    private @Nullable Vec3 lastKnown;
    private int lastKnownTicks;
    private static final double SEE_RANGE = 48.0;
    private static final double HEAR_RANGE = 16.0;
    private static final double HEAR_SPRINT_RANGE = 24.0;
    private static final int REMEMBER_TICKS = 200;

    private int crumbleTicks = -1;
    /** The grip: who it holds, for how much longer, and when it may take hold again. */
    private @Nullable UUID gripping;
    private int gripTicks;
    private int gripCooldown;
    private static final int GRIP_TICKS = 120;
    private static final int GRIP_COOLDOWN_TICKS = 200;
    /** How many times it is simply there behind you, close enough to touch, before it does. */
    private static final int SCARES_BEFORE_GRIP = 2;
    private static final int SCARE_STAND_TICKS = 70;
    private static final int UNWATCHED_BEFORE_TOUCH = 10;
    private int scares;
    private int scareStandTicks;
    private int unwatchedTicks;

    public PilgrimEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FROZEN, true);
        builder.define(DATA_CRUMBLING, false);
    }

    // ---- appearance and silence ----

    @Override
    public String skin() {
        return SKIN;
    }

    @Override
    public boolean animationFrozen() {
        return entityData.get(DATA_FROZEN) || entityData.get(DATA_CRUMBLING);
    }

    public boolean isFrozen() {
        return entityData.get(DATA_FROZEN);
    }

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) {
        return null;
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return null;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // silent
    }

    /** Of the Quiet: the sculk does not hear it either. */
    @Override
    public boolean dampensVibrations() {
        return true;
    }

    /** The villager base class names its kind by profession; ours are named by what they are. */
    @Override
    protected Component getTypeName() {
        return getType().getDescription();
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    /** Weapons do nothing. Only the void and /kill get through. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(level, source);
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    // ---- behaviour ----

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // No villager brain: nothing here schedules, trades, or sleeps.
        if (crumbleTicks >= 0) {
            tickCrumble(level);
            return;
        }
        if (inSunlight(level)) {
            startCrumble(level);
            return;
        }
        if (gripping != null) {
            tickGrip(level);
            return;
        }
        if (gripCooldown > 0) gripCooldown--;
        boolean observed = observed(level);
        unwatchedTicks = observed ? 0 : unwatchedTicks + 1;
        if (scareStandTicks > 0) {
            // After a scare it stands there, watched or not, long enough to be turned around on; then it is gone.
            entityData.set(DATA_FROZEN, true);
            getNavigation().stop();
            setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
            if (soulFireNear(level, blockPosition(), 2)) startCrumble(level);
            if (--scareStandTicks == 0 && !observed) slipAway(level);
            return;
        }
        entityData.set(DATA_FROZEN, observed);
        if (observed) {
            getNavigation().stop();
            setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
            if (soulFireNear(level, blockPosition(), 2)) startCrumble(level);
            return;
        }
        ServerPlayer prey = nearestPrey(level);
        if (prey == null) {
            // Nobody seen or heard: go where they were last known to be, then stand.
            if (lastKnown != null && lastKnownTicks-- > 0) {
                if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(lastKnown.x, lastKnown.y, lastKnown.z, SPEED);
                if (position().distanceToSqr(lastKnown) < 2.0) lastKnown = null;
            } else {
                lastKnown = null;
                getNavigation().stop();
            }
            return;
        }
        lastKnown = prey.position();
        lastKnownTicks = REMEMBER_TICKS;
        if (distanceTo(prey) <= TOUCH_DISTANCE && gripCooldown == 0 && unwatchedTicks >= UNWATCHED_BEFORE_TOUCH
                && !facing(prey) && hasLineOfSight(prey)) {
            // Only ever from behind, never through a door or a wall, and only once it has shown itself first.
            if (scares < SCARES_BEFORE_GRIP) scare(level, prey);
            else touch(level, prey);
            return;
        }
        if (tickCount % 5 == 0 || getNavigation().isDone()) {
            getNavigation().moveTo(prey, SPEED);
        }
        getLookControl().setLookAt(prey);
        if (--lungeTimer <= 0) {
            lungeTimer = LUNGE_MIN_TICKS + random.nextInt(LUNGE_MAX_TICKS - LUNGE_MIN_TICKS);
            double d = distanceTo(prey);
            if (d >= LUNGE_FROM && d <= LUNGE_TO) lunge(level, prey);
        }
    }

    /** Someone is looking: within range, line of sight, inside their view cone, and not blinded by darkness. */
    private boolean observed(ServerLevel level) {
        Vec3 me = getEyePosition();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(this) > OBSERVE_RANGE * OBSERVE_RANGE) continue;
            if (p.hasEffect(MobEffects.DARKNESS)) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getLookAngle();
            if (!ViewCone.contains(look.x, look.y, look.z, me.x - eye.x, me.y - eye.y, me.z - eye.z, ViewCone.DEFAULT_THRESHOLD)) continue;
            if (p.hasLineOfSight(this)) return true;
        }
        return false;
    }

    /** Is the player's face turned toward it, wall or no wall? It never takes hold of anyone looking its way. */
    private boolean facing(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 me = getEyePosition();
        return ViewCone.contains(look.x, look.y, look.z, me.x - eye.x, me.y - eye.y, me.z - eye.z, ViewCone.DEFAULT_THRESHOLD);
    }

    /**
     * The jump scare: it is suddenly right behind the player, a low sound at their ear, and it stands there
     * long enough to be turned around on. The first times it reaches them it only does this; the grip comes
     * after. Then it withdraws as after a touch.
     */
    private void scare(ServerLevel level, ServerPlayer prey) {
        Vec3 look = prey.getLookAngle();
        double len = Math.max(0.01, Math.sqrt(look.x * look.x + look.z * look.z));
        Vec3 behind = prey.position().add(-look.x / len * 1.8, 0, -look.z / len * 1.8);
        BlockPos at = BlockPos.containing(behind);
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos c = at.offset(0, dy, 0);
            if (WalkNodeEvaluator.getPathTypeStatic(this, c) != PathType.WALKABLE) continue;
            Vec3 spot = new Vec3(behind.x, c.getY(), behind.z);
            if (!level.noCollision(this, getBoundingBox().move(spot.subtract(position())))) continue;
            snapTo(spot.x, spot.y, spot.z, getYRot(), getXRot());
            break;
        }
        getNavigation().stop();
        getLookControl().setLookAt(prey);
        scares++;
        scareStandTicks = SCARE_STAND_TICKS;
        gripCooldown = GRIP_COOLDOWN_TICKS;
        entityData.set(DATA_FROZEN, true);
        level.playSound(null, prey.getX(), prey.getY() + 1.5, prey.getZ(), SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.7F, 0.4F);
        level.sendParticles(ParticleTypes.SCULK_SOUL, getX(), getY() + 1.0, getZ(), 6, 0.2, 0.4, 0.2, 0.01);
        TheHushMod.LOGGER.info("A Pilgrim has shown itself to {} ({} of {})", prey.getName().getString(), scares, SCARES_BEFORE_GRIP);
    }

    /** Into the dark, out of sight, eight to twelve blocks off, and still. */
    private void slipAway(ServerLevel level) {
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 8 + random.nextInt(5);
            BlockPos at = blockPosition().offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos c = at.offset(0, dy, 0);
                if (WalkNodeEvaluator.getPathTypeStatic(this, c) != PathType.WALKABLE) continue;
                Vec3 spot = new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                if (!level.noCollision(this, getBoundingBox().move(spot.subtract(position())))) continue;
                if (wouldBeSeenAt(level, spot)) continue;
                snapTo(spot.x, spot.y, spot.z, getYRot(), getXRot());
                getNavigation().stop();
                entityData.set(DATA_FROZEN, true);
                return;
            }
        }
        entityData.set(DATA_FROZEN, true);
    }

    /** Would standing at this spot put it inside some watching player's cone? Bursts never land in view. */
    private boolean wouldBeSeenAt(ServerLevel level, Vec3 spot) {
        Vec3 head = spot.add(0, getEyeHeight(), 0);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(spot) > OBSERVE_RANGE * OBSERVE_RANGE) continue;
            if (p.hasEffect(MobEffects.DARKNESS)) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getLookAngle();
            if (ViewCone.contains(look.x, look.y, look.z, head.x - eye.x, head.y - eye.y, head.z - eye.z, ViewCone.DEFAULT_THRESHOLD)) return true;
        }
        return false;
    }

    /** The nearest player it can see or hear. */
    private @Nullable ServerPlayer nearestPrey(ServerLevel level) {
        ServerPlayer best = null;
        double bestD = HUNT_RANGE * HUNT_RANGE;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue; // it watches them; it does not hunt them
            double d = p.distanceToSqr(this);
            if (d < bestD && awareOf(p, Math.sqrt(d))) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** It sees what is in its line of sight, and hears feet that are not careful. */
    private boolean awareOf(ServerPlayer p, double distance) {
        if (distance <= SEE_RANGE && hasLineOfSight(p)) return true;
        if (p.isSprinting() && distance <= HEAR_SPRINT_RANGE) return true;
        return !p.isCrouching() && distance <= HEAR_RANGE;
    }

    /** It is simply closer: four to six blocks from the player, where they are not looking, standing still. */
    private void lunge(ServerLevel level, ServerPlayer prey) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 4.0 + random.nextDouble() * 2.0;
            Vec3 spot = prey.position().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            BlockPos at = BlockPos.containing(spot);
            BlockPos ground = null;
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos c = at.offset(0, dy, 0);
                if (WalkNodeEvaluator.getPathTypeStatic(this, c) == PathType.WALKABLE) {
                    ground = c;
                    break;
                }
            }
            if (ground == null) continue;
            Vec3 landing = new Vec3(spot.x, ground.getY(), spot.z);
            if (!level.noCollision(this, getBoundingBox().move(landing.subtract(position())))) continue;
            if (wouldBeSeenAt(level, landing)) continue;
            snapTo(landing.x, landing.y, landing.z, getYRot(), getXRot());
            getNavigation().stop();
            getLookControl().setLookAt(prey);
            return;
        }
    }

    // ---- the touch ----

    /**
     * It takes hold. For six seconds the player cannot walk or jump, the dark comes down, and it bleeds
     * them a heart a second; anything else nearby gets its chance. Then it lets go and withdraws into the
     * dark, and can come again. Soul fire beside it ends the grip early.
     */
    private void touch(ServerLevel level, ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return;
        MinecraftServer server = level.getServer();
        gripping = player.getUUID();
        gripTicks = GRIP_TICKS;
        entityData.set(DATA_FROZEN, false);
        getNavigation().stop();
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 0.8F, 0.4F);
        level.sendParticles(ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1, player.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
        holdEffects(player, GRIP_TICKS + 10);
        AiVillagerEntity traveller = CampaignManager.get().traveller(server, player);
        if (traveller != null) {
            var taken = traveller.memory().takeRandomNoteAbout(player.getUUID(), random::nextInt);
            if (taken != null) TheHushMod.LOGGER.info("A Pilgrim took a note about {} from {}", player.getName().getString(), traveller.speakerName());
            if (CampaignManager.get().withinEarshot(traveller, player)) {
                ConversationManager.get().remark(traveller, player, "One of the hooded things has taken hold of "
                        + player.getName().getString() + " and they cannot move; it is bleeding them while it holds. Soul fire beside it is the only thing that makes it let go.");
            }
        }
        CampaignState s = CampaignManager.get().state(server);
        if (s.started()) {
            s.touches++;
            s.save();
        }
        TheHushMod.LOGGER.info("A Pilgrim has taken hold of {}", player.getName().getString());
    }

    private static void holdEffects(ServerPlayer player, int ticks) {
        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 10, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, ticks, 128, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0, false, false));
    }

    private void tickGrip(ServerLevel level) {
        ServerPlayer held = gripping == null ? null : level.getServer().getPlayerList().getPlayer(gripping);
        if (held == null || !held.isAlive() || held.level() != level || held.isSpectator() || held.isCreative() || held.distanceToSqr(this) > 9.0) {
            release(level, held, false);
            return;
        }
        if (soulFireNear(level, blockPosition(), 2)) {
            release(level, held, true);
            startCrumble(level);
            return;
        }
        setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
        getLookControl().setLookAt(held);
        held.setDeltaMovement(0.0, Math.min(0.0, held.getDeltaMovement().y), 0.0);
        held.hurtMarked = true;
        if (gripTicks % 20 == 0) {
            held.hurtServer(level, level.damageSources().magic(), 2.0F);
            level.playSound(null, held.getX(), held.getY(), held.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.0F, 0.6F);
            level.sendParticles(ParticleTypes.SCULK_SOUL, held.getX(), held.getY() + 1.2, held.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
        if (--gripTicks <= 0) release(level, held, false);
    }

    /** Let go: the player is weak for a while; it withdraws somewhere they cannot see, ready to come again. */
    private void release(ServerLevel level, @Nullable ServerPlayer held, boolean destroyed) {
        gripping = null;
        gripTicks = 0;
        gripCooldown = GRIP_COOLDOWN_TICKS;
        if (held != null) {
            held.removeEffect(MobEffects.SLOWNESS);
            held.removeEffect(MobEffects.JUMP_BOOST);
            held.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 0, false, false));
        }
        if (destroyed) return;
        slipAway(level);
    }

    // ---- persistence ----

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("thehush_scares", scares);
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        scares = input.getIntOr("thehush_scares", 0);
    }

    // ---- destruction ----

    private static boolean soulFireNear(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 2, r))) {
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH) || st.is(Blocks.SOUL_LANTERN)
                    || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.SOUL_CAMPFIRE)) return true;
        }
        return false;
    }

    private boolean inSunlight(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD || !level.isBrightOutside()) return false;
        BlockPos pos = blockPosition();
        return level.canSeeSky(pos) && level.getBrightness(LightLayer.SKY, pos) >= 15 && !level.isRaining();
    }

    public void startCrumble(ServerLevel level) {
        if (crumbleTicks >= 0) return;
        crumbleTicks = CRUMBLE_TICKS;
        entityData.set(DATA_CRUMBLING, true);
        entityData.set(DATA_FROZEN, true);
        getNavigation().stop();
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.0F, 0.6F);
    }

    private void tickCrumble(ServerLevel level) {
        setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
        if (crumbleTicks % 2 == 0) {
            double h = getBbHeight() * (crumbleTicks / (double) CRUMBLE_TICKS);
            level.sendParticles(ParticleTypes.WHITE_ASH, getX(), getY() + h, getZ(), 6, 0.3, 0.2, 0.3, 0.02);
            level.sendParticles(ParticleTypes.ASH, getX(), getY() + h * 0.5, getZ(), 4, 0.3, 0.3, 0.3, 0.01);
        }
        if (--crumbleTicks < 0) {
            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, getX(), getY() + 0.5, getZ(), 20, 0.4, 0.4, 0.4, 0.05);
            discard();
        }
    }

    // ---- placement helpers ----

    /** Spawn one at a walkable spot; returns it or null. */
    public static @Nullable PilgrimEntity place(ServerLevel level, BlockPos pos) {
        PilgrimEntity p = com.ayodehi.thehush.ModEntities.PILGRIM.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (p == null) return null;
        p.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
        p.setCustomNameVisible(false);
        if (!level.addFreshEntity(p)) return null;
        return p;
    }
}
