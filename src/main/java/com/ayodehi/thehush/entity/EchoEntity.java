package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.TheHushMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/**
 * The Echo: one of the Hush's hunters, sent after whoever the road has chosen. It is blind. It throws
 * clicks out into the dark and listens to what comes back, and it hears feet, breath, and the scrape of
 * a sword being drawn. It does not come at once. It keeps its distance, out of sight, circling, for a
 * few minutes, moving its post when the player gets close or turns to look; then it shrieks and comes
 * all at once, and it does not stop until one of them is dead. There is no outrunning it and no hiding
 * from it for long. Balanced for iron armour and an iron sword: fifty health, seven damage, a blow every
 * second and a half; each hunt after the first is a little tougher, up to a cap.
 */
public class EchoEntity extends Monster {
    private static final EntityDataAccessor<Boolean> DATA_STRIKING = SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.BOOLEAN);

    public static final double BASE_HEALTH = 50.0;
    public static final double BASE_DAMAGE = 7.0;
    public static final double HEALTH_PER_HUNT = 5.0;
    public static final double DAMAGE_PER_HUNT = 0.5;
    public static final int SCALING_CAP = 4;
    public static final int ATTACK_INTERVAL_TICKS = 30;

    private static final int STALK_MIN_TICKS = 20 * 90;
    private static final int STALK_MAX_TICKS = 20 * 240;
    private static final double POST_MIN = 18.0;
    private static final double POST_MAX = 28.0;
    private static final double TOO_CLOSE = 14.0;
    private static final int WINDUP_TICKS = 30;
    private static final int REMEMBER_TICKS = 20 * 20;
    private static final int LOST_TICKS = 20 * 30;
    private static final int STRIKE_GIVE_CHASE_TICKS = 20 * 15;
    private static final double STRIKE_LOST_DISTANCE = 64.0;

    enum Phase { STALK, WINDUP, STRIKE }

    private Phase phase = Phase.STALK;
    private @Nullable UUID prey;
    /** Placed by the hunt scheduler (as opposed to a spawn egg); the scheduler is told when it dies or fades. */
    private boolean managed;
    /** Which hunt this is, for scaling; 1 is the first. */
    private int hunt = 1;
    private int stalkTicks = STALK_MIN_TICKS;
    private int windupTicks;
    private int pingTimer = 60;
    /** Where it last heard the prey and how long ago. */
    private @Nullable Vec3 lastKnown;
    private int sinceHeard = Integer.MAX_VALUE / 2;
    private int lostTicks;
    private int strikeFarTicks;
    private int strikeStuckTicks;
    private @Nullable Vec3 lastPreyPos;

    public EchoEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 40;
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, BASE_DAMAGE)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STRIKING, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new StrikeGoal());
        goalSelector.addGoal(2, new StalkGoal());
    }

    // ---- setup ----

    /** Point it at someone. {@code hunt} scales it; managed hunters report back to the scheduler. */
    public void beginHunt(ServerPlayer player, int hunt, boolean managed) {
        this.prey = player.getUUID();
        this.hunt = Math.max(1, hunt);
        this.managed = managed;
        this.phase = Phase.STALK;
        this.stalkTicks = STALK_MIN_TICKS + random.nextInt(STALK_MAX_TICKS - STALK_MIN_TICKS);
        this.lastKnown = player.position();
        this.sinceHeard = 0;
        applyScaling();
    }

    private void applyScaling() {
        int steps = Math.min(SCALING_CAP, hunt - 1);
        var hp = getAttribute(Attributes.MAX_HEALTH);
        var dmg = getAttribute(Attributes.ATTACK_DAMAGE);
        if (hp != null) hp.setBaseValue(BASE_HEALTH + HEALTH_PER_HUNT * steps);
        if (dmg != null) dmg.setBaseValue(BASE_DAMAGE + DAMAGE_PER_HUNT * steps);
        setHealth(getMaxHealth());
    }

    public boolean isStriking() {
        return entityData.get(DATA_STRIKING);
    }

    public boolean isManaged() {
        return managed;
    }

    public int hunt() {
        return hunt;
    }

    public @Nullable UUID preyId() {
        return prey;
    }

    public String describe() {
        return phase.name().toLowerCase() + (phase == Phase.STALK ? " (" + stalkTicks / 20 + "s to the strike)" : "")
                + ", health " + (int) getHealth() + "/" + (int) getMaxHealth()
                + ", last heard " + (lastKnown == null ? "never" : sinceHeard / 20 + "s ago");
    }

    private @Nullable ServerPlayer preyPlayer() {
        if (level().getServer() == null) return null;
        if (prey == null) {
            // A wild one (spawn egg) takes the nearest player it can find.
            Player p = level().getNearestPlayer(this, 64.0);
            if (p instanceof ServerPlayer sp && !sp.isSpectator() && !sp.isCreative()) prey = sp.getUUID();
            if (prey == null) return null;
        }
        ServerPlayer p = level().getServer().getPlayerList().getPlayer(prey);
        return p == null || p.isSpectator() || p.isCreative() || !p.isAlive() ? null : p;
    }

    // ---- persistence: it never despawns ----

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (prey != null) output.putString("thehush_prey", prey.toString());
        output.putBoolean("thehush_managed", managed);
        output.putInt("thehush_hunt", hunt);
        output.putInt("thehush_phase", phase.ordinal());
        output.putInt("thehush_stalk", stalkTicks);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String id = input.getStringOr("thehush_prey", "");
        try {
            prey = id.isEmpty() ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            prey = null;
        }
        managed = input.getBooleanOr("thehush_managed", false);
        hunt = input.getIntOr("thehush_hunt", 1);
        int ph = input.getIntOr("thehush_phase", 0);
        phase = ph >= 0 && ph < Phase.values().length ? Phase.values()[ph] : Phase.STALK;
        if (phase == Phase.WINDUP) phase = Phase.STALK;
        stalkTicks = input.getIntOr("thehush_stalk", STALK_MIN_TICKS);
        entityData.set(DATA_STRIKING, phase == Phase.STRIKE);
        int steps = Math.min(SCALING_CAP, hunt - 1);
        var hp = getAttribute(Attributes.MAX_HEALTH);
        var dmg = getAttribute(Attributes.ATTACK_DAMAGE);
        if (hp != null) hp.setBaseValue(BASE_HEALTH + HEALTH_PER_HUNT * steps);
        if (dmg != null) dmg.setBaseValue(BASE_DAMAGE + DAMAGE_PER_HUNT * steps);
    }

    // ---- ticking ----

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        ServerLevel level = (ServerLevel) level();
        ServerPlayer p = preyPlayer();
        if (p == null || p.level() != level) {
            // The one it hunts is gone from this world: it goes back into the dark until the next is sent.
            if (managed) {
                if (++lostTicks > 100) fade(level);
            } else if (prey != null && ++lostTicks > LOST_TICKS) {
                prey = null;
                lostTicks = 0;
            }
            return;
        }
        lostTicks = 0;
        listen(level, p);
        ping(level, p);
        switch (phase) {
            case STALK -> tickStalk(level, p);
            case WINDUP -> tickWindup(level, p);
            case STRIKE -> tickStrike(level, p);
        }
    }

    /** It hears feet that are not careful, and breath that is close. */
    private void listen(ServerLevel level, ServerPlayer p) {
        sinceHeard++;
        Vec3 now = p.position();
        boolean moving = lastPreyPos != null && lastPreyPos.subtract(now).horizontalDistanceSqr() > 0.0004;
        lastPreyPos = now;
        double range = Sonar.hearingRange(p.isSprinting(), p.isCrouching(), moving, !p.onGround() && !p.isInWater(), hasLineOfSight(p));
        if (range > 0 && distanceToSqr(p) <= range * range) heard(now);
    }

    private void heard(Vec3 where) {
        lastKnown = where;
        sinceHeard = 0;
    }

    /** Its clicks, thrown out and listened for: the player hears where it is; it learns where they are. */
    private void ping(ServerLevel level, ServerPlayer p) {
        if (--pingTimer > 0) return;
        boolean striking = phase != Phase.STALK;
        pingTimer = striking ? 40 : 160 + random.nextInt(80);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.DOLPHIN_AMBIENT_WATER, SoundSource.HOSTILE,
                striking ? 2.5F : 2.0F, striking ? 0.75F : 0.45F + random.nextFloat() * 0.1F);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getEyeY(), getZ(), 6, 0.4, 0.3, 0.4, 0.0);
        if (distanceToSqr(p) <= Sonar.PING_RANGE * Sonar.PING_RANGE) heard(p.position());
    }

    private void tickStalk(ServerLevel level, ServerPlayer p) {
        if (stalkTicks > 0) stalkTicks--;
        if (lastKnown != null && sinceHeard > REMEMBER_TICKS) lastKnown = null;
        if (lastKnown == null) {
            // It has lost them. It does not give up; after a while it is simply nearer again, unseen.
            if (++lostTicks > LOST_TICKS) {
                lostTicks = 0;
                Vec3 spot = hidingSpot(level, this, p.position(), POST_MIN, POST_MAX, p);
                if (spot != null) {
                    snapTo(spot.x, spot.y, spot.z, getYRot(), getXRot());
                    getNavigation().stop();
                }
                heard(p.position());
            }
            return;
        }
        lostTicks = 0;
        if (stalkTicks <= 0 && sinceHeard < 100 && distanceToSqr(p) < Sonar.PING_RANGE * Sonar.PING_RANGE) {
            beginWindup(level, p);
        }
    }

    /** It stops, lifts its head, and shrieks. A second and a half to get the sword up. */
    private void beginWindup(ServerLevel level, ServerPlayer p) {
        phase = Phase.WINDUP;
        windupTicks = WINDUP_TICKS;
        entityData.set(DATA_STRIKING, true);
        getNavigation().stop();
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 3.0F, 0.5F);
        level.sendParticles(ParticleTypes.SONIC_BOOM, getX(), getEyeY(), getZ(), 1, 0, 0, 0, 0);
        TheHushMod.LOGGER.info("The Echo strikes at {}", p.getName().getString());
    }

    private void tickWindup(ServerLevel level, ServerPlayer p) {
        getNavigation().stop();
        getLookControl().setLookAt(p);
        if (--windupTicks <= 0) {
            phase = Phase.STRIKE;
            strikeFarTicks = 0;
            strikeStuckTicks = 0;
            setTarget(p);
        }
    }

    private void tickStrike(ServerLevel level, ServerPlayer p) {
        if (getTarget() != p && tickCount % 10 == 0) setTarget(p);
        double d = distanceTo(p);
        // No outrunning it: a chase it cannot win on foot ends with it beside them again.
        strikeFarTicks = d > STRIKE_LOST_DISTANCE ? strikeFarTicks + 1 : 0;
        strikeStuckTicks = d > 6 && (getNavigation().isStuck() || getNavigation().isDone()) ? strikeStuckTicks + 1 : 0;
        if (strikeFarTicks > STRIKE_GIVE_CHASE_TICKS || strikeStuckTicks > 20 * 8) {
            strikeFarTicks = 0;
            strikeStuckTicks = 0;
            Vec3 spot = hidingSpot(level, this, p.position(), 6.0, 10.0, p);
            if (spot == null) spot = hidingSpot(level, this, p.position(), 3.0, 10.0, null);
            if (spot != null) {
                snapTo(spot.x, spot.y, spot.z, getYRot(), getXRot());
                getNavigation().stop();
                level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.DOLPHIN_AMBIENT_WATER, SoundSource.HOSTILE, 2.5F, 0.75F);
            }
        }
    }

    /** Hit it while it circles and it stops circling. Whoever hits it becomes the one it hunts. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && source.getEntity() instanceof ServerPlayer sp && !sp.isSpectator()) {
            if (!managed) prey = sp.getUUID();
            heard(sp.position());
            if (phase == Phase.STALK && sp.getUUID().equals(prey)) beginWindup(level, sp);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide() && level().getServer() != null) EchoHunts.onEchoDied(level().getServer(), this);
    }

    /** Back into the dark, with nothing left behind. */
    public void fade(ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.0, getZ(), 30, 0.4, 0.9, 0.4, 0.02);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.4F);
        discard();
        if (level.getServer() != null) EchoHunts.onEchoGone(level.getServer(), this);
    }

    // ---- sounds ----

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return null; // it is heard only when it clicks
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENDERMAN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENDERMAN_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.5F + random.nextFloat() * 0.1F;
    }

    // ---- sight helpers (it has none; the player does) ----

    /** Is this spot inside the player's view, with a clear line to it? */
    static boolean seenBy(ServerLevel level, ServerPlayer p, Vec3 spot) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        if (!ViewCone.contains(look.x, look.y, look.z, spot.x - eye.x, spot.y - eye.y, spot.z - eye.z, ViewCone.DEFAULT_THRESHOLD)) return false;
        HitResult hit = level.clip(new ClipContext(eye, spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(spot) < 1.5;
    }

    /**
     * A walkable spot between minD and maxD blocks of a point, out of hideFrom's view when one is given,
     * dark for preference. Null when none can be found.
     */
    static @Nullable Vec3 hidingSpot(ServerLevel level, Mob self, Vec3 around, double minD, double maxD, @Nullable ServerPlayer hideFrom) {
        var random = level.getRandom();
        Vec3 best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < 36; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minD + random.nextDouble() * (maxD - minD);
            double x = around.x + Math.cos(angle) * dist;
            double z = around.z + Math.sin(angle) * dist;
            BlockPos column = BlockPos.containing(x, around.y, z);
            if (!level.isLoaded(column)) continue;
            for (int dy = 6; dy >= -10; dy--) {
                BlockPos c = column.offset(0, dy, 0);
                if (WalkNodeEvaluator.getPathTypeStatic(self, c) != PathType.WALKABLE) continue;
                Vec3 spot = new Vec3(x, c.getY(), z);
                if (!level.noCollision(self, self.getBoundingBox().move(spot.subtract(self.position())))) break;
                if (hideFrom != null && seenBy(level, hideFrom, spot.add(0, self.getEyeHeight(), 0))) break;
                int score = -level.getBrightness(LightLayer.BLOCK, c) - (level.canSeeSky(c) && level.isBrightOutside() ? 4 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = spot;
                }
                break;
            }
        }
        return best;
    }

    // ---- goals ----

    /** The strike: melee, following the target whether or not it can be seen, a blow every second and a half. */
    private final class StrikeGoal extends MeleeAttackGoal {
        StrikeGoal() {
            super(EchoEntity.this, 1.25, true);
        }

        @Override
        public boolean canUse() {
            return phase == Phase.STRIKE && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return phase == Phase.STRIKE && super.canContinueToUse();
        }

        @Override
        protected void resetAttackCooldown() {
            super.resetAttackCooldown();
            cooldownOverride = adjustedTickDelay(ATTACK_INTERVAL_TICKS);
        }

        private int cooldownOverride;

        @Override
        protected boolean isTimeToAttack() {
            return cooldownOverride <= 0 && super.isTimeToAttack();
        }

        @Override
        public void tick() {
            if (cooldownOverride > 0) cooldownOverride--;
            super.tick();
        }

        @Override
        protected boolean canPerformAttack(LivingEntity target) {
            // Blind: no line-of-sight check, only reach.
            return isTimeToAttack() && mob.isWithinMeleeAttackRange(target);
        }
    }

    /** The stalk: hold a post out of sight at a distance, move it when they come close or turn to look. */
    private final class StalkGoal extends Goal {
        private @Nullable Vec3 post;
        private int postTimer;
        private int seenTicks;
        private int stuckTicks;

        StalkGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return phase == Phase.STALK && lastKnown != null && preyPlayer() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            post = null;
            getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerLevel level = (ServerLevel) level();
            ServerPlayer p = preyPlayer();
            if (p == null || lastKnown == null) return;
            double toPrey = distanceTo(p);
            boolean seen = seenBy(level, p, getEyePosition());
            seenTicks = seen ? seenTicks + 1 : 0;
            if (postTimer > 0) postTimer--;
            boolean needPost = post == null || postTimer <= 0 || toPrey < TOO_CLOSE || seenTicks > 40
                    || lastKnown.distanceTo(post) > POST_MAX + 6;
            if (needPost) {
                Vec3 next = hidingSpot(level, EchoEntity.this, lastKnown, POST_MIN, POST_MAX, p);
                if (next != null) {
                    post = next;
                    postTimer = 300 + random.nextInt(300);
                    seenTicks = 0;
                    stuckTicks = 0;
                    getNavigation().moveTo(post.x, post.y, post.z, 1.0);
                } else if (toPrey < TOO_CLOSE) {
                    // Nowhere to go: back away from them anyway.
                    Vec3 away = position().subtract(p.position()).normalize().scale(8);
                    getNavigation().moveTo(getX() + away.x, getY(), getZ() + away.z, 1.1);
                }
            } else if (getNavigation().isDone() && post != null && position().distanceToSqr(post) > 4 && tickCount % 20 == 0) {
                getNavigation().moveTo(post.x, post.y, post.z, 1.0);
            }
            if (post != null && (getNavigation().isStuck() || (getNavigation().isDone() && position().distanceToSqr(post) > 9))) {
                if (++stuckTicks > 120 && toPrey > TOO_CLOSE && !seenBy(level, p, post.add(0, getEyeHeight(), 0))) {
                    // It cannot walk there; it is there anyway. Nobody saw it arrive.
                    snapTo(post.x, post.y, post.z, getYRot(), getXRot());
                    getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            // It keeps its blind face turned toward what it hears.
            getLookControl().setLookAt(lastKnown.x, lastKnown.y + 1.5, lastKnown.z);
        }
    }
}
