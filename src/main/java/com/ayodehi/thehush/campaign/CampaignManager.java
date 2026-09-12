package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs the campaign for the current world: binds the chosen player, advances stages from world triggers,
 * queues scripted beats and nudges for the Traveller, counts his returns from death, and builds the
 * campaign section of his prompt. Everything here runs on the server thread.
 */
public final class CampaignManager {
    private static final CampaignManager INSTANCE = new CampaignManager();
    private static final int CHECK_EVERY_TICKS = 20;
    private static final long NUDGE_GAP_TICKS = 20L * 60L * 10L;
    private static final long BEAT_TTL_TICKS = 20L * 60L * 20L;
    public static final double EARSHOT = 24.0;

    private @Nullable CampaignState state;
    private final Deque<Beat> beats = new ArrayDeque<>();
    private @Nullable Pattern namePattern;
    private String namePatternFor = "";
    private final java.util.Map<String, Long> placeLastFired = new java.util.HashMap<>();
    private long lastGuideTick = Long.MIN_VALUE / 2;

    /** A scripted line for him, delivered when he is near the chosen and free to speak. */
    private record Beat(String text, long expiresTick, boolean scripted, boolean stage) {}
    private long lastBeatTick = Long.MIN_VALUE / 2;

    private CampaignManager() {}

    public static CampaignManager get() {
        return INSTANCE;
    }

    // ---- state ----

