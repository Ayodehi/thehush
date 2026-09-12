package com.ayodehi.thehush.conversation;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.llm.ConversationEngine;
import com.ayodehi.thehush.llm.LlmService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is talking to which villager and routes chat between them.
 * Sessions are keyed by player; a villager can only hold one conversation at a time.
 */
public final class ConversationManager {
    private static final ConversationManager INSTANCE = new ConversationManager();

    /** player uuid -> villager uuid */
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();

    private ConversationManager() {}

    public static ConversationManager get() {
        return INSTANCE;
    }

    // ---- session lifecycle (server thread) ----

    public void engage(ServerPlayer player, AiVillagerEntity villager) {
        UUID existing = sessions.get(player.getUUID());
        if (villager.getUUID().equals(existing)) {
            tell(player, Component.literal("You are already talking to " + villager.speakerName()
                    + ". Just type in chat. Sneak and right-click to end.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        sessions.values().removeIf(v -> v.equals(villager.getUUID()));
        sessions.put(player.getUUID(), villager.getUUID());
        CampaignManager.get().onEngaged(villager, player);

        String greeting = villager.persona().greeting();
        if (greeting != null && !greeting.isBlank() && villager.rememberedMessages() == 0) {
            speak(villager, greeting);
        }
        tell(player, Component.literal("You are now talking to " + villager.speakerName()
                + ". Type in chat to speak; start a line with ! to talk past them. Sneak and right-click to end.")
                .withStyle(ChatFormatting.GRAY));
    }

    public void endSession(ServerPlayer player) {
        UUID villagerId = sessions.remove(player.getUUID());
        if (villagerId != null) {
            tell(player, Component.literal("Conversation ended.").withStyle(ChatFormatting.GRAY));
        }
    }

    public void endSession(UUID playerId) {
        pending.values().forEach(q -> q.removeIf(pd -> pd.player().equals(playerId)));
        pending.values().removeIf(ArrayDeque::isEmpty);
        sessions.remove(playerId);
    }

    public void clear() {
        sessions.clear();
    }

    public @Nullable ServerPlayer partnerOf(AiVillagerEntity villager) {
        for (Map.Entry<UUID, UUID> e : sessions.entrySet()) {
            if (e.getValue().equals(villager.getUUID())) {
                ServerPlayer p = villager.level().getServer() == null ? null
                        : villager.level().getServer().getPlayerList().getPlayer(e.getKey());
                boolean travellingTogether = villager.isFollowing() && p != null && p.equals(villager.followedPlayer());
                if (p != null && p.level() == villager.level()
                        && (travellingTogether || p.distanceTo(villager) <= Config.CONVERSATION_RADIUS.get() * 1.5)) {
                    return p;
                }
            }
        }
        return null;
    }

    public @Nullable AiVillagerEntity villagerFor(ServerPlayer player) {
        UUID id = sessions.get(player.getUUID());
        if (id == null) return null;
        Entity e = ((ServerLevel) player.level()).getEntity(id);
        return e instanceof AiVillagerEntity v && v.isAlive() ? v : null;
    }

    // ---- chat routing ----

    /**
     * Called from the chat event on the server thread. Returns true when the message was consumed by a
     * conversation (the caller cancels normal chat). A leading "!" always goes to normal chat.
     */
    public boolean handleChat(ServerPlayer player, String text) {
        MinecraftServer server = player.level().getServer();
        if (server == null || text.startsWith("!")) return false;
        if (!server.isSameThread()) {
            // Shouldn't happen (vanilla runs chat on the server thread) but never touch entities off-thread.
            if (!sessions.containsKey(player.getUUID())) return false;
            server.execute(() -> deliver(player, text, villagerFor(player)));
            return true;
        }
        AiVillagerEntity target = pickListener(player, text);
        if (target == null) return false;
        UUID previous = sessions.get(player.getUUID());
        if (!target.getUUID().equals(previous)) {
            sessions.values().removeIf(v -> v.equals(target.getUUID()));
            sessions.put(player.getUUID(), target.getUUID());
            tell(player, Component.literal("(you turn to " + target.speakerName() + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        return deliver(player, text, target);
    }

    /**
     * Who is the player talking to? Explicit session first when auto-listen is off; otherwise the villager
     * they addressed by name, then the current session, then the one they are looking at, then their
     * travelling companion, then the nearest one in earshot.
     */
    private @Nullable AiVillagerEntity pickListener(ServerPlayer player, String text) {
        AiVillagerEntity current = villagerFor(player);
        if (!Config.AUTO_LISTEN.get()) return current;

        double radius = Config.CONVERSATION_RADIUS.get();
        List<AiVillagerEntity> near = ((ServerLevel) player.level()).getEntitiesOfClass(AiVillagerEntity.class,
                player.getBoundingBox().inflate(radius), v -> v.isAlive() && v.distanceTo(player) <= radius);
        if (current != null && current.isFollowing() && player.equals(current.followedPlayer()) && !near.contains(current)) {
            near.add(current); // a companion lagging a few blocks behind still hears you
        }
        if (near.isEmpty()) return current; // deliver() will end a stale session politely

        for (AiVillagerEntity v : near) {
            if (Addressing.addressedByName(text, v.speakerName())) return v;
        }
        if (current != null && near.contains(current)) return current;

        AiVillagerEntity lookedAt = null;
        double bestDot = 0.94; // roughly a 20 degree cone
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (AiVillagerEntity v : near) {
            Vec3 to = v.getEyePosition().subtract(eye);
            if (to.lengthSqr() < 0.01) return v;
            double dot = to.normalize().dot(look);
            if (dot > bestDot) {
                bestDot = dot;
                lookedAt = v;
            }
        }
        if (lookedAt != null) return lookedAt;

        for (AiVillagerEntity v : near) {
            if (v.isFollowing() && player.equals(v.followedPlayer())) return v;
        }
        near.sort(java.util.Comparator.comparingDouble(player::distanceToSqr));
        return near.get(0);
    }

    /**
     * Hand the player's line to the villager. Returns false when nobody is there to hear it (gone, or out
     * of earshot): the session quietly lapses and the line goes out as ordinary chat, with no notice; the
     * silence is the answer.
     */
    /** A player's line that arrived while he was mid-thought; answered the moment he is free. */
    private record Pending(UUID player, String text) {}

    private final Map<UUID, ArrayDeque<Pending>> pending = new HashMap<>();

    private boolean deliver(ServerPlayer player, String text, @Nullable AiVillagerEntity villager) {
        return deliver(player, text, villager, true);
    }

    private boolean deliver(ServerPlayer player, String text, @Nullable AiVillagerEntity villager, boolean echo) {
        if (villager == null) {
            sessions.remove(player.getUUID());
            return false;
        }
        double radius = Config.CONVERSATION_RADIUS.get();
        boolean travellingTogether = villager.isFollowing() && player.equals(villager.followedPlayer());
        if (!travellingTogether && player.distanceTo(villager) > radius) {
            sessions.remove(player.getUUID());
            return false;
        }

        // Echo the player's line so nearby players still see it (normal chat was cancelled).
        if (echo) broadcastNear(villager, Component.literal("<" + player.getName().getString() + "> ")
                .append(Component.literal(text).withStyle(ChatFormatting.ITALIC)));

        villager.setTalkingTo(player);
        CampaignManager.get().onEngaged(villager, player);
        if (com.ayodehi.thehush.campaign.NightSilence.holdsTongue(villager)) {
            // Night, and the sculk is near: the line is heard and never answered, and never remembered.
            com.ayodehi.thehush.campaign.NightSilence.onSilenced(villager, player);
            return true;
        }
        ConversationEngine engine = villager.engine();
        if (engine == null) {
            speak(villager, "(I have no voice today: " + LlmService.get().describe() + ")");
            return true;
        }
        if (engine.isBusy()) {
            // Mid-thought (often an unprompted remark): keep the line and answer it when this exchange ends.
            pending.computeIfAbsent(villager.getUUID(), k -> new ArrayDeque<>()).addLast(new Pending(player.getUUID(), text));
            player.sendSystemMessage(Component.literal(villager.speakerName() + " is still thinking; he heard you...")
                    .withStyle(ChatFormatting.GRAY), true);
            return true;
        }

        player.sendSystemMessage(Component.literal(villager.speakerName() + " is thinking...")
                .withStyle(ChatFormatting.GRAY), true);
        villager.setAwaitingReplyFor(player);
        String userText = "[" + CampaignManager.get().displayName(villager, player) + "] " + text;
        MinecraftServer server = player.level().getServer();
        engine.respond(villager.systemPrompt(), userText).whenComplete((reply, error) -> server.execute(() -> {
            AiVillagerEntity live = villager.current(); // he may have crossed a dimension while thinking
            live.setAwaitingReplyFor(null);
            if (error != null) {
                TheHushMod.LOGGER.warn("Villager {} failed to reply", live.speakerName(), error);
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                speak(live, "(" + live.speakerName() + " mumbles something you can't make out: "
                        + cause.getMessage() + ")");
            } else if (live.isAlive()) {
                if (live != villager) live.adoptHistory(engine);
                speak(live, reply);
                if (Intent.wantsCoordinates(text)) pointOutPlaces(live, reply);
                else live.drainLocated();
                keepHisWord(live, player, text);
            }
            answerPending(server, live);
        }));
        return true;
    }

    /** He has finished a thought: if someone spoke to him meanwhile, that comes next. Several lines from one player are joined. */
    private void answerPending(MinecraftServer server, AiVillagerEntity live) {
        ArrayDeque<Pending> q = pending.get(live.getUUID());
        if (q == null || q.isEmpty()) return;
        Pending first = q.pollFirst();
        StringBuilder text = new StringBuilder(first.text());
        while (!q.isEmpty() && q.peekFirst().player().equals(first.player())) text.append('\n').append(q.pollFirst().text());
        if (q.isEmpty()) pending.remove(live.getUUID());
        ServerPlayer player = server.getPlayerList().getPlayer(first.player());
        if (player == null || !live.isAlive()) return;
        deliver(player, text.toString(), live, false);
    }

    /**
     * The model sometimes says "I'll stay" or "lead on" without calling the tool that makes it true. When
     * the player's words could hardly mean anything else, make it true anyway.
     */
    private void keepHisWord(AiVillagerEntity villager, ServerPlayer player, String text) {
        Intent.Travel wanted = Intent.travel(text);
        if (wanted == Intent.Travel.STAY && villager.isFollowing() && player.equals(villager.followedPlayer())) {
            villager.stopFollowing();
            TheHushMod.LOGGER.info("{} said he would stay but kept following; stopped him", villager.speakerName());
        } else if (wanted == Intent.Travel.COME && !villager.isFollowing() && player.level() == villager.level()
                && player.distanceTo(villager) <= 32) {
            villager.startFollowing(player);
            TheHushMod.LOGGER.info("{} was asked to come and did not; now following", villager.speakerName());
        }
    }

    // ---- unprompted remarks ----

    /** Hand the villager something it noticed; it may answer with one line or stay silent. */
    public void remark(AiVillagerEntity villager, ServerPlayer audience, String event) {
        ConversationEngine engine = villager.engine();
        if (engine == null || engine.isBusy()) return;
        if (pending.containsKey(villager.getUUID())) return; // someone is waiting on him; the world can wait
        if (com.ayodehi.thehush.voice.VoiceService.get().isSpeaking(villager)) return; // not over his own voice
        MinecraftServer server = villager.level().getServer();
        if (server == null) return;
        villager.setTalkingTo(audience);
        String prompt = "[world] " + event + " (If this is worth a word from you, say one short line in character"
                + " to " + CampaignManager.get().displayName(villager, audience) + "; otherwise reply with only ... to stay silent.)";
        engine.respond(villager.systemPrompt(), prompt).whenComplete((reply, error) -> server.execute(() -> {
            AiVillagerEntity live = villager.current();
            if (error != null) {
                TheHushMod.LOGGER.debug("ambient remark failed for {}", villager.speakerName(), error);
            } else if (live.isAlive() && !isSilence(reply)) {
                if (live != villager) live.adoptHistory(engine);
                speak(live, reply);
                live.drainLocated(); // unprompted remarks never come with numbers
            }
            answerPending(server, live);
        }));
    }

    private static boolean isSilence(String reply) {
        if (reply == null) return true;
        String stripped = reply.replaceAll("[.\\u2026\\s*()\\[\\]\\-]", "");
        return stripped.isEmpty();
    }

    // ---- output ----

    public void speak(AiVillagerEntity villager, String line) {
        villager.markSpoke();
        com.ayodehi.thehush.debug.DebugBridge.get().record("npc", villager.speakerName(), line);
        MutableComponent msg = Component.literal("<" + villager.speakerName() + "> ").withStyle(ChatFormatting.GOLD)
                .append(ChatText.linkify(com.ayodehi.thehush.voice.Speech.shown(line), ChatFormatting.WHITE));
        // With a voice, the text waits for the audio so they arrive together; without one it goes out now.
        com.ayodehi.thehush.voice.VoiceService.get().say(villager, line, () -> broadcastNear(villager.current(), msg));
    }

    /** If the locate tool found something the reply didn't quote as digits, add a clickable line for it. */
    private void pointOutPlaces(AiVillagerEntity villager, String reply) {
        for (AiVillagerEntity.LocatedPlace place : villager.drainLocated()) {
            if (ChatText.mentionsCoords(reply, place.x(), place.z())) continue;
            MutableComponent msg = Component.literal("<" + villager.speakerName() + "> ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("*points* " + place.label() + ": ").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
                    .append(ChatText.coords(place.x(), place.y(), place.z()));
            broadcastNear(villager, msg);
        }
    }

    private void broadcastNear(AiVillagerEntity villager, Component msg) {
        double hearing = Config.CONVERSATION_RADIUS.get() * 2.5;
        for (ServerPlayer p : ((ServerLevel) villager.level()).players()) {
            if (p.distanceTo(villager) <= hearing) {
                p.sendSystemMessage(msg);
            }
        }
    }

    private static void tell(ServerPlayer player, Component msg) {
        player.sendSystemMessage(msg);
    }

    /** A grey narrator line for one player. */
    public void tellQuietly(ServerPlayer player, String text) {
        com.ayodehi.thehush.debug.DebugBridge.get().record("narrator", player.getName().getString(), text);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
