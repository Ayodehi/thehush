package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.conversation.AmbientObserver;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.llm.ConversationEngine;
import com.ayodehi.thehush.llm.LlmProvider;
import com.ayodehi.thehush.llm.LlmService;
import com.ayodehi.thehush.memory.MemoryStore;
import com.ayodehi.thehush.memory.VillagerMemory;
import com.ayodehi.thehush.persona.Persona;
import com.ayodehi.thehush.persona.PersonaRegistry;
import com.ayodehi.thehush.persona.PromptBuilder;
import com.ayodehi.thehush.tools.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A villager that talks. Reuses the vanilla model, pathfinding, and schedule; replaces trading with
 * conversation. All conversation state lives here and is saved with the entity.
 */
public class AiVillagerEntity extends Villager implements Skinned {
    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(AiVillagerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_VOICE = SynchedEntityData.defineId(AiVillagerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_EYES_DARK = SynchedEntityData.defineId(AiVillagerEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_SITTING = SynchedEntityData.defineId(AiVillagerEntity.class, EntityDataSerializers.BOOLEAN);
    private static final String TAG_EYES_DARK = "thehush_eyes_dark";
    private static final String TAG_SEAT = "thehush_seat";
    /** Idle this long beside his seat and he sits down. */
    private static final int SIT_AFTER_TICKS = 60;
    private static final double SEAT_RADIUS = 1.6;
    private static final String TAG_PERSONA = "thehush_persona";
    private static final String TAG_HISTORY = "thehush_history";
    private static final String TAG_FOLLOWING = "thehush_following";
    private static final String TAG_FOLLOW_TARGET = "thehush_follow_target";
    private static final String TAG_POST = "thehush_post";
    /** Where he was told to stay, or null; while set he keeps within POST_RADIUS of it and his brain does not wander him off. */
    private @Nullable BlockPos post;
    private static final double POST_RADIUS = 5.0;
    /** Personal space: start walking past 7 blocks, stop again inside 4, back off if the player is within 2. */
    private static final double FOLLOW_START_SQ = 49.0;
    private static final double FOLLOW_STOP_SQ = 16.0;
    private static final double CROWDED_SQ = 4.0;
    private static final double JOG_START_SQ = 100.0;
    private static final double TELEPORT_SQ = 256.0;

    private String personaId = "";
    private @Nullable JsonArray pendingHistory;
    private @Nullable ConversationEngine engine;
    private @Nullable LlmProvider engineProvider;
    private @Nullable ServerPlayer talkingTo;
    /** Set while answering a line the player typed; unprompted remarks don't show a thinking indicator. */
    private @Nullable ServerPlayer awaitingReplyFor;
    /** Where he is leading the player, or null. Suspends following while set. */
    private @Nullable BlockPos leadTarget;
    private int leadStuckTicks;
    private final List<LocatedPlace> recentlyLocated = new ArrayList<>();
    private final AmbientObserver ambient = new AmbientObserver();
    private boolean following;
    private @Nullable UUID followTarget;
    /** Game time of his last spoken line, for the campaign's guide heartbeat. */
    private long lastSpokeTick;

    /** A place the locate tool found while answering; y is null for structures (surface unknown). */
    public record LocatedPlace(String label, int x, @Nullable Integer y, int z) {}

    public AiVillagerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    // ---- persona ----

    public Persona persona() {
        return PersonaRegistry.get().findOrDefault(personaId, Config.DEFAULT_PERSONA.get());
    }

    public void setPersona(String id) {
        if (!id.equals(personaId)) {
            personaId = id;
            engine = null;
            pendingHistory = null;
        }
        applyPersonaName();
    }

    public void applyPersonaName() {
        Persona p = persona();
        setCustomName(Component.literal(p.name()));
        setCustomNameVisible(true);
        entityData.set(DATA_SKIN, p.skin());
        entityData.set(DATA_VOICE, p.voice());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, "");
        builder.define(DATA_VOICE, "villager");
        builder.define(DATA_EYES_DARK, false);
        builder.define(DATA_SITTING, false);
    }

    /** Late in the campaign his eyes go dark; the renderer swaps the texture. */
    public boolean eyesDark() {
        return entityData.get(DATA_EYES_DARK);
    }

    // ---- his seat ----

    /** Where he sits when nothing is asked of him: the well's edge at arrival. */
    private @Nullable BlockPos seat;
    private int idleTicks;

    public @Nullable BlockPos seat() {
        return seat;
    }

    public void setSeat(@Nullable BlockPos seat) {
        this.seat = seat == null ? null : seat.immutable();
        idleTicks = 0;
    }

    public boolean sitting() {
        return entityData.get(DATA_SITTING);
    }

    private void setSitting(boolean sit) {
        if (entityData.get(DATA_SITTING) != sit) entityData.set(DATA_SITTING, sit);
    }

    /** Anything that moves him or asks for his attention gets him to his feet. */
    private void stand() {
        idleTicks = 0;
        setSitting(false);
    }

    /**
     * Idle, unaddressed, beside his seat: after a minute he sits; while sitting he keeps still. Wandered off
     * in daylight with nothing to do, now and then he drifts back to it. Standing up is immediate.
     */
    private void tickSeat(ServerLevel level, @Nullable ServerPlayer partner) {
        if (seat == null || partner != null || awaitingReplyFor != null || isSleeping()) {
            stand();
            return;
        }
        double d2 = position().distanceToSqr(Vec3.atBottomCenterOf(seat));
        if (d2 > SEAT_RADIUS * SEAT_RADIUS) {
            stand();
            if (post == null && level.isBrightOutside() && getNavigation().isDone() && d2 < 48 * 48 && random.nextInt(200) == 0) {
                getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 0.6D);
            }
            return;
        }
        if (!getNavigation().isDone()) {
            if (sitting()) getNavigation().stop(); // nothing moves him once he has sat down
            else {
                idleTicks = 0;
                return;
            }
        }
        if (++idleTicks >= SIT_AFTER_TICKS) setSitting(true);
    }

    public void setEyesDark(boolean dark) {
        entityData.set(DATA_EYES_DARK, dark);
    }

    public AmbientObserver ambientObserver() {
        return ambient;
    }

    public void markSpoke() {
        lastSpokeTick = level().getGameTime();
    }

    public long ticksSinceSpoke() {
        return level().getGameTime() - lastSpokeTick;
    }

    /** Texture id synced to clients; empty means the vanilla villager look. Dark eyes swap in the _dark variant. */
    @Override
    public String skin() {
        String skin = entityData.get(DATA_SKIN);
        if (!skin.isEmpty() && entityData.get(DATA_EYES_DARK) && skin.endsWith(".png")) {
            return skin.substring(0, skin.length() - 4) + "_dark.png";
        }
        return skin;
    }

    // ---- voice ----

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return "villager".equals(entityData.get(DATA_VOICE)) ? super.getAmbientSound() : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return "hollow".equals(entityData.get(DATA_VOICE)) ? SoundEvents.SOUL_ESCAPE.value() : super.getHurtSound(source);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return "hollow".equals(entityData.get(DATA_VOICE)) ? SoundEvents.SOUL_ESCAPE.value() : super.getDeathSound();
    }

