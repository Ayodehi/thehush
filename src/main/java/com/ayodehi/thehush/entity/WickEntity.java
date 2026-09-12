package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.ModBlocks;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;

/**
 * The Wick: the Hush's breath going ahead of it, a thing that eats light. It drifts through caves toward
 * torches and campfires, touches them, and they go out (a snuffed torch is left behind). It does its work
 * where nobody is looking, keeps to the dark, and runs from players when it can; cornered, or hurt, it
 * fights, and when it lands a blow it feeds on the torches you carry. Soul fire it cannot pass. Daylight
 * burns it. Average health, average bite; the danger is being left unlit in a cave.
 */
public class WickEntity extends Monster {
    private static final double SNUFF_RANGE = 24.0;
    private static final double FLEE_FROM_PLAYERS_WITHIN = 10.0;
    private static final int CORNERED_TICKS = 100;
    private static final int SNUFF_COOLDOWN_TICKS = 30;

    private int corneredTicks;
    private int snuffCooldown;
    /** Game time of the last "torches are going out" line to the Traveller, so he is not told every time. */
    private static long lastTravellerBeatTick = Long.MIN_VALUE / 2;

    public WickEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CorneredAttackGoal());
        goalSelector.addGoal(2, new FleeIntoDarkGoal());
        goalSelector.addGoal(3, new SnuffGoal());
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.7));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 4, true, false, (p, level) -> isCornered()));
    }

    public boolean isCornered() {
        return corneredTicks > 0;
    }

    // ---- ticking ----

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        if (corneredTicks > 0) corneredTicks--;
        if (snuffCooldown > 0) snuffCooldown--;
        ServerLevel level = (ServerLevel) level();
        // Daylight unmakes it.
        if (level.isBrightOutside() && level.canSeeSky(blockPosition()) && level.getBrightness(LightLayer.SKY, blockPosition()) >= 12 && random.nextInt(10) == 0) {
            igniteForSeconds(4.0F);
        }
        // Soul fire it cannot bear.
        if (tickCount % 20 == 0 && soulFireNear(level, blockPosition(), 2)) {
            hurtServer(level, level.damageSources().magic(), 1.0F);
        }
        if (tickCount % 10 == 0) {
            level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.6, getZ(), 1, 0.1, 0.1, 0.1, 0.0);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && source.getEntity() instanceof Player) corneredTicks = CORNERED_TICKS;
        return hurt;
    }

    /** A blow lands: it feeds on the torches they carry. */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof ServerPlayer player) {
            int eaten = 0;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize() && eaten < 16; i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.TORCH)) {
                    int take = Math.min(s.getCount(), 16 - eaten);
                    s.shrink(take);
                    eaten += take;
                }
            }
            if (eaten > 0) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 1.0F, 0.6F);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 12, 0.3, 0.4, 0.3, 0.02);
                heal(eaten / 4.0F);
            }
        }
        return hit;
    }

    // ---- light ----

    static boolean isArtificialLight(BlockState st) {
        return st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || (st.is(Blocks.CAMPFIRE) && CampfireBlock.isLitCampfire(st));
    }

    static boolean soulFireNear(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 2, r))) {
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH) || st.is(Blocks.SOUL_LANTERN)
                    || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.SOUL_CAMPFIRE)) return true;
        }
        return false;
    }

    /** Can any player nearby see this block? (In their view cone, with a clear line to it.) */
    static boolean anyPlayerSees(ServerLevel level, BlockPos pos, double range) {
        Vec3 target = Vec3.atCenterOf(pos);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(target) > range * range) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getLookAngle();
            if (!ViewCone.contains(look.x, look.y, look.z, target.x - eye.x, target.y - eye.y, target.z - eye.z, ViewCone.DEFAULT_THRESHOLD)) continue;
            HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 1.5) return true;
        }
        return false;
    }

    /** Put a light out: torch to snuffed torch, campfire dowsed. */
    void snuff(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.is(Blocks.TORCH)) {
            level.setBlock(pos, ModBlocks.SNUFFED_TORCH.get().defaultBlockState(), Block.UPDATE_ALL);
        } else if (st.is(Blocks.WALL_TORCH)) {
            level.setBlock(pos, ModBlocks.SNUFFED_WALL_TORCH.get().defaultBlockState()
                    .setValue(WallTorchBlock.FACING, st.getValue(WallTorchBlock.FACING)), Block.UPDATE_ALL);
        } else if (st.is(Blocks.CAMPFIRE) && CampfireBlock.isLitCampfire(st)) {
            CampfireBlock.dowse(this, level, pos, st);
        } else {
            return;
        }
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7F, 0.8F + random.nextFloat() * 0.3F);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 6, 0.1, 0.2, 0.1, 0.01);
        snuffCooldown = SNUFF_COOLDOWN_TICKS;
        heal(1.0F);
        tellTheTraveller(level, pos);
    }

    /** The Traveller notices, once in a while, that the lights behind the chosen are going out. */
    private void tellTheTraveller(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        if (now - lastTravellerBeatTick < 20L * 60L * 8L) return;
        var server = level.getServer();
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server)) return;
        ServerPlayer chosen = cm.chosen(server);
        if (chosen == null || chosen.level() != level || chosen.blockPosition().distSqr(pos) > 48 * 48) return;
        lastTravellerBeatTick = now;
        cm.queueBeat(server, "Somewhere behind " + chosen.getName().getString() + ", out of sight, a torch has just gone out, "
                + "and it is not the first. Something is walking their lights back to them. You know what it is and where "
                + "it comes from; you do not say. One line: put fire it cannot eat between you and it.", true);
    }

    // ---- sounds ----

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return SoundEvents.CAMPFIRE_CRACKLE;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.FIRE_EXTINGUISH;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GENERIC_EXTINGUISH_FIRE;
    }

    @Override
    protected float getSoundVolume() {
        return 0.5F;
    }

    // ---- goals ----

    /** Only when cornered or hurt does it stand and fight. */
    private final class CorneredAttackGoal extends MeleeAttackGoal {
        CorneredAttackGoal() {
            super(WickEntity.this, 1.1, true);
        }

        @Override
        public boolean canUse() {
            return isCornered() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return isCornered() && super.canContinueToUse();
        }
    }

    /** A player near and light on it: slip away to the darkest spot it can find. No way out marks it cornered. */
    private final class FleeIntoDarkGoal extends Goal {
        private @Nullable Vec3 refuge;
        private int stuck;

        FleeIntoDarkGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (isCornered()) return false;
            Player p = level().getNearestPlayer(WickEntity.this, FLEE_FROM_PLAYERS_WITHIN);
            if (p == null || p.isSpectator()) return false;
            ServerLevel level = (ServerLevel) level();
            boolean lit = level.getBrightness(LightLayer.BLOCK, blockPosition()) > 5;
            if (!lit && distanceTo(p) > 5) return false;
            refuge = findDark(level, p);
            if (refuge == null) {
                if (distanceTo(p) < 4) corneredTicks = CORNERED_TICKS;
                return false;
            }
            return true;
        }

        private @Nullable Vec3 findDark(ServerLevel level, Player from) {
            Vec3 best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < 24; i++) {
                BlockPos c = blockPosition().offset(random.nextInt(25) - 12, random.nextInt(7) - 3, random.nextInt(25) - 12);
                if (!level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir() || !level.getBlockState(c.below()).isSolid()) continue;
                int light = level.getBrightness(LightLayer.BLOCK, c);
                if (light > 3) continue;
                if (soulFireNear(level, c, 3)) continue;
                int away = (int) Math.sqrt(c.distSqr(from.blockPosition()));
                int score = away * 2 - light * 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = Vec3.atBottomCenterOf(c);
                }
            }
            return best;
        }

        @Override
        public void start() {
            stuck = 0;
            if (refuge != null) getNavigation().moveTo(refuge.x, refuge.y, refuge.z, 1.2);
        }

        @Override
        public boolean canContinueToUse() {
            return refuge != null && !getNavigation().isDone() && !isCornered() && stuck < 60;
        }

        @Override
        public void tick() {
            if (getNavigation().isStuck()) stuck += 10;
        }

        @Override
        public void stop() {
            refuge = null;
        }
    }

    /** Find a light nobody is looking at, go to it, and put it out. */
    private final class SnuffGoal extends Goal {
        private @Nullable BlockPos target;
        private int patience;

        SnuffGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (isCornered() || snuffCooldown > 0 || tickCount % 10 != 0) return false;
            target = findLight((ServerLevel) level());
            return target != null;
        }

        private @Nullable BlockPos findLight(ServerLevel level) {
            BlockPos me = blockPosition();
            int r = (int) SNUFF_RANGE;
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(me.offset(-r, -6, -r), me.offset(r, 6, r))) {
                if (!isArtificialLight(level.getBlockState(pos))) continue;
                double d = pos.distSqr(me);
                if (d >= bestD) continue;
                if (anyPlayerSees(level, pos, 40)) continue;
                if (soulFireNear(level, pos, 3)) continue;
                bestD = d;
                best = pos.immutable();
            }
            return best;
        }

        @Override
        public void start() {
            patience = 200;
            if (target != null) getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && patience > 0 && !isCornered() && isArtificialLight(level().getBlockState(target));
        }

        @Override
        public void tick() {
            if (target == null) return;
            patience--;
            getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            if (getNavigation().isDone() && tickCount % 20 == 0) {
                getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            }
            if (position().distanceToSqr(Vec3.atCenterOf(target)) <= 2.6) {
                ServerLevel level = (ServerLevel) level();
                if (anyPlayerSees(level, target, 40)) {
                    patience = 0; // someone looked; it will not do it in front of them
                    return;
                }
                snuff(level, target);
                target = null;
            }
        }

        @Override
        public void stop() {
            target = null;
            getNavigation().stop();
        }
    }
}
