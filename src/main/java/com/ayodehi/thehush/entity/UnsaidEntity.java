package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.conversation.ConversationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The Unsaid: what is left of the ones who crossed the frame and turned back. Grey, hooded, in the
 * Traveller's own shape, drifting above the soul sand of the Nether, where the noise goes. They whisper:
 * names from the register, fragments of what he has never said, and, worst, things the player said to
 * him. They want him more than the player; a touch on him takes a note from his memory. A touch on the
 * player hurts and gives one of their own lines back. They stop to listen the moment anyone speaks in
 * chat within earshot: your voice is the one thing that holds them. Steel ends them.
 */
public class UnsaidEntity extends Monster {
    private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(UnsaidEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_LISTENING = SynchedEntityData.defineId(UnsaidEntity.class, EntityDataSerializers.BOOLEAN);
    public static final int VARIANTS = 3;

    private static final double HEAR_CHAT_RANGE = 24.0;
    private static final double WHISPER_RANGE = 24.0;
    private static final int LISTEN_TICKS = 90;
    private static final double TOUCH_DISTANCE = 1.7;
    private static final int TOUCH_COOLDOWN = 100;
    private static final int TOUCH_HIM_COOLDOWN = 400;

    /** Those who crossed, from the register, for the pages they leave and the names they say. */
    static final String[] NAMES = {"Anselm of the Low Wells", "Hesper", "Corvin", "Ilse", "Tamsin"};
    static final String[] WHISPERS = {
            "...crossed...", "...did not come back...", "...the frame remembers...", "...Ves...", "...not again...",
            "...we built the wells shallow...", "...the door remembers me...", "...it listens...", "...we sent one...",
            "...we did not send him alone...", "...say it...", "...say my name...", "...the water carries sound...",
            "...he was here...", "...he turned back too...", "...it is happening again...", "...who did you leave...",
            "...lit from the far side...", "...quieter, now..."
    };

    private int listenTicks;
    private int whisperTimer = 200;
    private int touchCooldown;
    private int touchHimCooldown;
    private static long lastTouchBeatTick = Long.MIN_VALUE / 2;

    public UnsaidEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.moveControl = new FlyingMoveControl<>(this, 20, true);
        this.xpReward = 8;
        setNoGravity(true);
        entityData.set(DATA_VARIANT, random.nextInt(VARIANTS));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.FLYING_SPEED, 0.14)
                .add(Attributes.MOVEMENT_SPEED, 0.14)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, 0);
        builder.define(DATA_LISTENING, false);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    public void travel(Vec3 input) {
        travelFlying(input, getSpeed());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new DriftGoal());
    }

    public int variant() {
        return entityData.get(DATA_VARIANT);
    }

    public boolean isListening() {
        return entityData.get(DATA_LISTENING);
    }

    // ---- persistence ----

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("thehush_variant", variant());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(DATA_VARIANT, Math.floorMod(input.getIntOr("thehush_variant", 0), VARIANTS));
    }

    // ---- ticking ----

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        ServerLevel level = (ServerLevel) level();
        if (touchCooldown > 0) touchCooldown--;
        if (touchHimCooldown > 0) touchHimCooldown--;
        if (listenTicks > 0 && --listenTicks == 0) entityData.set(DATA_LISTENING, false);
        if (tickCount % 6 == 0) {
            level.sendParticles(random.nextInt(3) == 0 ? ParticleTypes.SOUL : ParticleTypes.ASH,
                    getX(), getY() + 0.4 + random.nextDouble() * 1.4, getZ(), 1, 0.25, 0.3, 0.25, 0.0);
        }
        if (--whisperTimer <= 0) {
            whisperTimer = 200 + random.nextInt(240);
            if (listenTicks == 0) whisper(level);
        }
    }

    /** Someone spoke in chat. Every one of them within earshot stops and turns toward the voice. */
    public static void onSomeoneSpoke(ServerPlayer speaker) {
        if (!(speaker.level() instanceof ServerLevel level)) return;
        for (UnsaidEntity u : level.getEntitiesOfClass(UnsaidEntity.class, speaker.getBoundingBox().inflate(HEAR_CHAT_RANGE), LivingEntity::isAlive)) {
            u.listen(speaker);
        }
    }

    private void listen(ServerPlayer speaker) {
        listenTicks = LISTEN_TICKS;
        entityData.set(DATA_LISTENING, true);
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        getLookControl().setLookAt(speaker);
    }

    /** A line into the dark: a name, a fragment, or the player's own words. Unattributed. */
    private void whisper(ServerLevel level) {
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!p.isSpectator() && p.distanceToSqr(this) <= WHISPER_RANGE * WHISPER_RANGE) near.add(p);
        if (near.isEmpty()) return;
        String line;
        int roll = random.nextInt(10);
        List<String> theirs = playersOwnWords(level, near.get(random.nextInt(near.size())));
        if (roll < 4 && !theirs.isEmpty()) line = "..." + theirs.get(random.nextInt(theirs.size())) + "...";
        else if (roll < 6) line = "..." + NAMES[random.nextInt(NAMES.length)] + "...";
        else line = WHISPERS[random.nextInt(WHISPERS.length)];
        Component msg = Component.literal(line).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        for (ServerPlayer p : near) p.sendSystemMessage(msg);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 0.6F, 0.4F + random.nextFloat() * 0.2F);
    }

    /** What the player has said to the Traveller, from his conversation with them, or failing that his notes about them. */
    private static List<String> playersOwnWords(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        List<String> out = new ArrayList<>();
        AiVillagerEntity t = CampaignManager.get().traveller(server, player);
        if (t == null) return out;
        var engine = t.engine();
        if (engine != null) out.addAll(engine.recentUserLines(12));
        if (out.isEmpty()) {
            for (var n : t.memory().notesAbout(player.getUUID())) if (n.text.length() <= 80) out.add(n.text);
        }
        return out;
    }

    // ---- the touch ----

    private void touchPlayer(ServerLevel level, ServerPlayer p) {
        touchCooldown = TOUCH_COOLDOWN;
        p.hurtServer(level, level.damageSources().mobAttack(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE));
        level.sendParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 1.2, p.getZ(), 10, 0.3, 0.4, 0.3, 0.02);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.0F, 0.3F);
        List<String> theirs = playersOwnWords(level, p);
        if (!theirs.isEmpty()) {
            p.sendSystemMessage(Component.literal("..." + theirs.get(random.nextInt(theirs.size())) + "...")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }

    private void touchHim(ServerLevel level, AiVillagerEntity t) {
        touchHimCooldown = TOUCH_HIM_COOLDOWN;
        MinecraftServer server = level.getServer();
        ServerPlayer chosen = CampaignManager.get().chosen(server);
        ServerPlayer nearest = chosen != null && chosen.level() == level ? chosen : level.getNearestPlayer(this, 32.0) instanceof ServerPlayer sp ? sp : null;
        level.sendParticles(ParticleTypes.SOUL, t.getX(), t.getY() + 1.2, t.getZ(), 16, 0.3, 0.5, 0.3, 0.03);
        level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.0F, 0.3F);
        if (nearest != null) {
            var taken = t.memory().takeRandomNoteAbout(nearest.getUUID(), random::nextInt);
            if (taken != null) TheHushMod.LOGGER.info("One of the Unsaid took a note about {} from {}", nearest.getName().getString(), t.speakerName());
            long now = level.getGameTime();
            if (now - lastTouchBeatTick > 20L * 60L * 3L && CampaignManager.get().active(server)) {
                lastTouchBeatTick = now;
                CampaignManager.get().queueBeat(server, "One of the grey things has just passed through you and taken something with it; you "
                        + "cannot say what. You know these: they are what is left of the ones who crossed the frame and turned back, and "
                        + "one of them was yours. Say, shaken and short, that " + nearest.getName().getString() + " must not let them "
                        + "reach you, that their voice holds them, and no more than that.", true);
            }
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level().isClientSide() || !(level() instanceof ServerLevel level)) return;
        String name = NAMES[random.nextInt(NAMES.length)];
        ItemStack page = new ItemStack(Items.PAPER);
        page.set(DataComponents.CUSTOM_NAME, Component.literal("A page: " + name + ", crossed, and turned back").withStyle(ChatFormatting.GRAY));
        spawnAtLocation(level, page);
        level.sendParticles(ParticleTypes.SOUL, getX(), getY() + 1.0, getZ(), 24, 0.3, 0.6, 0.3, 0.05);
        MinecraftServer server = level.getServer();
        if (source.getEntity() instanceof ServerPlayer killer && CampaignManager.get().active(server)
                && level.getGameTime() - lastTouchBeatTick > 20L * 60L * 3L) {
            lastTouchBeatTick = level.getGameTime();
            CampaignManager.get().queueBeat(server, killer.getName().getString() + " has just cut down one of the grey things and it "
                    + "came apart into ash and a page with a name on it. Say, low, that it had a name once, that it crossed and could "
                    + "not bear to go on, and that you will not say whether you knew it. One line, then quiet.", true);
        }
    }

    // ---- sounds ----

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return null; // it whispers in chat instead
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VEX_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SOUL_ESCAPE.value();
    }

    @Override
    public float getVoicePitch() {
        return 0.4F + random.nextFloat() * 0.1F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // it does not touch the ground
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    // ---- goals ----

    /** Drift toward him if he is here, else toward the nearest player; touch what it reaches; wander when alone. */
    private final class DriftGoal extends Goal {
        private int wanderTimer;

        DriftGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return true;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerLevel level = (ServerLevel) level();
            if (listenTicks > 0) {
                getNavigation().stop();
                return;
            }
            AiVillagerEntity him = CampaignManager.get().traveller(level.getServer(), null);
            if (him != null && (him.level() != level || him.distanceToSqr(UnsaidEntity.this) > 48 * 48)) him = null;
            ServerPlayer p = null;
            for (ServerPlayer c : level.players()) {
                if (c.isSpectator() || c.isCreative() || !c.isAlive() || c.distanceToSqr(UnsaidEntity.this) > 32 * 32) continue;
                if (p == null || c.distanceToSqr(UnsaidEntity.this) < p.distanceToSqr(UnsaidEntity.this)) p = c;
            }
            LivingEntity target = him != null && touchHimCooldown == 0 ? him : p;
            if (target == null) {
                if (--wanderTimer <= 0) {
                    wanderTimer = 80 + random.nextInt(80);
                    getNavigation().moveTo(getX() + (random.nextDouble() - 0.5) * 12, getY() + (random.nextDouble() - 0.5) * 4,
                            getZ() + (random.nextDouble() - 0.5) * 12, 1.0);
                }
                return;
            }
            getLookControl().setLookAt(target);
            double d = distanceTo(target);
            if (d <= TOUCH_DISTANCE) {
                if (target == him && touchHimCooldown == 0) touchHim(level, him);
                else if (target instanceof ServerPlayer sp && touchCooldown == 0) touchPlayer(level, sp);
                return;
            }
            if (tickCount % 10 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(target.getX(), target.getY() + 1.0, target.getZ(), 1.0);
            }
        }
    }
}