    public CampaignState state(MinecraftServer server) {
        if (state == null) {
            state = CampaignState.load(server.getWorldPath(LevelResource.ROOT).resolve(TheHushMod.MODID).resolve("campaign.json"));
            CampaignDefinition loadedDef = state.started() ? CampaignRegistry.get().find(state.campaignId) : null;
            if (loadedDef != null) {
                java.util.List<String> stale = StageMachine.staleReachedFlags(loadedDef, state.flags);
                if (!stale.isEmpty()) {
                    state.flags.removeAll(stale);
                    state.save();
                    TheHushMod.LOGGER.info("Campaign: dropped pre-armed day-count stage(s) {} from the save", stale);
                }
            }
            if (state.forcedChunk != null) {
                // Left force-loaded by a previous run that stopped mid-search.
                String[] parts = state.forcedChunk.split(",");
                Identifier id = parts.length == 3 ? Identifier.tryParse(parts[0]) : null;
                ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
                if (level != null) {
                    try {
                        level.setChunkForced(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), false);
                    } catch (NumberFormatException ignored) {
                        // malformed; just forget it
                    }
                }
                state.forcedChunk = null;
                state.save();
            }
        }
        return state;
    }

    /** Server stopping: let go of any chunk we were holding open. */
    public void shutdown(MinecraftServer server) {
        releaseForced(server);
        if (state != null && state.forcedChunk != null) {
            state.forcedChunk = null;
            state.save();
        }
    }

    public void clear() {
        state = null;
        forced = null;
        beats.clear();
        placeLastFired.clear();
        lastGuideTick = Long.MIN_VALUE / 2;
        lastBeatTick = Long.MIN_VALUE / 2;
    }

    public boolean active(MinecraftServer server) {
        return Config.CAMPAIGN_ENABLED.get() && state(server).started();
    }

    public @Nullable CampaignDefinition definition(MinecraftServer server) {
        CampaignState s = state(server);
        return s.started() ? CampaignRegistry.get().find(s.campaignId) : null;
    }

    public @Nullable CampaignStage stage(MinecraftServer server) {
        CampaignDefinition d = definition(server);
        return d == null ? null : d.stage(state(server).stage);
    }

    /** True when this villager's persona belongs to the running campaign. */
    public boolean takesPart(AiVillagerEntity npc) {
        MinecraftServer server = npc.level().getServer();
        if (server == null || !Config.CAMPAIGN_ENABLED.get()) return false;
        String id = npc.persona().campaign();
        if (id.isEmpty()) return false;
        CampaignState s = state(server);
        return s.started() ? s.campaignId.equals(id) : true;
    }

    public boolean isChosen(MinecraftServer server, ServerPlayer player) {
        CampaignState s = state(server);
        return s.chosenPlayer != null && s.chosenPlayer.equals(player.getUUID().toString());
    }

    public @Nullable ServerPlayer chosen(MinecraftServer server) {
        CampaignState s = state(server);
        if (s.chosenPlayer == null) return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(s.chosenPlayer));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void bind(MinecraftServer server, ServerPlayer player) {
        CampaignState s = state(server);
        s.chosenPlayer = player.getUUID().toString();
        s.chosenName = player.getName().getString();
        s.save();
    }

    /** Called whenever a player starts talking to a villager: the first to engage a campaign persona is the chosen. */
    public void onEngaged(AiVillagerEntity npc, ServerPlayer player) {
        MinecraftServer server = npc.level().getServer();
        if (server == null || !Config.CAMPAIGN_ENABLED.get()) return;
        String id = npc.persona().campaign();
        if (id.isEmpty()) return;
        CampaignState s = state(server);
        if (!s.started()) {
            CampaignDefinition d = CampaignRegistry.get().find(id);
            if (d == null) {
                TheHushMod.LOGGER.warn("Persona {} names campaign {} which does not exist", npc.personaId(), id);
                return;
            }
            s.campaignId = id;
            BlockPos at = npc.blockPosition();
            s.arrival = new int[] {at.getX(), at.getY(), at.getZ()};
            bind(server, player);
            enterStage(server, d.first(), "first meeting");
            TheHushMod.LOGGER.info("Campaign {} begins; {} is the chosen", d.title, player.getName().getString());
        } else if (s.chosenPlayer == null && s.campaignId.equals(id)) {
            bind(server, player);
        }
    }

    // ---- stages ----

    public void enterStage(MinecraftServer server, CampaignStage stage, String how) {
        CampaignState s = state(server);
        ServerLevel overworld = server.overworld();
        s.stage = stage.id;
        s.stageEnteredDay = overworld.getOverworldClockTime() / 24000L;
        s.stageEnteredTick = overworld.getGameTime();
        s.lastNudgeTick = Long.MIN_VALUE / 2;
        CampaignState.Visit v = new CampaignState.Visit();
        v.stage = stage.id;
        v.day = s.stageEnteredDay;
        s.history.add(v);
        s.save();
        TheHushMod.LOGGER.info("Campaign stage {} ({}): {}", stage.id, stage.title, how);
        if (!stage.onEnter.isBlank()) {
            String text = stage.onEnter;
            if (stage.id.equals("named") && s.flags.contains("heard")) {
                text += " Something below turned toward the sound when it was said.";
            }
            queueBeat(server, text, true, true);
        }
        CampaignEvents.onStageEntered(server, this, stage);
    }

    public boolean setFlag(MinecraftServer server, String flag) {
        CampaignState s = state(server);
        if (s.flags.add(flag)) {
            s.save();
            if (Config.CAMPAIGN_DEBUG.get()) TheHushMod.LOGGER.info("Campaign flag set: {}", flag);
            return true;
        }
        return false;
    }

    public boolean hasFlag(MinecraftServer server, String flag) {
        return state(server).flags.contains(flag);
    }

    /** Queue a [world] line for him. Scripted beats bypass the ambient cooldown; nudges do not. */
    public void queueBeat(MinecraftServer server, String text, boolean scripted) {
        queueBeat(server, text, scripted, false);
    }

    /** Stage-entry beats go to the front of the line; everything else waits its turn. */
    public void queueBeat(MinecraftServer server, String text, boolean scripted, boolean stageEntry) {
        long now = server.overworld().getGameTime();
        Beat b = new Beat(text, now + BEAT_TTL_TICKS, scripted, stageEntry);
        if (stageEntry) beats.addFirst(b);
        else beats.addLast(b);
    }

    // ---- ticking ----

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
        if (Config.CAMPAIGN_ENABLED.get() && !state(server).started()) Arrival.tick(server, state(server));
        if (!active(server)) return;
        CampaignState s = state(server);
        CampaignDefinition def = CampaignRegistry.get().find(s.campaignId);
        if (def == null) return;
        ServerPlayer chosen = chosen(server);
        AiVillagerEntity traveller = traveller(server, chosen);
        long now = server.overworld().getGameTime();
        keepTrackOfHim(server, s, traveller, chosen);

        if (chosen != null) {
            advance(server, def, s, chosen);
            places(server, def, s, chosen, now);
            guide(server, def, s, chosen, traveller, now);
            CampaignStage stage = def.stage(s.stage);
            if (stage != null && stage.nudgeAfterMinutes > 0 && !stage.nudge.isBlank()
                    && now - s.stageEnteredTick >= stage.nudgeAfterMinutes * 1200L
                    && now - s.lastNudgeTick >= NUDGE_GAP_TICKS
                    && traveller != null && withinEarshot(traveller, chosen)) {
                s.lastNudgeTick = now;
                s.save();
                queueBeat(server, stage.nudge, false);
            }
        }
        deliverBeats(server, traveller, chosen, now);
        CampaignEvents.tick(server, this, s);
    }

    private void advance(MinecraftServer server, CampaignDefinition def, CampaignState s, ServerPlayer chosen) {
        long day = server.overworld().getOverworldClockTime() / 24000L;
        WorldSnapshot snap = new WorldSnapshot(chosen, s.flags, day - s.stageEnteredDay);
        StageMachine.Result r = StageMachine.evaluate(def, s.stage, s.flags, snap);
        // Later triggers firing early are remembered as flags, never skipped to.
        for (String id : r.reachedEarly()) setFlag(server, "reached:" + id);
        if (r.enterNext()) {
            CampaignStage next = def.after(s.stage);
            if (next != null) enterStage(server, next, "trigger");
        }
    }

    /**
     * A follower who is loaded but far behind is stepped to the chosen's side; one whose chunk unloaded
     * (the chosen died or teleported away) is found again by loading the chunk he was last seen in.
     */
    private void keepTrackOfHim(MinecraftServer server, CampaignState s, @Nullable AiVillagerEntity traveller, @Nullable ServerPlayer chosen) {
        if (traveller != null) {
            if (forced != null) releaseForced(server);
            BlockPos p = traveller.blockPosition();
            s.travellerLevel = traveller.level().dimension().identifier().toString();
            s.travellerPos = new int[] {p.getX(), p.getY(), p.getZ()};
            s.travellerFollowing = traveller.isFollowing();
            if (server.getTickCount() % 600 == 0) s.save();
            if (chosen != null && !chosen.isSpectator() && traveller.isFollowing() && chosen.getUUID().equals(traveller.followTargetId())
                    && !traveller.isLeading()
                    && (traveller.level() != chosen.level() || traveller.distanceToSqr(chosen) > 64 * 64)) {
                traveller.teleportBeside(chosen);
            }
            return;
        }
        if (chosen == null || chosen.isSpectator() || !s.travellerFollowing || s.travellerPos == null || s.travellerLevel == null) return;
        if (server.getTickCount() % 200 != 0) return;
        Identifier id = Identifier.tryParse(s.travellerLevel);
        if (id == null) return;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return;
        // A plain chunk load unloads again before the entities in it are read back; forcing the chunk
        // keeps it ticking until he is found, then the next pass steps him over and releases it.
        int cx = s.travellerPos[0] >> 4, cz = s.travellerPos[2] >> 4;
        if (forced == null) {
            level.setChunkForced(cx, cz, true);
            forced = new Forced(level.dimension(), cx, cz, server.getTickCount());
            s.forcedChunk = level.dimension().identifier() + "," + cx + "," + cz;
            s.save();
            TheHushMod.LOGGER.info("Looking for him where he was last seen: {}", java.util.Arrays.toString(s.travellerPos));
        } else if (server.getTickCount() - forced.sinceTick > 20 * 60) {
            releaseForced(server);
            s.travellerFollowing = false; // he is not where we left him; stop searching
            s.save();
            TheHushMod.LOGGER.warn("Could not find him at {}; giving up the search", java.util.Arrays.toString(s.travellerPos));
        }
    }

    private record Forced(ResourceKey<net.minecraft.world.level.Level> level, int cx, int cz, long sinceTick) {}

    private @Nullable Forced forced;

    private void releaseForced(MinecraftServer server) {
        if (forced == null) return;
        ServerLevel level = server.getLevel(forced.level());
        if (level != null) level.setChunkForced(forced.cx(), forced.cz(), false);
        forced = null;
        if (state != null && state.forcedChunk != null) {
            state.forcedChunk = null;
            state.save();
        }
    }

    /** Location beats: the first time (or every so often) the chosen reaches somewhere the story cares about. */
    private void places(MinecraftServer server, CampaignDefinition def, CampaignState s, ServerPlayer chosen, long now) {
        if (def.places.isEmpty()) return;
        int idx = def.indexOf(s.stage);
        long day = server.overworld().getOverworldClockTime() / 24000L;
        WorldSnapshot snap = new WorldSnapshot(chosen, s.flags, day - s.stageEnteredDay);
        for (CampaignDefinition.Place place : def.places) {
            if (place.prompt.isBlank()) continue;
            if (place.fromStage != null && idx < def.indexOf(place.fromStage)) continue;
            if (place.untilStage != null && def.indexOf(place.untilStage) >= 0 && idx > def.indexOf(place.untilStage)) continue;
            String flag = "place:" + place.id;
            if (place.once && s.flags.contains(flag)) continue;
            Long last = placeLastFired.get(place.id);
            if (!place.once && last != null && now - last < place.repeatMinutes * 1200L) continue;
            if (!place.matches(snap)) continue;
            placeLastFired.put(place.id, now);
            if (place.once) setFlag(server, flag);
            queueBeat(server, place.prompt, true);
            if (Config.CAMPAIGN_DEBUG.get()) TheHushMod.LOGGER.info("Place beat: {}", place.id);
        }
    }

    /** The guide heartbeat: together and quiet for a while, he is told where they are and may steer. */
    private void guide(MinecraftServer server, CampaignDefinition def, CampaignState s, ServerPlayer chosen,
                       @Nullable AiVillagerEntity traveller, long now) {
        int minutes = Config.GUIDE_MINUTES.get();
        if (minutes <= 0 || traveller == null || !withinEarshot(traveller, chosen)) return;
        if (traveller.ticksSinceSpoke() < minutes * 1200L || now - lastGuideTick < minutes * 1200L) return;
        if (!beats.isEmpty()) return;
        lastGuideTick = now;
        long day = server.overworld().getOverworldClockTime() / 24000L;
        WorldSnapshot snap = new WorldSnapshot(chosen, s.flags, day - s.stageEnteredDay);
        String who = displayName(traveller, chosen);
        queueBeat(server, "Neither of you has spoken for a while. You and " + who + " are " + snap.describeWhere()
                + ". " + com.ayodehi.thehush.tools.WorldTools.carrySummary(chosen, 6)
                + " Think about where the road leads next from here (your purpose), what they have and lack, and what you can see around you. "
                + "If one line of direction, a warning, or an observation about this place would help them onward, say it; "
                + "otherwise stay quiet.", false);
    }

    private void deliverBeats(MinecraftServer server, @Nullable AiVillagerEntity traveller, @Nullable ServerPlayer chosen, long now) {
        beats.removeIf(b -> now > b.expiresTick);
        if (beats.isEmpty() || traveller == null || chosen == null) return;
        if (now - lastBeatTick < Config.BEAT_GAP_SECONDS.get() * 20L) return;
        if (!withinEarshot(traveller, chosen)) return;
        if (NightSilence.holdsTongue(traveller)) return; // beats keep until he is willing to speak again
        var engine = traveller.engine();
        if (engine == null || engine.isBusy()) return;
        Beat b = beats.poll();
        if (b == null) return;
        lastBeatTick = now;
        if (b.scripted) traveller.ambientObserver().markScripted(now);
        ConversationManager.get().remark(traveller, chosen, b.text);
    }

    public static boolean withinEarshot(AiVillagerEntity npc, ServerPlayer player) {
        if (player.level() != npc.level()) return false;
        if (npc.isFollowing() && player.equals(npc.followedPlayer())) return true;
        return npc.distanceTo(player) <= EARSHOT;
    }

    /** The campaign persona's villager, nearest to the given player when there are several. */
    public @Nullable AiVillagerEntity traveller(MinecraftServer server, @Nullable ServerPlayer near) {
        CampaignState s = state(server);
        List<AiVillagerEntity> found = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities(EntityTypeTest.forClass(AiVillagerEntity.class),
                    v -> v.isAlive() && s.campaignId.equals(v.persona().campaign()), found);
        }
        if (found.isEmpty()) return null;
        if (near == null) return found.get(0);
        AiVillagerEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AiVillagerEntity v : found) {
            double d = v.level() == near.level() ? v.distanceToSqr(near) : 1e12 + v.getId();
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    // ---- deaths and forgetting ----

    /** Called when he is brought back at dawn. Each return costs a memory once the thresholds are crossed. */
    public void recordReturn(AiVillagerEntity npc) {
        MinecraftServer server = npc.level().getServer();
        if (server == null || !takesPart(npc)) return;
        CampaignState s = state(server);
        if (!s.started()) return;
        s.deaths++;
        Set<String> before = new java.util.LinkedHashSet<>(s.forgotten);
        recomputeForgotten(server);
        s.save();
        Set<String> lost = new java.util.LinkedHashSet<>(s.forgotten);
        lost.removeAll(before);
        TheHushMod.LOGGER.info("Campaign: {} has returned {} time(s); forgotten {}", npc.speakerName(), s.deaths, s.forgotten);
        if (!lost.isEmpty()) {
            // What he forgets must not survive in the transcript he is replayed: the last day's talk goes with it.
            npc.forgetEverything();
            TheHushMod.LOGGER.info("Campaign: the return cost {}; his recent conversation is gone with it", lost);
        }
        if (s.forgotten.contains("crossings")) npc.setEyesDark(true);
    }

    private void recomputeForgotten(MinecraftServer server) {
        CampaignState s = state(server);
        CampaignDefinition def = CampaignRegistry.get().find(s.campaignId);
        if (def == null) return;
        s.forgotten.clear();
        for (CampaignDefinition.Forgetting f : def.forgetting) {
            if (s.deaths >= f.deaths) s.forgotten.add(f.forget);
        }
    }

    public Set<String> forgotten(MinecraftServer server) {
        return state(server).forgotten;
    }

    /** How he refers to this player in the prompt: the name, unless he has lost it. */
    public String displayName(AiVillagerEntity npc, ServerPlayer player) {
        MinecraftServer server = npc.level().getServer();
        String name = player.getName().getString();
        if (server == null || !takesPart(npc)) return name;
        CampaignState s = state(server);
        if (s.strangerToPlayer && isChosen(server, player)) return "the stranger";
        return s.forgotten.contains("player_name") && isChosen(server, player) ? "the player" : name;
    }

    // ---- the name (Phase 3) ----

    /** Chat listener: the true name spoken within earshot of him opens everything; near sculk, it is heard. */
    public void onChat(ServerPlayer player, String text) {
        MinecraftServer server = player.level().getServer();
        if (server == null || !active(server)) return;
        CampaignDefinition def = definition(server);
        if (def == null || def.trueName.isBlank() || hasFlag(server, "name_spoken")) return;
        if (!def.trueName.equals(namePatternFor)) {
            namePatternFor = def.trueName;
            namePattern = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(def.trueName) + "(?![\\p{L}\\p{N}])");
        }
        if (namePattern == null || !namePattern.matcher(text).find()) return;
        AiVillagerEntity traveller = traveller(server, player);
        if (traveller == null || traveller.level() != player.level() || traveller.distanceTo(player) > 32) return;
        if (sculkNear((ServerLevel) player.level(), player.blockPosition(), 16)) setFlag(server, "heard");
        setFlag(server, "name_spoken");
        if (!isChosen(server, player)) bind(server, player);
        TheHushMod.LOGGER.info("The name was spoken by {}", player.getName().getString());
    }

    public static boolean sculkNear(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -4, -r), center.offset(r, 4, r))) {
            var st = level.getBlockState(pos);
            if (st.is(Blocks.SCULK_SENSOR) || st.is(Blocks.CALIBRATED_SCULK_SENSOR) || st.is(Blocks.SCULK_SHRIEKER)) return true;
        }
        return false;
    }

    // ---- prompt ----

    /** The campaign block of his system prompt. Changes only on stage change or a return from death. */
    public String promptSection(AiVillagerEntity npc, @Nullable ServerPlayer partner) {
        MinecraftServer server = npc.level().getServer();
        if (server == null || !takesPart(npc)) return "";
        CampaignState s = state(server);
        CampaignDefinition def = s.started() ? CampaignRegistry.get().find(s.campaignId) : CampaignRegistry.get().find(npc.persona().campaign());
        if (def == null) return "";
        int idx = s.started() ? Math.max(0, def.indexOf(s.stage)) : 0;
        CampaignStage stage = def.stages.get(idx);
        StringBuilder sb = new StringBuilder();
        sb.append("\nThe road (only you know this; let it out a line at a time, never recited):\n");
        if (s.strangerToPlayer) {
            sb.append("- You have never met this person. You have been waiting for someone; it may be them. Nothing below about them is yours to know.\n");
        }
        if (!s.forgotten.contains("mission_next") && !stage.purpose.isBlank()) {
            sb.append("- Your purpose now: ").append(stage.purpose).append('\n');
        }
        // Guidance: last three stages in full, earlier stages one line each, so the block stays bounded.
        List<String> guidance = new ArrayList<>();
        for (int i = 0; i <= idx; i++) {
            CampaignStage st = def.stages.get(i);
            if (st.guidance.isEmpty()) continue;
            if (i >= idx - 2) guidance.addAll(st.guidance);
            else guidance.add(st.guidance.get(0));
        }
        if (!guidance.isEmpty()) {
            sb.append("- What you know that helps:\n");
            for (String g : guidance) sb.append("  - ").append(g).append('\n');
        }
        List<String> reveal = new ArrayList<>();
        for (int i = 0; i <= idx; i++) reveal.addAll(def.stages.get(i).reveal);
        if (!reveal.isEmpty()) {
            sb.append("- What you may admit about yourself, and only if pressed, a piece at a time:\n");
            for (String r : reveal) sb.append("  - ").append(r).append('\n');
        }
        if (!s.arrivalVillage) {
            sb.append("- There is no village where you arrived; you came to the player in open country, a little way from where they woke. "
                    + "You have seen no well and no villager here. Say nothing about a village well, black growth on it, or villagers "
                    + "keeping away from you until you both stand in a village; then it is that village you mean.\n");
        }
        if (s.missingVillager != null) {
            sb.append("- One of the villagers is gone: ").append(s.missingVillager)
              .append(". Nobody mentions it. You know, and you will not say who; if asked you flinch and change the subject.\n");
        }
        if (s.flags.contains("has_soul")) {
            sb.append("- The player has brought soul sand or soul soil (or soul torches) out of the Nether. Good; say so once if it comes up, and no more about why.\n");
        }
        if (s.flags.contains("has_wool")) {
            sb.append("- The player carries wool now. Tell them to lay it as they go and to sneak; nothing hears steps on wool.\n");
        } else if (idx >= def.indexOf("deep_dark") && def.indexOf("deep_dark") >= 0) {
            sb.append("- The player carries no wool. Your warnings are only warnings until they do.\n");
        }
        if (!def.guidanceRule.isBlank()) sb.append("- ").append(def.guidanceRule).append('\n');
        if (!s.forgotten.isEmpty()) {
            sb.append("- What your returns have cost you:\n");
            for (CampaignDefinition.Forgetting f : def.forgetting) {
                if (s.forgotten.contains(f.forget) && !f.prompt.isBlank()) sb.append("  - ").append(f.prompt).append('\n');
            }
            if (s.forgotten.contains("player_name")) {
                sb.append("  - Any name for this player that appears in your notes or in earlier words of this conversation is one you can no longer place: never use it. Until they tell you their name again, they are 'traveller'.\n");
            }
        }
        if (partner != null && isChosen(server, partner)) {
            sb.append("- This player is the one the road chose.\n");
        } else if (partner != null) {
            sb.append("- This player is not the one the road chose; they travel with them. Be civil, help a little, reveal less.\n");
        }
        return sb.toString();
    }

    // ---- reset ----

    public void reset(MinecraftServer server) {
        CampaignState s = state(server);
        CampaignEvents.cleanup(server, s);
        s.reset();
        beats.clear();
        com.ayodehi.thehush.llm.UsageMeter.get().resetCampaign();
        AiVillagerEntity t = traveller(server, null);
        if (t != null) t.setEyesDark(false);
    }
}
