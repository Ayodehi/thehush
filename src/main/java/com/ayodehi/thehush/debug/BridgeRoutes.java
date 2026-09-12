package com.ayodehi.thehush.debug;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.campaign.CampaignDefinition;
import com.ayodehi.thehush.campaign.CampaignEvents;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignRegistry;
import com.ayodehi.thehush.campaign.CampaignState;
import com.ayodehi.thehush.campaign.Quiet;
import com.ayodehi.thehush.commands.Snapshot;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.debug.DebugBridge.Reply;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.EchoEntity;
import com.ayodehi.thehush.entity.EchoHunts;
import com.ayodehi.thehush.entity.PilgrimEntity;
import com.ayodehi.thehush.entity.ReturnRegistry;
import com.ayodehi.thehush.entity.UnsaidEntity;
import com.ayodehi.thehush.llm.LlmService;
import com.ayodehi.thehush.llm.UsageMeter;
import com.ayodehi.thehush.memory.VillagerMemory;
import com.ayodehi.thehush.persona.PersonaRegistry;
import com.ayodehi.thehush.voice.VoiceService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/** The bridge's routes. Every function here runs on the server thread. */
final class BridgeRoutes {
    private BridgeRoutes() {}

    static Map<String, BiFunction<MinecraftServer, MiniHttp.Request, JsonElement>> all() {
        Map<String, BiFunction<MinecraftServer, MiniHttp.Request, JsonElement>> m = new LinkedHashMap<>();
        m.put("/status", BridgeRoutes::status);
        m.put("/players", BridgeRoutes::players);
        m.put("/entities", BridgeRoutes::entities);
        m.put("/villagers", BridgeRoutes::villagers);
        m.put("/villager", BridgeRoutes::villager);
        m.put("/campaign", BridgeRoutes::campaign);
        m.put("/snapshot", BridgeRoutes::snapshot);
        m.put("/block", BridgeRoutes::block);
        m.put("/command", BridgeRoutes::command);
        m.put("/chat", BridgeRoutes::chat);
        m.put("/usage", BridgeRoutes::usage);
        m.put("/config", BridgeRoutes::config);
        return m;
    }

    // ---- world overview ----