    /** The villager base class names its kind by profession; ours are named by what they are. */
    @Override
    protected Component getTypeName() {
        return getType().getDescription();
    }

    public String speakerName() {
        return persona().name();
    }

    // ---- interaction ----

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !isAlive()) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp) {
            if (personaId.isEmpty()) {
                setPersona(Config.DEFAULT_PERSONA.get());
            }
            if (player.isSecondaryUseActive()) {
                ConversationManager.get().endSession(sp);
            } else {
                ConversationManager.get().engage(sp, this);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private boolean lookDirty;

    @Override
    protected void customServerAiStep(ServerLevel level) {
        if (lookDirty) {
            try {
                entityData.set(DATA_SKIN, persona().skin());
                entityData.set(DATA_VOICE, persona().voice());
                lookDirty = false;
            } catch (RuntimeException ignored) {
                // try again next tick
            }
        }
        ServerPlayer partner = ConversationManager.get().partnerOf(this);
        observeWorld(level, partner);
        if (awaitingReplyFor != null && engine != null && engine.isBusy() && tickCount % 30 == 0) {
            // Action-bar text fades after ~3s; keep it alive while a reply to the player is in flight.
            long waited = engine.busyMillis();
            String note = waited < 12_000 ? speakerName() + " is thinking..."
                    : speakerName() + " is still thinking (the road is slow today)...";
            awaitingReplyFor.sendSystemMessage(Component.literal(note).withStyle(net.minecraft.ChatFormatting.GRAY), true);
        }
        if (following || leadTarget != null) tickDoors(level);
        if (following || leadTarget != null) stand();
        if (fleeHunter(level)) {
            stand();
            return;
        }
        if (leadTarget != null && tickLeading(level, partner)) {
            return;
        }
        ServerPlayer followed = following ? followedPlayer() : null;
        if ((followed != null || leadTarget != null) && isSleeping()) stopSleeping();
        if (followed == null) {
            tickSeat(level, partner);
            if (post != null) tickPost(level, partner);
            else if (!sitting()) super.customServerAiStep(level);
            if (partner != null) {
                // Stand still and pay attention while someone is talking to you.
                getNavigation().stop();
                getLookControl().setLookAt(partner);
            }
            return;
        }

        // Following: the villager brain (schedule, job, bed) is skipped so it can't drag him off.
        if (followed.level() != level) {
            teleportBeside(followed);
            return;
        }
        double d2 = distanceToSqr(followed);
        boolean quiet = quietGround(level);
        if (quiet && (followed.hasEffect(MobEffects.DARKNESS) || wardenNear(level, 20))) {
            // The listening dark: he does not move while it is looking, and never while the player is blind.
            getNavigation().stop();
            return;
        }
        if (quiet && d2 < TELEPORT_SQ * 4 && d2 > FOLLOW_STOP_SQ) {
            if (followed.isSprinting()) {
                getNavigation().stop(); // "If the player sprints, he does not follow."
                return;
            }
            if (tickCount % 5 == 0 || getNavigation().isDone()) getNavigation().moveTo(followed, 0.8D);
            getLookControl().setLookAt(followed);
            return;
        }
        if (d2 >= TELEPORT_SQ) {
            // Only when hopelessly behind (a cliff, a locked door, a sprinting player); walking is the normal way.
            teleportBeside(followed);
        } else if (d2 < CROWDED_SQ) {
            // The player walked into him: step back a few blocks so he isn't in the way of mining or doors.
            if (tickCount % 10 == 0 || getNavigation().isDone()) {
                double dx = getX() - followed.getX(), dz = getZ() - followed.getZ();
                double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                getNavigation().moveTo(getX() + dx / len * 3.0, getY(), getZ() + dz / len * 3.0, 0.9D);
            }
            getLookControl().setLookAt(followed);
        } else if (d2 > FOLLOW_START_SQ || (!getNavigation().isDone() && d2 > FOLLOW_STOP_SQ) || separated(followed)) {
            // Walk when close, jog when the player is pulling away; keep going until comfortably near.
            // "Near" is not enough when a wall or a door is between you: then he comes through to your side.
            double speed = d2 > JOG_START_SQ ? 1.3D : 1.0D;
            if (tickCount % 5 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(followed, speed);
            }
            getLookControl().setLookAt(followed);
        } else {
            getNavigation().stop();
            getLookControl().setLookAt(partner != null ? partner : followed);
        }
    }

    /**
     * Doors on his way while he follows or leads (his villager brain, which would do this itself, is
     * skipped then): open the one he is stepping through, and close the ones he has opened once he is past.
     */
    private final java.util.Set<BlockPos> openedDoors = new java.util.HashSet<>();

    private void tickDoors(ServerLevel level) {
        net.minecraft.world.level.pathfinder.Path path = getNavigation().getPath();
        BlockPos from = null, to = null;
        if (path != null && !path.notStarted() && !path.isDone()) {
            from = path.getPreviousNode().asBlockPos();
            to = path.getNextNode().asBlockPos();
            openDoorAt(level, from);
            openDoorAt(level, to);
        }
        if (openedDoors.isEmpty()) return;
        var it = openedDoors.iterator();
        while (it.hasNext()) {
            BlockPos door = it.next();
            if (door.equals(from) || door.equals(to)) continue;
            if (getBoundingBox().intersects(new net.minecraft.world.phys.AABB(door))) continue; // still in the doorway
            var state = level.getBlockState(door);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock d && d.isOpen(state)
                    && door.distToCenterSqr(position()) < 9.0) {
                d.setOpen(this, level, state, door, false);
            }
            it.remove();
        }
    }

    private void openDoorAt(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.is(net.minecraft.tags.BlockTags.MOB_INTERACTABLE_DOORS) && state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock d) {
            if (!d.isOpen(state)) d.setOpen(this, level, state, pos, true);
            openedDoors.add(pos.immutable());
        }
    }

    /** He is afraid of the hunters, and does not pretend otherwise: one within reach and he gets away from it. */
    private boolean fleeHunter(ServerLevel level) {
        if (tickCount % 5 != 0 && getNavigation().isDone()) return false;
        net.minecraft.world.entity.LivingEntity hunter = null;
        double best = 14.0 * 14.0;
        for (EchoEntity e : level.getEntitiesOfClass(EchoEntity.class, getBoundingBox().inflate(14.0), EchoEntity::isAlive)) {
            double d = distanceToSqr(e);
            if (d < best) {
                best = d;
                hunter = e;
            }
        }
        for (UnsaidEntity u : level.getEntitiesOfClass(UnsaidEntity.class, getBoundingBox().inflate(10.0), UnsaidEntity::isAlive)) {
            double d = distanceToSqr(u);
            if (d < best) {
                best = d;
                hunter = u;
            }
        }
        if (hunter == null) return false;
        if (isSleeping()) stopSleeping();
        if (tickCount % 10 == 0 || getNavigation().isDone()) {
            double dx = getX() - hunter.getX(), dz = getZ() - hunter.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            getNavigation().moveTo(getX() + dx / len * 10.0, getY(), getZ() + dz / len * 10.0, 1.3D);
        }
        getLookControl().setLookAt(hunter);
        return true;
    }

    /** Close by, but with no clear line between his eyes and theirs: the other side of a door, or a wall. */
    private boolean separated(ServerPlayer p) {
        if (tickCount % 5 == 0) lastSeparated = !hasLineOfSight(p);
        return lastSeparated;
    }

    private boolean lastSeparated;

    /** Sculk country: the Deep Dark or the Quiet. He whispers, walks, and freezes when something listens. */
    public boolean quietGround(ServerLevel level) {
        return com.ayodehi.thehush.campaign.Quiet.isQuiet(level)
                || level.getBiome(blockPosition()).is(net.minecraft.world.level.biome.Biomes.DEEP_DARK);
    }

    private boolean wardenNear(ServerLevel level, double r) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.monster.warden.Warden.class, getBoundingBox().inflate(r), w -> w.isAlive()).isEmpty();
    }

    /** Unprompted remarks about weather, nightfall, monsters and the player's state. */
    private void observeWorld(ServerLevel level, @Nullable ServerPlayer partner) {
        if (!Config.AMBIENT_REMARKS.get() || (level.dimension() != Level.OVERWORLD && !com.ayodehi.thehush.campaign.Quiet.isQuiet(level))) return;
        // Someone with a greeting does not speak first: no unprompted remarks until a player has engaged him once.
        if (partner == null && rememberedMessages() == 0 && !persona().greeting().isBlank()) return;
        if (com.ayodehi.thehush.campaign.NightSilence.holdsTongue(this)) return;
        ServerPlayer audience = partner;
        if (audience == null && isFollowing()) {
            ServerPlayer f = followedPlayer();
            if (f != null && f.level() == level && distanceToSqr(f) < 256) audience = f;
        }
        if (audience == null) {
            double r = Config.CONVERSATION_RADIUS.get() * 1.5;
            audience = level.getNearestPlayer(this, r) instanceof ServerPlayer sp ? sp : null;
        }
        if (audience == null) return;
        String event = ambient.observe(this, level, audience);
        if (event != null) {
            ConversationManager.get().remark(this, audience, event);
        }
    }

    // ---- staying put ----

    /**
     * Told to stay, he stays: no schedule, no bed, no wandering off to the meeting point. He keeps within a
     * few blocks of the spot, takes a few steps now and then, and watches whoever is near.
     */
    private void tickPost(ServerLevel level, @Nullable ServerPlayer partner) {
        if (post == null) return;
        double d2 = position().distanceToSqr(Vec3.atBottomCenterOf(post));
        if (d2 > POST_RADIUS * POST_RADIUS) {
            if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0.8D);
            if (d2 > 24 * 24) teleportNear(post);
            return;
        }
        if (getNavigation().isDone() && random.nextInt(240) == 0) {
            double x = post.getX() + 0.5 + (random.nextDouble() - 0.5) * 5.0;
            double z = post.getZ() + 0.5 + (random.nextDouble() - 0.5) * 5.0;
            getNavigation().moveTo(x, post.getY(), z, 0.6D);
        }
        if (partner == null && tickCount % 10 == 0) {
            Player near = level.getNearestPlayer(this, 8.0);
            if (near != null) getLookControl().setLookAt(near);
        }
    }

    private void teleportNear(BlockPos target) {
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos c = target.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos at = c.offset(0, dy, 0);
                if (WalkNodeEvaluator.getPathTypeStatic(this, at) == PathType.WALKABLE
                        && level().noCollision(this, getBoundingBox().move(Vec3.atBottomCenterOf(at).subtract(position())))) {
                    snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, getYRot(), getXRot());
                    getNavigation().stop();
                    return;
                }
            }
        }
    }

    public @Nullable BlockPos post() {
        return post;
    }

    // ---- leading ----

    public void startLeading(BlockPos target) {
        leadTarget = target;
        leadStuckTicks = 0;
        post = null;
    }

    public void stopLeading() {
        leadTarget = null;
        getNavigation().stop();
    }

    public boolean isLeading() {
        return leadTarget != null;
    }

    /** Walk ahead toward the target, waiting when the player falls behind. Returns true while in charge of movement. */
    private boolean tickLeading(ServerLevel level, @Nullable ServerPlayer partner) {
        ServerPlayer p = partner != null ? partner : talkingTo != null && !talkingTo.isRemoved() ? talkingTo : followedPlayer();
        BlockPos target = leadTarget;
        if (target == null) return false;
        if (p == null || p.level() != level || distanceToSqr(p) > 32 * 32) {
            stopLeading();
            return false;
        }
        double toTarget = blockPosition().distSqr(target);
        if (toTarget <= 2.5 * 2.5) {
            stopLeading();
            getLookControl().setLookAt(p);
            ConversationManager.get().remark(this, p, "You have arrived at the place you were leading "
                    + p.getName().getString() + " to (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ").");
            return true;
        }
        double toPlayer = distanceToSqr(p);
        if (toPlayer > 8 * 8) {
            // Don't run off: wait for them to catch up.
            getNavigation().stop();
            getLookControl().setLookAt(p);
            return true;
        }
        if (quietGround(level) && (p.hasEffect(MobEffects.DARKNESS) || wardenNear(level, 20))) {
            getNavigation().stop();
            return true;
        }
        if (tickCount % 10 == 0 || getNavigation().isDone()) {
            boolean ok = getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 2, quietGround(level) ? 0.8D : 1.0D);
            if (!ok || getNavigation().isDone()) {
                if (++leadStuckTicks > 6) { // ~3 seconds without a path: give up honestly
                    stopLeading();
                    ConversationManager.get().remark(this, p, "You cannot find a walkable way to ("
                            + target.getX() + ", " + target.getY() + ", " + target.getZ() + "); it is sealed in rock or out of reach on foot. Tell "
                            + p.getName().getString() + " which way to dig instead.");
                    return true;
                }
            } else {
                leadStuckTicks = 0;
            }
        }
        getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        return true;
    }

    // ---- following ----

    public boolean isFollowing() {
        return following && followTarget != null;
    }

    public @Nullable ServerPlayer followedPlayer() {
        if (followTarget == null || level().getServer() == null) return null;
        ServerPlayer p = level().getServer().getPlayerList().getPlayer(followTarget);
        return p != null && !p.isSpectator() ? p : null;
    }

    public void startFollowing(ServerPlayer player) {
        following = true;
        followTarget = player.getUUID();
        post = null;
        if (isSleeping()) stopSleeping();
    }

    /** Told to stay: he stops, and this spot becomes his post until he is asked to come along or lead again. */
    public void stopFollowing() {
        following = false;
        followTarget = null;
        getNavigation().stop();
        post = blockPosition();
    }

    /**
     * Crossing dimensions replaces the entity object (the game copies him into the new level and discards
     * this one). Anything holding a reference across such a move must ask for the live one.
     */
    public AiVillagerEntity current() {
        if (!isRemoved() || level().getServer() == null) return this;
        for (ServerLevel level : level().getServer().getAllLevels()) {
            if (level.getEntity(getUUID()) instanceof AiVillagerEntity live && !live.isRemoved()) return live;
        }
        return this;
    }

    /** Step to the player's side: cross dimensions if needed, otherwise pick a walkable spot 2-3 blocks away like a pet. */
    public void teleportBeside(ServerPlayer player) {
        if (player.level() != level()) {
            teleportTo((ServerLevel) player.level(), player.getX(), player.getY(), player.getZ(),
                    Set.<Relative>of(), getYRot(), getXRot(), false);
            return;
        }
        var target = player.blockPosition();
        for (int attempt = 0; attempt < 40; attempt++) {
            int reach = attempt < 20 ? 4 : 8;
            int xd = random.nextIntBetweenInclusive(-reach, reach);
            int zd = random.nextIntBetweenInclusive(-reach, reach);
            if (Math.abs(xd) < 2 && Math.abs(zd) < 2) continue;
            int yd = random.nextIntBetweenInclusive(-2, 2);
            var pos = target.offset(xd, yd, zd);
            if (WalkNodeEvaluator.getPathTypeStatic(this, pos) != PathType.WALKABLE) continue;
            if (!level().noCollision(this, getBoundingBox().move(pos.subtract(blockPosition())))) continue;
            snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, getYRot(), getXRot());
            getNavigation().stop();
            return;
        }
        // Nowhere tidy to stand: appear right on the player rather than get left behind, unless that is inside a wall.
        if (level().noCollision(this, getBoundingBox().move(player.position().subtract(position())))) {
            snapTo(player.getX(), player.getY(), player.getZ(), getYRot(), getXRot());
        }
        getNavigation().stop();
    }

    // ---- conversation ----

    /** The player whose message is currently being answered; tools inspect this player. */
    public @Nullable ServerPlayer talkingTo() {
        return talkingTo;
    }

    public void setTalkingTo(@Nullable ServerPlayer player) {
        this.talkingTo = player;
        if (player != null && isSleeping()) stopSleeping();
    }

    public void setAwaitingReplyFor(@Nullable ServerPlayer player) {
        this.awaitingReplyFor = player;
    }

    /** Server thread only: called by the locate tool. */
    public void rememberLocated(LocatedPlace place) {
        recentlyLocated.add(place);
    }

    /** Server thread only: returns and clears places found during the exchange that just finished. */
    public List<LocatedPlace> drainLocated() {
        List<LocatedPlace> out = List.copyOf(recentlyLocated);
        recentlyLocated.clear();
        return out;
    }

    /** Lazily builds the engine so a config change (new key, new model) is picked up without respawning. */
    public @Nullable ConversationEngine engine() {
        LlmProvider provider = LlmService.get().provider();
        if (provider == null) {
            return null;
        }
        if (engine == null || engineProvider != provider) {
            ConversationEngine fresh = new ConversationEngine(provider, ToolRegistry.get().specs(),
                    ToolRegistry.get().executorFor(this), Config.MAX_TOOL_ROUNDS.get(),
                    Config.MAX_HISTORY_MESSAGES.get());
            if (engine != null) {
                fresh.loadJson(engine.toJson());
            } else if (pendingHistory != null) {
                fresh.loadJson(pendingHistory);
                pendingHistory = null;
            }
            engine = fresh;
            engineProvider = provider;
        }
        return engine;
    }

    /** Persona rules plus whatever this villager has chosen to remember about the current partner. */
    public String systemPrompt() {
        Persona persona = persona();
        CampaignManager campaign = CampaignManager.get();
        boolean inCampaign = campaign.takesPart(this);
        java.util.Set<String> forgotten = inCampaign ? campaign.forgotten(level().getServer()) : java.util.Set.of();
        String base = PromptBuilder.systemPrompt(persona, forgotten.contains("own_fear"),
                com.ayodehi.thehush.voice.VoiceService.get().readsCues());
        if (level().getServer() == null) return base;
        ServerPlayer p = talkingTo;
        String section = inCampaign ? campaign.promptSection(this, p) : "";
        VillagerMemory.View view = VillagerMemory.View.ALL;
        if (inCampaign && p != null && campaign.isChosen(level().getServer(), p)) {
            boolean stranger = campaign.state(level().getServer()).strangerToPlayer;
            view = new VillagerMemory.View(forgotten.contains("player_name"),
                    forgotten.contains("recent_days") ? currentDay() - 1 : Long.MAX_VALUE, stranger);
        }
        return base + section + memory().promptSection(p == null ? null : p.getUUID(), p == null ? null : p.getName().getString(), view)
                + currentState();
    }

    /** What he is doing right now, so the model knows whether "stay" or "come" needs a tool call. */
    private String currentState() {
        ServerPlayer f = isFollowing() ? followedPlayer() : null;
        if (f != null) {
            return "\nRight now you are travelling with " + f.getName().getString()
                    + " (following them). If they tell you to stay or stop, call stop_following.\n";
        }
        if (leadTarget != null) return "\nRight now you are leading the player somewhere.\n";
        if (post != null) return "\nRight now you are staying where you were told to, keeping to a few blocks of the spot, "
                + "travelling with nobody. If they ask you to come, call follow_player.\n";
        return "\nRight now you are standing where you are, travelling with nobody. If they ask you to come, call follow_player.\n";
    }

    public VillagerMemory memory() {
        return MemoryStore.forVillager(level().getServer(), getUUID(), personaId);
    }

    /** In-game day, for dating memories. */
    public long currentDay() {
        return level().getOverworldClockTime() / 24000L;
    }

    public void forgetEverything() {
        pendingHistory = null;
        if (engine != null) engine.clearHistory();
    }

    public int rememberedMessages() {
        if (engine != null) return engine.historySize();
        return pendingHistory == null ? 0 : pendingHistory.size();
    }

    public @Nullable UUID conversationPartner() {
        ServerPlayer p = ConversationManager.get().partnerOf(this);
        return p == null ? null : p.getUUID();
    }

    // ---- death ----

    @Override
    public void die(DamageSource source) {
        // Health is already zero here, so isAlive() would be false; "dead" is the flag the base class sets once.
        boolean first = !this.dead && !isRemoved();
        super.die(source);
        if (first && !level().isClientSide() && persona().returnsFromDeath()) {
            ReturnRegistry.get().schedule(this);
        }
    }

    /** He has walked sculk country before: nothing he does reaches a sensor, a shrieker, or the Warden's ear. */
    @Override
    public boolean dampensVibrations() {
        return true;
    }

    /** He is not entirely here: walls do not crush him, and the void only sends him home early. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)) {
            return true;
        }
        return super.isInvulnerableTo(level, source);
    }

    public String personaId() {
        return personaId;
    }

    public @Nullable UUID followTargetId() {
        return following ? followTarget : null;
    }

    public JsonArray historyJson() {
        if (engine != null) return engine.toJson();
        return pendingHistory == null ? new JsonArray() : pendingHistory;
    }

    /** After a dimension crossing mid-reply: take the finished conversation from the old body's engine. */
    public void adoptHistory(ConversationEngine finished) {
        pendingHistory = finished.toJson();
        engine = null;
    }

    public void restoreHistory(JsonArray history) {
        engine = null;
        pendingHistory = history.isEmpty() ? null : history;
    }

    // ---- persistence ----

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(TAG_PERSONA, personaId);
        output.putBoolean(TAG_FOLLOWING, following);
        if (post != null) output.putString(TAG_POST, post.getX() + "," + post.getY() + "," + post.getZ());
        output.putBoolean(TAG_EYES_DARK, eyesDark());
        if (seat != null) output.putString(TAG_SEAT, seat.getX() + "," + seat.getY() + "," + seat.getZ());
        if (followTarget != null) output.putString(TAG_FOLLOW_TARGET, followTarget.toString());
        JsonArray history = engine != null ? engine.toJson() : pendingHistory;
        if (history != null && !history.isEmpty()) {
            output.putString(TAG_HISTORY, history.toString());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        personaId = input.getStringOr(TAG_PERSONA, "");
        if (!personaId.isEmpty()) {
            try {
                entityData.set(DATA_SKIN, persona().skin());
                entityData.set(DATA_VOICE, persona().voice());
            } catch (RuntimeException e) {
                // Whatever went wrong with the persona lookup, he must not vanish for it; the look is set on the first tick.
                com.ayodehi.thehush.TheHushMod.LOGGER.warn("Could not resolve persona {} while loading {}; will retry", personaId, getUUID(), e);
                lookDirty = true;
            }
        }
        following = input.getBooleanOr(TAG_FOLLOWING, false);
        String postText = input.getStringOr(TAG_POST, "");
        post = null;
        if (!postText.isEmpty()) {
            try {
                String[] xyz = postText.split(",");
                post = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            } catch (RuntimeException ignored) {
                post = null;
            }
        }
        entityData.set(DATA_EYES_DARK, input.getBooleanOr(TAG_EYES_DARK, false));
        String seatText = input.getStringOr(TAG_SEAT, "");
        seat = null;
        if (!seatText.isEmpty()) {
            try {
                String[] xyz = seatText.split(",");
                seat = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            } catch (RuntimeException ignored) {
                seat = null;
            }
        }
        String target = input.getStringOr(TAG_FOLLOW_TARGET, "");
        try {
            followTarget = target.isEmpty() ? null : UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            followTarget = null;
        }
        engine = null;
        pendingHistory = null;
        String raw = input.getStringOr(TAG_HISTORY, "");
        if (!raw.isEmpty()) {
            try {
                pendingHistory = JsonParser.parseString(raw).getAsJsonArray();
            } catch (RuntimeException e) {
                pendingHistory = null;
            }
        }
    }
}
