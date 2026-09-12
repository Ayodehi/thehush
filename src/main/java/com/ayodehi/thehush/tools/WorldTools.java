package com.ayodehi.thehush.tools;

import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.llm.ToolSpec;
import com.ayodehi.thehush.memory.VillagerMemory;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** The villager's senses and simple actions. Everything here runs on the server thread. */
public final class WorldTools {
    private WorldTools() {}

    static List<NpcTool> all() {
        return List.of(
                new TimeAndWeather(),
                new Location(),
                new InspectPlayer(),
                new PlayerInventory(),
                new NearbyEntities(),
                new Surroundings(),
                new Locate(),
                new FindBlock(),
                new LeadPlayerTo(),
                new StopLeading(),
                new SendPlayerTo(),
                new GiveItem(),
                new FollowPlayer(),
                new StopFollowing(),
                new Remember(),
                new Forget(),
                new WalkToPlayer(),
                new FacePlayer());
    }

    // ---------- senses ----------

    static final class TimeAndWeather implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("get_time_and_weather",
                    "Current in-game day, time of day, and weather where you are standing.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            ServerLevel level = ctx.level();
            long dayTime = level.getOverworldClockTime();
            long day = dayTime / 24000L + 1;
            long t = dayTime % 24000L;
            String phase;
            if (t < 1000) phase = "sunrise";
            else if (t < 6000) phase = "morning";
            else if (t < 9000) phase = "midday";
            else if (t < 12000) phase = "afternoon";
            else if (t < 13500) phase = "sunset";
            else if (t < 18000) phase = "early night";
            else if (t < 22500) phase = "deep night";
            else phase = "just before dawn";
            String weather = level.isThundering() ? "thunderstorm" : level.isRaining() ? "raining" : "clear";
            return "Day " + day + ", " + phase + " (" + clock(t) + "). Weather: " + weather + ". "
                    + (level.isBrightOutside() ? "It is light outside." : "It is dark outside; monsters may be about.");
        }