    private static JsonElement status(MinecraftServer server, MiniHttp.Request req) {
        JsonObject o = new JsonObject();
        o.addProperty("bridge", DebugBridge.VERSION);
        o.addProperty("dedicated", server.isDedicatedServer());
        o.addProperty("tick", server.getTickCount());
        o.addProperty("msptAverage", round(server.getAverageTickTimeNanos() / 1_000_000.0));
        o.addProperty("serverDirectory", server.getServerDirectory().toAbsolutePath().normalize().toString());
        o.addProperty("worldDirectory", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        o.addProperty("modDataDirectory", server.getWorldPath(LevelResource.ROOT).resolve("thehush").toAbsolutePath().normalize().toString());
        JsonArray levels = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            JsonObject l = new JsonObject();
            l.addProperty("id", level.dimension().identifier().toString());
            l.addProperty("players", level.players().size());
            l.addProperty("loadedChunks", level.getChunkSource().getLoadedChunksCount());
            int entities = 0;
            for (Entity ignored : level.getAllEntities()) entities++;
            l.addProperty("entities", entities);
            l.addProperty("gameTime", level.getGameTime());
            l.addProperty("dayTime", level.getOverworldClockTime() % 24000L);
            l.addProperty("day", level.getOverworldClockTime() / 24000L);
            l.addProperty("daylight", level.isBrightOutside());
            l.addProperty("raining", level.isRaining());
            l.addProperty("thundering", level.isThundering());
            levels.add(l);
        }
        o.add("levels", levels);
        JsonArray players = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getName().getString());
        o.add("players", players);
        o.addProperty("provider", LlmService.get().describe());
        o.addProperty("voice", VoiceService.get().describe());
        o.add("personas", DebugBridge.GSON.toJsonTree(PersonaRegistry.get().ids()));
        CampaignState s = CampaignManager.get().state(server);
        JsonObject c = new JsonObject();
        c.addProperty("started", s.started());
        c.addProperty("stage", s.stage);
        c.addProperty("chosen", s.chosenName);
        c.addProperty("deaths", s.deaths);
        c.add("flags", DebugBridge.GSON.toJsonTree(s.flags));
        c.addProperty("hunts", EchoHunts.describe(server));
        c.addProperty("hush", CampaignEvents.hush().describe());
        c.addProperty("pendingReturns", ReturnRegistry.get().pendingCount(server));
        o.add("campaign", c);
        o.addProperty("transcriptLastSeq", DebugBridge.get().lastSeq());
        return o;
    }

    private static JsonElement players(MinecraftServer server, MiniHttp.Request req) {
        JsonArray arr = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            JsonObject o = entityJson(p, null);
            o.addProperty("gameMode", p.gameMode().getName());
            o.addProperty("food", p.getFoodData().getFoodLevel());
            o.addProperty("xpLevel", p.experienceLevel);
            o.addProperty("sneaking", p.isCrouching());
            o.addProperty("sprinting", p.isSprinting());
            o.addProperty("mainHand", itemName(p));
            o.addProperty("op", server.getPlayerList().isOp(p.nameAndId()));
            o.addProperty("chosen", CampaignManager.get().isChosen(server, p));
            AiVillagerEntity v = ConversationManager.get().villagerFor(p);
            o.addProperty("talkingTo", v == null ? null : v.speakerName());
            o.addProperty("talkingToUuid", v == null ? null : v.getStringUUID());
            arr.add(o);
        }
        return arr;
    }

    /** Entities near a player (?player=) or a point (?x&y&z&dimension=), within ?radius (24), optionally ?type=substring. */
    private static JsonElement entities(MinecraftServer server, MiniHttp.Request req) {
        double radius = DebugBridge.parseDouble(req.param("radius", "24"));
        String typeFilter = req.param("type");
        ServerLevel level;
        Vec3 center;
        Entity except = null;
        String playerName = req.param("player");
        if (playerName != null) {
            ServerPlayer p = player(server, playerName);
            level = (ServerLevel) p.level();
            center = p.position();
            except = p;
        } else if (req.param("x") != null) {
            level = level(server, req.param("dimension"));
            center = new Vec3(DebugBridge.parseDouble(req.param("x")), DebugBridge.parseDouble(req.param("y")), DebugBridge.parseDouble(req.param("z")));
        } else {
            ServerPlayer p = anyPlayer(server);
            level = (ServerLevel) p.level();
            center = p.position();
            except = p;
        }
        AABB box = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
        JsonArray arr = new JsonArray();
        for (Entity e : level.getEntities(except, box, en -> en.position().distanceTo(center) <= radius)) {
            String type = typeId(e);
            if (typeFilter != null && !type.contains(typeFilter.toLowerCase(Locale.ROOT))
                    && !(e.hasCustomName() && e.getCustomName().getString().toLowerCase(Locale.ROOT).contains(typeFilter.toLowerCase(Locale.ROOT)))) continue;
            arr.add(entityJson(e, center));
        }
        JsonObject o = new JsonObject();
        o.addProperty("dimension", level.dimension().identifier().toString());
        o.add("center", vec(center));
        o.addProperty("radius", radius);
        o.addProperty("count", arr.size());
        o.add("entities", arr);
        return o;
    }

    private static JsonElement villagers(MinecraftServer server, MiniHttp.Request req) {
        JsonArray arr = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof AiVillagerEntity v) arr.add(villagerJson(server, v, false, false));
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", arr.size());
        o.add("villagers", arr);
        o.addProperty("pendingReturns", ReturnRegistry.get().pendingCount(server));
        return o;
    }

    /** One villager by ?uuid=, or the one ?player= is talking to (default: the campaign's Traveller). ?prompt=1 adds the system prompt. */
    private static JsonElement villager(MinecraftServer server, MiniHttp.Request req) {
        AiVillagerEntity v = null;
        String uuid = req.param("uuid");
        String playerName = req.param("player");
        if (uuid != null) {
            Entity e = findEntity(server, uuid);
            if (!(e instanceof AiVillagerEntity av)) throw new Reply(404, "No talking villager with uuid " + uuid + " is loaded.");
            v = av;
        } else if (playerName != null) {
            ServerPlayer p = player(server, playerName);
            v = ConversationManager.get().villagerFor(p);
            if (v == null) v = CampaignManager.get().traveller(server, p);
            if (v == null) throw new Reply(404, p.getName().getString() + " is not talking to anyone and no Traveller is loaded near them.");
        } else {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                v = ConversationManager.get().villagerFor(p);
                if (v == null) v = CampaignManager.get().traveller(server, p);
                if (v != null) break;
            }
            if (v == null) throw new Reply(404, "No talking villager is loaded; pass uuid= or player=.");
        }
        return villagerJson(server, v, true, req.param("prompt") != null);
    }

    private static JsonElement campaign(MinecraftServer server, MiniHttp.Request req) {
        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        JsonObject o = new JsonObject();
        o.add("state", DebugBridge.GSON.toJsonTree(s));
        o.addProperty("file", s.file() == null ? null : s.file().toAbsolutePath().normalize().toString());
        CampaignDefinition d = cm.definition(server);
        if (d == null) d = CampaignRegistry.get().find(Config.DEFAULT_CAMPAIGN_FALLBACK);
        if (d != null) {
            JsonObject def = new JsonObject();
            def.addProperty("id", d.id);
            def.addProperty("title", d.title);
            JsonArray stages = new JsonArray();
            for (var st : d.stages) {
                JsonObject so = new JsonObject();
                so.addProperty("id", st.id);
                so.addProperty("title", st.title);
                so.addProperty("current", st.id.equals(s.stage));
                stages.add(so);
            }
            def.add("stages", stages);
            o.add("definition", def);
        }
        o.add("known", DebugBridge.GSON.toJsonTree(CampaignRegistry.get().ids()));
        o.addProperty("hunts", EchoHunts.describe(server));
        o.addProperty("hush", CampaignEvents.hush().describe());
        o.addProperty("pendingReturns", ReturnRegistry.get().pendingCount(server));
        ServerPlayer chosen = cm.chosen(server);
        o.addProperty("chosenOnline", chosen == null ? null : chosen.getName().getString());
        ServerLevel quiet = Quiet.level(server);
        o.addProperty("quietLoaded", quiet != null);
        return o;
    }

    /** The same text picture /hush campaign snapshot writes, returned instead of (as well as) written. */
    private static JsonElement snapshot(MinecraftServer server, MiniHttp.Request req) {
        String name = req.param("player");
        ServerPlayer p = name == null ? anyPlayer(server) : player(server, name);
        JsonObject o = new JsonObject();
        o.addProperty("player", p.getName().getString());
        o.addProperty("text", Snapshot.render(p));
        if (req.param("write") != null) o.addProperty("file", Snapshot.write(p));
        return o;
    }

    /** The block at ?x&y&z (&dimension=); with ?radius=n, every non-air block in the cube around it. */
    private static JsonElement block(MinecraftServer server, MiniHttp.Request req) {
        ServerLevel level = level(server, req.param("dimension"));
        BlockPos at = new BlockPos((int) Math.floor(DebugBridge.parseDouble(DebugBridge.required(req, new JsonObject(), "x"))),
                (int) Math.floor(DebugBridge.parseDouble(DebugBridge.required(req, new JsonObject(), "y"))),
                (int) Math.floor(DebugBridge.parseDouble(DebugBridge.required(req, new JsonObject(), "z"))));
        int radius = (int) DebugBridge.parseLong(req.param("radius", "0"));
        if (radius > 8) throw new Reply(400, "radius is at most 8.");
        JsonObject o = new JsonObject();
        o.addProperty("dimension", level.dimension().identifier().toString());
        o.add("at", blockJson(level, at));
        if (radius > 0) {
            JsonArray arr = new JsonArray();
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-radius, -radius, -radius), at.offset(radius, radius, radius))) {
                if (!level.getBlockState(pos).isAir()) arr.add(blockJson(level, pos.immutable()));
            }
            o.add("blocks", arr);
        }
        return o;
    }

    // ---- actions ----

    /** Runs a command as the server (or positioned as ?as=player with owner permissions) and returns its output. */
    private static JsonElement command(MinecraftServer server, MiniHttp.Request req) {
        JsonObject body = DebugBridge.bodyJson(req);
        String command = DebugBridge.required(req, body, "command");
        String as = DebugBridge.arg(req, body, "as");
        List<String> output = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack stack;
        if (as == null) {
            stack = server.createCommandSourceStack().withSource(capture);
        } else {
            ServerPlayer p = player(server, as);
            stack = new CommandSourceStack(capture, p.position(), p.getRotationVector(), (ServerLevel) p.level(),
                    LevelBasedPermissionSet.OWNER, p.getName().getString(), p.getDisplayName(), server, p);
        }
        server.getCommands().performPrefixedCommand(stack, command);
        JsonObject o = new JsonObject();
        o.addProperty("command", command);
        o.addProperty("as", as == null ? "Server" : as);
        o.add("output", DebugBridge.GSON.toJsonTree(output));
        return o;
    }

    /** Says ?text as ?player, exactly as if they had typed it in chat; villagers in earshot reply asynchronously (see /transcript). */
    private static JsonElement chat(MinecraftServer server, MiniHttp.Request req) {
        JsonObject body = DebugBridge.bodyJson(req);
        String text = DebugBridge.required(req, body, "text");
        String name = DebugBridge.arg(req, body, "player");
        ServerPlayer p = name == null ? anyPlayer(server) : player(server, name);
        DebugBridge.get().record("chat", p.getName().getString(), text + "  (injected)");
        UnsaidEntity.onSomeoneSpoke(p);
        CampaignManager.get().onChat(p, text);
        boolean consumed = ConversationManager.get().handleChat(p, text);
        if (!consumed) {
            Component msg = Component.literal("<" + p.getName().getString() + "> " + text);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) other.sendSystemMessage(msg);
        }
        JsonObject o = new JsonObject();
        o.addProperty("player", p.getName().getString());
        o.addProperty("consumedByVillager", consumed);
        AiVillagerEntity v = ConversationManager.get().villagerFor(p);
        o.addProperty("talkingTo", v == null ? null : v.speakerName());
        o.addProperty("transcriptSeq", DebugBridge.get().lastSeq());
        o.addProperty("note", consumed ? "The reply arrives in /transcript after the API call." : "Nobody in earshot; sent as plain chat.");
        return o;
    }

    private static JsonElement usage(MinecraftServer server, MiniHttp.Request req) {
        JsonObject o = new JsonObject();
        o.addProperty("provider", LlmService.get().describe());
        o.add("session", DebugBridge.GSON.toJsonTree(UsageMeter.get().session()));
        o.add("campaign", DebugBridge.GSON.toJsonTree(UsageMeter.get().campaign()));
        return o;
    }

    /** Every config value, with keys named like apiKey masked. */
    private static JsonElement config(MinecraftServer server, MiniHttp.Request req) {
        JsonObject o = new JsonObject();
        for (Field f : Config.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !ModConfigSpec.ConfigValue.class.isAssignableFrom(f.getType())) continue;
            try {
                ModConfigSpec.ConfigValue<?> value = (ModConfigSpec.ConfigValue<?>) f.get(null);
                String path = String.join(".", value.getPath());
                Object v = value.get();
                if (path.toLowerCase(Locale.ROOT).contains("apikey") && !path.toLowerCase(Locale.ROOT).contains("envvar")) {
                    v = v == null || v.toString().isBlank() ? "" : "(set, " + v.toString().length() + " chars)";
                }
                o.add(path, DebugBridge.GSON.toJsonTree(v));
            } catch (IllegalAccessException ignored) {
            }
        }
        o.addProperty("bridgeProperty", System.getProperty("thehush.bridge"));
        return o;
    }

    // ---- helpers ----

    static ServerPlayer player(MinecraftServer server, String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p == null) {
            try {
                p = server.getPlayerList().getPlayer(UUID.fromString(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (p == null) {
            List<String> online = new ArrayList<>();
            for (ServerPlayer x : server.getPlayerList().getPlayers()) online.add(x.getName().getString());
            throw new Reply(404, "No player '" + name + "' online. Online: " + online);
        }
        return p;
    }

    static ServerPlayer anyPlayer(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) throw new Reply(404, "No player is online.");
        return players.getFirst();
    }

    static ServerLevel level(MinecraftServer server, @Nullable String id) {
        if (id == null || id.isEmpty()) {
            var players = server.getPlayerList().getPlayers();
            return players.isEmpty() ? server.overworld() : (ServerLevel) players.getFirst().level();
        }
        String full = id.contains(":") ? id : "minecraft:" + id;
        ServerLevel level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(full)));
        if (level == null) {
            List<String> known = new ArrayList<>();
            for (ServerLevel l : server.getAllLevels()) known.add(l.dimension().identifier().toString());
            throw new Reply(404, "No dimension '" + id + "'. Loaded: " + known);
        }
        return level;
    }

    static @Nullable Entity findEntity(MinecraftServer server, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new Reply(400, "Not a uuid: " + uuid);
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntities().get(id);
            if (e != null) return e;
        }
        return null;
    }

    static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    static JsonObject entityJson(Entity e, @Nullable Vec3 from) {
        JsonObject o = new JsonObject();
        o.addProperty("type", typeId(e));
        o.addProperty("uuid", e.getStringUUID());
        o.addProperty("name", e.hasCustomName() ? e.getCustomName().getString() : e.getName().getString());
        o.addProperty("dimension", e.level().dimension().identifier().toString());
        o.add("pos", vec(e.position()));
        o.addProperty("yaw", round(e.getYRot()));
        o.addProperty("pitch", round(e.getXRot()));
        if (from != null) o.addProperty("distance", round(e.position().distanceTo(from)));
        if (e instanceof LivingEntity le) {
            o.addProperty("health", round(le.getHealth()));
            o.addProperty("maxHealth", round(le.getMaxHealth()));
        }
        if (!e.entityTags().isEmpty()) o.add("tags", DebugBridge.GSON.toJsonTree(e.entityTags()));
        if (e instanceof AiVillagerEntity v) {
            o.addProperty("persona", v.personaId());
            o.addProperty("speakerName", v.speakerName());
            o.addProperty("following", v.isFollowing() && v.followedPlayer() != null ? v.followedPlayer().getName().getString() : null);
            o.addProperty("leading", v.isLeading());
            ServerPlayer partner = ConversationManager.get().partnerOf(v);
            o.addProperty("talkingTo", partner == null ? null : partner.getName().getString());
            o.addProperty("navigation", v.getNavigation().isDone() ? "idle" : v.getNavigation().isStuck() ? "stuck" : "moving");
            o.addProperty("eyesDark", v.eyesDark());
            o.addProperty("inCampaign", CampaignManager.get().takesPart(v));
        } else if (e instanceof EchoEntity echo) {
            o.addProperty("describe", echo.describe());
        } else if (e instanceof PilgrimEntity p) {
            o.addProperty("frozen", p.isFrozen());
        }
        return o;
    }

    static JsonObject villagerJson(MinecraftServer server, AiVillagerEntity v, boolean detail, boolean prompt) {
        JsonObject o = entityJson(v, null);
        o.addProperty("historyMessages", v.rememberedMessages());
        o.addProperty("notes", v.memory().size());
        o.addProperty("memoryFile", v.memory().file().toAbsolutePath().normalize().toString());
        o.addProperty("ticksSinceSpoke", v.ticksSinceSpoke());
        if (detail) {
            o.add("history", v.historyJson());
            JsonObject mem = new JsonObject();
            JsonObject byPlayer = new JsonObject();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                List<VillagerMemory.Note> notes = v.memory().notesAbout(p.getUUID());
                if (!notes.isEmpty()) byPlayer.add(p.getName().getString(), DebugBridge.GSON.toJsonTree(notes));
            }
            mem.add("aboutOnlinePlayers", byPlayer);
            mem.add("general", DebugBridge.GSON.toJsonTree(v.memory().generalNotes()));
            mem.addProperty("taken", v.memory().takenCount());
            mem.addProperty("archived", v.memory().archivedCount());
            o.add("memory", mem);
            if (prompt) o.addProperty("systemPrompt", v.systemPrompt());
        }
        return o;
    }

    static JsonObject blockJson(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        o.addProperty("block", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        if (!st.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            st.getValues().forEach(v -> props.addProperty(v.property().getName(), v.value().toString().toLowerCase(Locale.ROOT)));
            o.add("properties", props);
        }
        o.addProperty("light", level.getMaxLocalRawBrightness(pos));
        if (level.getBlockEntity(pos) != null) o.addProperty("blockEntity", true);
        return o;
    }

    static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(round(v.x));
        a.add(round(v.y));
        a.add(round(v.z));
        return a;
    }

    static String itemName(LivingEntity e) {
        var stack = e.getMainHandItem();
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
    }

    static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