        private static String clock(long t) {
            long hour = ((t / 1000) + 6) % 24;
            long minute = (t % 1000) * 60 / 1000;
            return String.format("%02d:%02d", hour, minute);
        }
    }

    static final class Location implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("get_location",
                    "Where you and the player are: coordinates, biome, dimension, and how far away the player is.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            BlockPos me = ctx.npc().blockPosition();
            BlockPos them = ctx.player().blockPosition();
            String biome = ctx.level().getBiome(me).getRegisteredName().replace("minecraft:", "");
            String dim = ctx.level().dimension().identifier().toString().replace("minecraft:", "");
            return "You are at " + pos(me) + " in a " + biome.replace('_', ' ') + " biome (" + dim + "). "
                    + ctx.player().getName().getString() + " is at " + pos(them) + ", about "
                    + Math.round(ctx.npc().distanceTo(ctx.player())) + " blocks away.";
        }
    }

    static final class InspectPlayer implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("inspect_player",
                    "Look the player over: health, hunger, experience, what they hold, and whether they look hurt or well.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            ServerPlayer p = ctx.player();
            float hp = p.getHealth(), max = p.getMaxHealth();
            int food = p.getFoodData().getFoodLevel();
            String condition = hp >= max * 0.9 ? "looks healthy" : hp >= max * 0.5 ? "looks a bit battered" : "looks badly hurt";
            String hunger = food >= 18 ? "well fed" : food >= 10 ? "peckish" : "starving";
            return p.getName().getString() + " " + condition + " (" + Math.round(hp) + "/" + Math.round(max)
                    + " health), " + hunger + " (" + food + "/20 hunger), experience level " + p.experienceLevel
                    + ". Main hand: " + stack(p.getMainHandItem()) + ". Off hand: " + stack(p.getOffhandItem()) + "."
                    + (p.isSleeping() ? " They are asleep." : "");
        }
    }

    /**
     * A glance at what a player carries and wears, for prompts that need grounding without a tool call:
     * "They carry 64 Iron Ingot, 12 Torch, ...; wear Iron Helmet, Iron Chestplate; hold Iron Pickaxe." The
     * biggest {@code maxKinds} stacks are named; the rest are counted.
     */
    public static String carrySummary(ServerPlayer player, int maxKinds) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        if (counts.isEmpty()) sb.append("Their pockets are empty");
        else {
            List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            sb.append("They carry ");
            int shown = Math.min(maxKinds, top.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                sb.append(top.get(i).getValue()).append(" ").append(top.get(i).getKey());
            }
            if (top.size() > shown) sb.append(" and ").append(top.size() - shown).append(" other kind(s) of thing");
        }
        List<String> worn = new ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack w = player.getItemBySlot(slot);
            if (!w.isEmpty()) worn.add(w.getHoverName().getString());
        }
        sb.append(worn.isEmpty() ? "; wear no armour" : "; wear " + String.join(", ", worn));
        ItemStack held = player.getMainHandItem();
        sb.append(held.isEmpty() ? "; hold nothing." : "; hold " + held.getHoverName().getString() + ".");
        return sb.toString();
    }

    static final class PlayerInventory implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("get_player_inventory",
                    "Peek at what the player is carrying in their inventory (item names and counts).");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            Map<String, Integer> counts = new TreeMap<>();
            for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty()) counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
            }
            if (counts.isEmpty()) return "Their pockets are empty.";
            StringBuilder sb = new StringBuilder("They carry: ");
            counts.forEach((name, n) -> sb.append(n).append(" ").append(name).append(", "));
            sb.setLength(sb.length() - 2);
            return sb.append('.').toString();
        }
    }

    static final class NearbyEntities implements NpcTool {
        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject radius = new JsonObject();
            radius.addProperty("type", "integer");
            radius.addProperty("description", "Search radius in blocks, 4 to 48. Default 16.");
            props.add("radius", radius);
            schema.add("properties", props);
            return new ToolSpec("get_nearby_creatures",
                    "Creatures, monsters, villagers, and other players near you, with rough direction and distance.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            int radius = input.has("radius") ? Math.clamp(input.get("radius").getAsInt(), 4, 48) : 16;
            Entity me = ctx.npc();
            AABB box = me.getBoundingBox().inflate(radius);
            List<LivingEntity> found = ctx.level().getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != me && e.isAlive());
            if (found.isEmpty()) return "Nothing living within " + radius + " blocks besides you.";
            found.sort(Comparator.comparingDouble(me::distanceToSqr));
            Map<String, List<String>> byKind = new LinkedHashMap<>();
            for (LivingEntity e : found) {
                String label = describe(e);
                byKind.computeIfAbsent(label, k -> new ArrayList<>())
                        .add(Math.round(me.distanceTo(e)) + "m " + direction(me, e));
            }
            StringBuilder sb = new StringBuilder("Within " + radius + " blocks: ");
            byKind.forEach((kind, list) -> {
                sb.append(list.size()).append("x ").append(kind);
                if (list.size() <= 3) sb.append(" (").append(String.join("; ", list)).append(")");
                else sb.append(" (nearest ").append(list.get(0)).append(")");
                sb.append(", ");
            });
            sb.setLength(sb.length() - 2);
            return sb.append('.').toString();
        }

        private static String describe(LivingEntity e) {
            if (e instanceof Player) return "player " + e.getName().getString();
            EntityType<?> type = e.getType();
            String name = type.getDescription().getString();
            return type.getCategory() == MobCategory.MONSTER ? name + " [hostile]" : name;
        }
    }

    static final class Surroundings implements NpcTool {
        private static final int RADIUS = 6;

        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("look_around",
                    "Describe the blocks and structures immediately around you (within about six blocks).");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            BlockPos c = ctx.npc().blockPosition();
            Map<String, Integer> counts = new TreeMap<>();
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-RADIUS, -2, -RADIUS), c.offset(RADIUS, 4, RADIUS))) {
                Block b = ctx.level().getBlockState(p).getBlock();
                if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
                counts.merge(b.getName().getString(), 1, Integer::sum);
            }
            if (counts.isEmpty()) return "Nothing but open air around you.";
            List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder sb = new StringBuilder("Around you: ");
            int shown = 0;
            for (Map.Entry<String, Integer> e : top) {
                if (shown++ == 12) break;
                sb.append(e.getValue()).append(" ").append(e.getKey()).append(", ");
            }
            sb.setLength(sb.length() - 2);
            return sb.append('.').toString();
        }
    }


    /** The villager's equivalent of /locate structure|biome|poi. */
    static final class Locate implements NpcTool {
        private static final int STRUCTURE_RADIUS_CHUNKS = 100;
        private static final int BIOME_RADIUS = 6400;
        private static final int POI_RADIUS = 256;
        private static final int MAX_SUGGESTIONS = 12;

        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject kind = new JsonObject();
            kind.addProperty("type", "string");
            JsonArrayHelper.enumOf(kind, "structure", "biome", "poi");
            kind.addProperty("description", "What to search for: a generated structure, a biome, or a point of interest block.");
            props.add("kind", kind);
            JsonObject target = new JsonObject();
            target.addProperty("type", "string");
            target.addProperty("description", "Registry id or tag. Examples: structure 'village', 'ancient_city', "
                    + "'#minecraft:village' (any village), 'mineshaft', 'stronghold'; biome 'cherry_grove', 'desert', "
                    + "'#minecraft:is_forest'; poi 'bell', 'lodestone', 'nether_portal'. Plain words like 'jungle temple' are matched loosely. "
                    + "If unknown, the tool lists close matches to try.");
            props.add("target", target);
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "kind", "target");
            return new ToolSpec("locate",
                    "Find the nearest structure, biome, or point of interest from where you stand, like the /locate command. "
                    + "Returns coordinates, distance, and direction. Structures are searched within about 1600 blocks, biomes "
                    + "within 6400, points of interest within 256. Searches can take a few seconds.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            String kind = input.has("kind") ? input.get("kind").getAsString().toLowerCase(Locale.ROOT) : "";
            String target = input.has("target") ? input.get("target").getAsString().strip() : "";
            if (target.isEmpty()) return "You need to say what to look for.";
            ServerLevel level = ctx.level();
            BlockPos origin = ctx.npc().blockPosition();
            return switch (kind) {
                case "structure" -> structure(ctx, level, origin, target);
                case "biome" -> biome(ctx, level, origin, target);
                case "poi" -> poi(ctx, level, origin, target);
                default -> "kind must be one of structure, biome, poi.";
            };
        }

        private String structure(ToolContext ctx, ServerLevel level, BlockPos origin, String target) {
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Resolved<Structure> r = resolve(registry, Registries.STRUCTURE, target, "structure");
            if (r.error != null) return r.error;
            Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, r.set, origin, STRUCTURE_RADIUS_CHUNKS, false);
            if (found == null) return "No " + r.name + " within about " + (STRUCTURE_RADIUS_CHUNKS * 16) + " blocks.";
            return describeFound(ctx, name(r, found.getSecond()), origin, found.getFirst(), false);
        }

        private String biome(ToolContext ctx, ServerLevel level, BlockPos origin, String target) {
            Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            Resolved<Biome> r = resolve(registry, Registries.BIOME, target, "biome");
            if (r.error != null) return r.error;
            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(r.set::contains, origin, BIOME_RADIUS, 32, 64);
            if (found == null) return "No " + r.name + " within " + BIOME_RADIUS + " blocks.";
            return describeFound(ctx, name(r, found.getSecond()), origin, found.getFirst(), true);
        }

        private String poi(ToolContext ctx, ServerLevel level, BlockPos origin, String target) {
            Registry<PoiType> registry = level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE);
            Resolved<PoiType> r = resolve(registry, Registries.POINT_OF_INTEREST_TYPE, target, "point of interest");
            if (r.error != null) return r.error;
            Optional<Pair<Holder<PoiType>, BlockPos>> found = level.getPoiManager()
                    .findClosestWithType(r.set::contains, origin, POI_RADIUS, PoiManager.Occupancy.ANY);
            if (found.isEmpty()) return "No " + r.name + " within " + POI_RADIUS + " blocks.";
            return describeFound(ctx, name(r, found.get().getFirst()), origin, found.get().getSecond(), true);
        }

        private static String describeFound(ToolContext ctx, String what, BlockPos from, BlockPos at, boolean includeY) {
            int dx = at.getX() - from.getX(), dz = at.getZ() - from.getZ();
            int distance = includeY ? (int) Math.sqrt(from.distSqr(at)) : (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String coords = "(" + at.getX() + ", " + (includeY ? String.valueOf(at.getY()) : "~") + ", " + at.getZ() + ")";
            ctx.npc().rememberLocated(new AiVillagerEntity.LocatedPlace(what, at.getX(), includeY ? at.getY() : null, at.getZ()));
            return "Nearest " + what + " is about " + distance + " blocks " + direction(dx, dz) + ", " + relative(ctx.player(), dx, dz)
                    + ". Coordinates " + coords + ": tell the player those only if they ask for coordinates; otherwise "
                    + "point them the way a person would (which way, roughly how far).";
        }

        private static <T> String name(Resolved<T> r, Holder<T> hit) {
            return r.isTag ? r.name + " (" + pretty(hit.getRegisteredName()) + ")" : r.name;
        }

        private static final class Resolved<T> {
            HolderSet<T> set;
            String name;
            boolean isTag;
            String error;
        }

        /** Turns 'village', 'minecraft:village', '#minecraft:village', or 'jungle temple' into a HolderSet, or an error with suggestions. */
        private static <T> Resolved<T> resolve(Registry<T> registry, ResourceKey<Registry<T>> key, String target, String noun) {
            Resolved<T> out = new Resolved<>();
            String raw = target.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (raw.startsWith("#")) {
                Identifier id = Identifier.tryParse(raw.substring(1));
                Optional<HolderSet.Named<T>> tag = id == null ? Optional.empty() : registry.get(TagKey.create(key, id));
                if (tag.isPresent()) {
                    out.set = tag.get();
                    out.name = "#" + id;
                    out.isTag = true;
                    return out;
                }
                out.error = "Unknown " + noun + " tag '" + target + "'." + suggestTags(registry, raw.substring(1));
                return out;
            }
            IdMatch<T> match = matchId(registry, raw, noun, target);
            if (match.error != null) {
                out.error = match.error;
                return out;
            }
            Optional<Holder.Reference<T>> exact = Optional.of(match.hit);
            out.set = HolderSet.direct(exact.get());
            out.name = pretty(exact.get().getRegisteredName());
            return out;
        }

        static final class IdMatch<T> {
            Holder.Reference<T> hit;
            String error;
        }

        /** Exact id, else a unique loose match on the path, else an error listing candidates. */
        static <T> IdMatch<T> matchId(Registry<T> registry, String raw, String noun, String original) {
            IdMatch<T> out = new IdMatch<>();
            Identifier id = Identifier.tryParse(raw);
            Optional<Holder.Reference<T>> exact = id == null ? Optional.empty() : registry.get(id);
            if (exact.isEmpty()) {
                String needle = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
                List<Identifier> hits = registry.keySet().stream()
                        .filter(k -> k.getPath().contains(needle))
                        .sorted(Comparator.comparing(Identifier::toString)).toList();
                if (hits.size() == 1) {
                    exact = registry.get(hits.get(0));
                } else if (hits.size() > 1) {
                    // prefer an exact path match among several ("bread" vs "bread_..."), else ask
                    Optional<Identifier> exactPath = hits.stream().filter(k -> k.getPath().equals(needle)).findFirst();
                    if (exactPath.isPresent()) {
                        exact = registry.get(exactPath.get());
                    } else {
                        out.error = "Several " + noun + "s match '" + original + "': " + joinIds(hits.subList(0, Math.min(hits.size(), MAX_SUGGESTIONS))) + ". Pick one.";
                        return out;
                    }
                }
            }
            if (exact.isEmpty()) {
                out.error = "Unknown " + noun + " '" + original + "'." + suggestIds(registry, raw) + suggestTags(registry, raw);
                return out;
            }
            out.hit = exact.get();
            return out;
        }

        static <T> String suggestIds(Registry<T> registry, String raw) {
            String needle = raw.length() >= 3 ? raw.substring(0, 3) : raw;
            List<Identifier> near = registry.keySet().stream()
                    .filter(k -> k.getPath().contains(needle))
                    .sorted(Comparator.comparing(Identifier::toString)).limit(MAX_SUGGESTIONS).toList();
            if (near.isEmpty()) {
                near = registry.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).limit(MAX_SUGGESTIONS).toList();
                return " Some valid ids: " + joinIds(near) + " ...";
            }
            return " Similar ids: " + joinIds(near) + ".";
        }

        static <T> String suggestTags(Registry<T> registry, String raw) {
            List<String> tags = registry.getTags()
                    .map(t -> "#" + t.key().location())
                    .filter(t -> t.contains(raw.length() >= 3 ? raw.substring(0, 3) : raw))
                    .sorted().limit(MAX_SUGGESTIONS).toList();
            return tags.isEmpty() ? "" : " Tags: " + String.join(", ", tags) + ".";
        }

        static String joinIds(List<Identifier> ids) {
            StringBuilder sb = new StringBuilder();
            for (Identifier i : ids) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(pretty(i.toString()));
            }
            return sb.toString();
        }

        static String pretty(String id) {
            return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        }
    }

    /** Tiny helpers for building JSON schemas without a dependency. */
    private static final class JsonArrayHelper {
        static void enumOf(JsonObject prop, String... values) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String v : values) arr.add(v);
            prop.add("enum", arr);
        }

        static void required(JsonObject schema, String... names) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String n : names) arr.add(n);
            schema.add("required", arr);
        }
    }

    // ---------- actions ----------



    /** Long-term memory: things the player told you, or events worth keeping, written to the world save. */
    static final class Remember implements NpcTool {
        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject note = new JsonObject();
            note.addProperty("type", "string");
            note.addProperty("description", "One concise fact to keep, in plain words, under 240 characters. "
                    + "Examples: 'Prefers to be called Ben.' 'Afraid of caves.' 'Gave them iron armor and a sword on day 3.' "
                    + "'Finished the first step of the mission.'");
            props.add("note", note);
            JsonObject about = new JsonObject();
            about.addProperty("type", "string");
            JsonArrayHelper.enumOf(about, "player", "world");
            about.addProperty("description", "'player' for something about the person you are talking to (default); "
                    + "'world' for a general fact or event not tied to one person.");
            props.add("about", about);
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "note");
            return new ToolSpec("remember",
                    "Write a lasting note to your memory. Use it when the player tells you something about themselves "
                    + "worth keeping (their name, preferences, history, promises, plans) or when something happened "
                    + "between you that you would recall later. Notes survive long after the conversation is forgotten.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            String note = input.get("note").getAsString().strip();
            if (note.isEmpty()) return "Nothing to remember.";
            String about = input.has("about") ? input.get("about").getAsString() : "player";
            VillagerMemory.Note saved = "world".equals(about)
                    ? ctx.npc().memory().rememberGeneral(note, ctx.npc().currentDay())
                    : ctx.npc().memory().rememberAboutPlayer(ctx.player().getUUID(), ctx.player().getName().getString(),
                            note, ctx.npc().currentDay());
            return "Remembered [" + saved.id + "]: " + saved.text;
        }
    }

    static final class Forget implements NpcTool {
        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject id = new JsonObject();
            id.addProperty("type", "integer");
            id.addProperty("description", "The id in brackets shown next to the memory.");
            props.add("id", id);
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "id");
            return new ToolSpec("forget", "Erase one memory by id, when it turns out to be wrong or the player asks you to.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            int id = input.get("id").getAsInt();
            return ctx.npc().memory().forget(id) ? "Forgotten [" + id + "]." : "No memory with id " + id + ".";
        }
    }

    /** Travel with the player like a tamed animal: walk behind, teleport to catch up. */
    static final class FollowPlayer implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("follow_player",
                    "Start travelling with the player: you walk behind them and step through to their side if they get "
                    + "more than a dozen blocks ahead, across dimensions too, until told to stop. Use it when the player "
                    + "asks you to come with them, even if your character agrees reluctantly.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            ctx.npc().startFollowing(ctx.player());
            return "You are now travelling with " + ctx.player().getName().getString() + ".";
        }
    }

    static final class StopFollowing implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("stop_following", "Stop travelling with the player and stay where you are. Call it "
                    + "whenever the player tells you to stay, wait here, stop following, or go no further; saying you "
                    + "will stay without calling it leaves you following them.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            if (!ctx.npc().isFollowing()) return "You were not following anyone.";
            ctx.npc().stopFollowing();
            return "You stop here.";
        }
    }


    /** Short-range sense for a specific block: nearest match within a few blocks, as a position. */
    static final class FindBlock implements NpcTool {
        private static final int MAX_RADIUS = 16;

        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject block = new JsonObject();
            block.addProperty("type", "string");
            block.addProperty("description", "What to look for, loosely: 'diamond', 'diamond ore', 'iron', 'lava', 'water', 'chest', 'spawner', 'obsidian'.");
            props.add("block", block);
            JsonObject radius = new JsonObject();
            radius.addProperty("type", "integer");
            radius.addProperty("description", "Search radius in blocks, up to 16. Default 12.");
            props.add("radius", radius);
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "block");
            return new ToolSpec("find_block",
                    "Sense the nearest blocks of a kind within a short distance of you (through stone). Returns positions, "
                    + "distance, direction and depth relative to the player, so you can point them the right way or, if they "
                    + "ask, give coordinates. Use lead_player_to to walk them there.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            String needle = input.get("block").getAsString().strip().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (needle.endsWith("_ore")) needle = needle.substring(0, needle.length() - 4);
            if (needle.isEmpty()) return "Look for what?";
            int radius = input.has("radius") ? Math.clamp(input.get("radius").getAsInt(), 2, MAX_RADIUS) : 12;
            ServerLevel level = ctx.level();
            BlockPos me = ctx.npc().blockPosition();
            BlockPos you = ctx.player().blockPosition();
            Map<Block, Boolean> matches = new java.util.HashMap<>();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(me.offset(-radius, -radius, -radius), me.offset(radius, radius, radius))) {
                Block b = level.getBlockState(p).getBlock();
                final String n = needle;
                boolean hit = matches.computeIfAbsent(b, k -> {
                    Identifier id = BuiltInRegistries.BLOCK.getKey(k);
                    return id != null && id.getPath().contains(n);
                });
                if (hit) found.add(p.immutable());
            }
            if (found.isEmpty()) return "No " + needle.replace('_', ' ') + " within " + radius + " blocks of you.";
            found.sort(Comparator.comparingDouble(you::distSqr));
            StringBuilder sb = new StringBuilder(found.size() + " block(s) of " + needle.replace('_', ' ') + " within " + radius + " blocks. ");
            int shown = 0;
            for (BlockPos p : found) {
                if (shown++ == 3) break;
                String name = Locate.pretty(String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())));
                int dx = p.getX() - you.getX(), dy = p.getY() - you.getY(), dz = p.getZ() - you.getZ();
                int flat = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
                String vertical = dy == 0 ? "at your level" : Math.abs(dy) + (dy > 0 ? " blocks above you" : " blocks below you");
                sb.append(shown == 1 ? "Nearest: " : "Also: ").append(name).append(", ").append(flat).append(" blocks ")
                  .append(flat == 0 ? "" : direction(dx, dz) + ", " + relative(ctx.player(), dx, dz) + ", ").append("and ").append(vertical)
                  .append(" (coordinates ").append(p.getX()).append(", ").append(p.getY()).append(", ").append(p.getZ()).append("). ");
            }
            BlockPos nearest = found.get(0);
            ctx.npc().rememberLocated(new AiVillagerEntity.LocatedPlace(needle.replace('_', ' '), nearest.getX(), nearest.getY(), nearest.getZ()));
            sb.append("Point them the way a person would: which way (left, right, ahead, behind, a compass point), roughly how far, "
                    + "and up or down. Give the coordinates only if they ask for coordinates. lead_player_to walks them there.");
            return sb.toString();
        }
    }

    /** Walk ahead to a spot so the player can follow. */
    static final class LeadPlayerTo implements NpcTool {
        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            for (String axis : new String[] {"x", "y", "z"}) {
                JsonObject n = new JsonObject();
                n.addProperty("type", "integer");
                props.add(axis, n);
            }
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "x", "y", "z");
            return new ToolSpec("lead_player_to",
                    "Walk ahead to these coordinates so the player can follow you; you wait if they fall behind and say when "
                    + "you arrive. Only works where there is a walkable way; ore sealed in rock cannot be walked to, so lead "
                    + "to the nearest open spot or tell them which way to dig.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            BlockPos target = new BlockPos(input.get("x").getAsInt(), input.get("y").getAsInt(), input.get("z").getAsInt());
            ctx.npc().startLeading(target);
            return "You set off toward (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + "), keeping "
                    + ctx.player().getName().getString() + " in sight.";
        }
    }

    static final class StopLeading implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("stop_leading", "Stop leading the player and stay where you are.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            if (!ctx.npc().isLeading()) return "You were not leading anyone.";
            ctx.npc().stopLeading();
            return "You stop here.";
        }
    }

    /** Teleport the player: "walking the short road". */
    static final class SendPlayerTo implements NpcTool {
        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            for (String axis : new String[] {"x", "y", "z"}) {
                JsonObject n = new JsonObject();
                n.addProperty("type", "integer");
                n.addProperty("description", axis.equals("y")
                        ? "Height. Omit it to land on the surface at that spot (use this for structures found with ~)."
                        : "Block coordinate " + axis + ".");
                props.add(axis, n);
            }
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "x", "z");
            return new ToolSpec("send_player_to",
                    "Teleport the player you are talking with to coordinates. Use locate first if you need to find the place. "
                    + "Only when the player asked outright to be sent; never offer it.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            int x = input.get("x").getAsInt();
            int z = input.get("z").getAsInt();
            ServerLevel level = ctx.level();
            ServerPlayer player = ctx.player();
            int y;
            if (input.has("y") && !input.get("y").isJsonNull()) {
                y = input.get("y").getAsInt();
            } else {
                level.getChunk(x >> 4, z >> 4); // force-load so the heightmap is real
                y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
            boolean ok = player.teleportTo(level, x + 0.5, y, z + 0.5, Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
            if (!ok) return "The road would not open; they are still here.";
            ctx.npc().rememberLocated(new AiVillagerEntity.LocatedPlace("where you sent " + player.getName().getString(), x, y, z));
            boolean cameAlong = ctx.npc().isFollowing() && player.equals(ctx.npc().followedPlayer());
            if (cameAlong) ctx.npc().teleportBeside(player);
            return player.getName().getString() + " now stands at (" + x + ", " + y + ", " + z + ")."
                    + (cameAlong ? " You stepped through beside them." : "");
        }
    }

    /** Put items in the player's hands, like /give. */
    static final class GiveItem implements NpcTool {
        private static final int MAX_PER_CALL = 64;

        @Override
        public ToolSpec spec() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonObject item = new JsonObject();
            item.addProperty("type", "string");
            item.addProperty("description", "Item id or plain name: 'bread', 'iron_sword', 'minecraft:diamond_pickaxe', 'ender pearl'. "
                    + "If unknown, the tool lists close matches.");
            props.add("item", item);
            JsonObject count = new JsonObject();
            count.addProperty("type", "integer");
            count.addProperty("description", "How many, 1 to 64. Default 1.");
            props.add("count", count);
            schema.add("properties", props);
            JsonArrayHelper.required(schema, "item");
            return new ToolSpec("give_item",
                    "Give the player you are talking with an item. Only when they asked for it outright; never offer, never suggest it.", schema);
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            String target = input.get("item").getAsString().strip();
            int count = input.has("count") ? Math.clamp(input.get("count").getAsInt(), 1, MAX_PER_CALL) : 1;
            String raw = target.toLowerCase(Locale.ROOT).replace(' ', '_');
            Locate.IdMatch<Item> match = Locate.matchId(BuiltInRegistries.ITEM, raw, "item", target);
            if (match.error != null) return match.error;
            Item itemType = match.hit.value();
            ServerPlayer player = ctx.player();
            ItemStack prototype = new ItemStack(itemType);
            int remaining = count;
            int max = Math.max(1, prototype.getMaxStackSize());
            while (remaining > 0) {
                int n = Math.min(max, remaining);
                remaining -= n;
                ItemStack stack = new ItemStack(itemType, n);
                if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                    player.drop(stack, false); // pockets full: it lands at their feet
                }
            }
            String name = prototype.getHoverName().getString();
            return "You hand " + player.getName().getString() + " " + count + " " + name + ".";
        }
    }


    static final class WalkToPlayer implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("walk_to_player", "Walk over to the player you are talking with.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            boolean ok = ctx.npc().getNavigation().moveTo(ctx.player(), 0.6D);
            return ok ? "You start walking toward " + ctx.player().getName().getString() + "."
                      : "You cannot find a path to them from here.";
        }
    }

    static final class FacePlayer implements NpcTool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.noArgs("face_player", "Turn to look directly at the player.");
        }

        @Override
        public String run(ToolContext ctx, JsonObject input) {
            ctx.npc().getLookControl().setLookAt(ctx.player());
            return "You turn to face " + ctx.player().getName().getString() + ".";
        }
    }

    // ---------- helpers ----------

    private static String pos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static String stack(ItemStack s) {
        return s.isEmpty() ? "nothing" : (s.getCount() > 1 ? s.getCount() + " " : "") + s.getHoverName().getString();
    }

    private static String direction(Entity from, Entity to) {
        return direction(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    /** Where something lies from the player's own point of view: ahead, behind, left, right, or between. */
    static String relative(Entity player, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) return "right where you stand";
        double yaw = Math.toRadians(player.getYRot());
        // Minecraft facing: yaw 0 = south (+z), 90 = west (-x).
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double forward = (dx * fx + dz * fz) / len;
        double right = (dx * fz - dz * fx) / len; // positive = on the player's right
        String fb = forward > 0.38 ? "ahead of you" : forward < -0.38 ? "behind you" : "";
        String lr = right > 0.38 ? "to your right" : right < -0.38 ? "to your left" : "";
        if (!fb.isEmpty() && !lr.isEmpty()) return fb + " and " + lr;
        if (!fb.isEmpty()) return forward > 0.92 ? "straight " + fb : fb;
        return lr.isEmpty() ? "beside you" : lr;
    }

    private static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        String[] dirs = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        int idx = (int) Math.round(((angle + 360) % 360) / 45.0) % 8;
        return "to the " + dirs[idx];
    }
}
