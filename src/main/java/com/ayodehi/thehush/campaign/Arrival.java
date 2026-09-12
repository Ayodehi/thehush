package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.persona.Persona;
import com.ayodehi.thehush.persona.PersonaRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * How the story begins in a fresh world: the first time anyone joins, a thunderstorm rolls in; when it
 * passes, the Traveller is sitting on the well of the nearest village (or, with no village near, a short
 * walk from the player's spawn), and a narrator line says where. Nothing more happens until someone
 * speaks to him.
 */
public final class Arrival {
    private static final int STORM_TICKS = 20 * 60 * 3;
    private static final int VILLAGE_SEARCH_CHUNKS = 48;
    private static final int VILLAGE_MAX_DISTANCE = 450;

    private Arrival() {}

    /** Someone joined: if the road has never begun here and he is nowhere in the world, send the storm. */
    public static void onPlayerJoined(MinecraftServer server, ServerPlayer player) {
        if (!Config.CAMPAIGN_ENABLED.get() || !Config.AUTO_ARRIVAL.get()) return;
        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        if (s.started() || s.arrivalDone || s.stormUntilTick > 0) return;
        Persona persona = PersonaRegistry.get().find(Config.ARRIVAL_PERSONA.get());
        if (persona == null || persona.campaign().isEmpty()) return;
        if (anyOfPersona(server, persona.id()) != null) {
            s.arrivalDone = true; // spawned by hand already
            s.save();
            return;
        }
        beginStorm(server, s);
    }

    /** Debug: replay the opening. Any Traveller already standing in the world is left alone; a new one only comes if none exists. */
    public static String replay(MinecraftServer server) {
        CampaignState s = CampaignManager.get().state(server);
        s.arrivalDone = false;
        s.stormUntilTick = 0;
        beginStorm(server, s);
        return "The storm has begun; he arrives in three minutes" + (anyOfPersona(server, Config.ARRIVAL_PERSONA.get()) != null
                ? ", though one of him is already here, so no second one will come." : ".");
    }

    private static void beginStorm(MinecraftServer server, CampaignState s) {
        ServerLevel overworld = server.overworld();
        server.setWeatherParameters(0, STORM_TICKS, true, true);
        s.stormUntilTick = overworld.getGameTime() + STORM_TICKS;
        s.save();
        // He is not here yet; this comes out of the weather itself, in his colour but without his name.
        MutableComponent voice = Component.literal("<A voice in the storm> ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("...not again. Not again. It is happening again.").withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(voice);
        TheHushMod.LOGGER.info("Arrival: the storm has begun; he comes when it passes");
    }

    /** Once a second from the campaign tick: when the storm passes, he is there. */
    public static void tick(MinecraftServer server, CampaignState s) {
        if (s.stormUntilTick <= 0 || s.arrivalDone) return;
        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        // Thunder takes a while to build after it is requested, so the clock is the only judge here.
        if (now < s.stormUntilTick) {
            dryThunder(overworld);
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        server.setWeatherParameters(20 * 60 * 10, 0, false, false);
        Persona persona = PersonaRegistry.get().find(Config.ARRIVAL_PERSONA.get());
        if (persona == null) return;
        if (anyOfPersona(server, persona.id()) != null) {
            s.arrivalDone = true;
            s.stormUntilTick = 0;
            s.save();
            return;
        }

        BlockPos home = player.getRespawnConfig() != null ? player.getRespawnConfig().respawnData().pos() : overworld.getRespawnData().pos();
        BlockPos spot = null;
        String where = null;
        BlockPos villageAnchor = findVillage(overworld, home);
        if (villageAnchor != null) {
            BlockPos well = Clues.findWell(overworld, villageAnchor, 40);
            BlockPos seat = well != null ? standingSpotBeside(overworld, well) : standingSpotNear(overworld, villageAnchor, 6);
            if (seat != null) {
                spot = seat;
                where = well != null ? "sitting on the well of the village" : "standing in the village";
            }
        }
        if (spot == null) {
            spot = standingSpotNear(overworld, player.blockPosition(), 16);
            where = "standing a little way off, watching you";
        }
        if (spot == null) {
            TheHushMod.LOGGER.warn("Arrival: no ground for him near {} or the village; will try again", home);
            return;
        }
        AiVillagerEntity npc = ModEntities.AI_VILLAGER.get().create(overworld, EntitySpawnReason.EVENT);
        if (npc == null) return;
        npc.setPersona(persona.id());
        if (villageAnchor != null) npc.setSeat(spot);
        npc.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, overworld.getRandom().nextFloat() * 360F, 0F);
        if (!overworld.addFreshEntity(npc)) return;
        s.arrivalDone = true;
        s.arrivalVillage = villageAnchor != null && !where.startsWith("standing a little way off");
        s.stormUntilTick = 0;
        s.save();

        MutableComponent line = Component.literal("The storm has passed. Someone is " + where + ", ")
                .append(bearing(player.blockPosition(), spot))
                .append(Component.literal(". Go and speak to him."));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(line.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        TheHushMod.LOGGER.info("Arrival: {} is {} at {}", persona.name(), where, spot);
    }

    /**
     * Vanilla lightning only strikes where it rains, so a player who spawns in a desert, a savanna, the
     * badlands, or snow gets a darkened sky and nothing else: no rain, no thunder, no voice under it. For
     * anyone standing where the storm cannot fall, heat lightning is thrown for them instead: harmless
     * (visual-only) bolts some way off, every eight seconds or so, so the storm is still heard.
     */
    private static void dryThunder(ServerLevel overworld) {
        if (!overworld.isThundering()) return;
        for (ServerPlayer p : overworld.players()) {
            BlockPos at = p.blockPosition();
            if (overworld.getBiome(at).value().getPrecipitationAt(at, overworld.getSeaLevel()) == Biome.Precipitation.RAIN) continue;
            if (overworld.getRandom().nextInt(8) != 0) continue;
            double angle = overworld.getRandom().nextDouble() * Math.PI * 2;
            double dist = 24 + overworld.getRandom().nextInt(25);
            BlockPos ground = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    at.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist)));
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(overworld, EntitySpawnReason.EVENT);
            if (bolt == null) return;
            bolt.snapTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0F, 0F);
            bolt.setVisualOnly(true);
            overworld.addFreshEntity(bolt);
        }
    }

    // ---- helpers ----

    private static @Nullable AiVillagerEntity anyOfPersona(MinecraftServer server, String personaId) {
        for (ServerLevel level : server.getAllLevels()) {
            var found = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(AiVillagerEntity.class),
                    v -> v.isAlive() && personaId.equals(v.personaId()));
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }

    /** The bell of the nearest village within reach of home, else the village's start position, else null. */
    private static @Nullable BlockPos findVillage(ServerLevel level, BlockPos home) {
        BlockPos start = level.findNearestMapStructure(StructureTags.VILLAGE, home, VILLAGE_SEARCH_CHUNKS, false);
        if (start == null) return null;
        if (Math.sqrt(start.distSqr(home)) > VILLAGE_MAX_DISTANCE) return null;
        level.getChunk(start.getX() >> 4, start.getZ() >> 4);
        BlockPos bell = level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), start, 64, PoiManager.Occupancy.ANY).orElse(null);
        if (bell != null) return bell;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.getX(), start.getZ());
        return new BlockPos(start.getX(), y, start.getZ());
    }

    static @Nullable BlockPos standingSpotBeside(ServerLevel level, BlockPos well) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= 3; dist++) {
                BlockPos p = well.relative(d, dist);
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos c = p.offset(0, dy, 0);
                    if (walkable(level, c)) return c;
                }
            }
        }
        return standingSpotNear(level, well, 6);
    }

    private static @Nullable BlockPos standingSpotNear(ServerLevel level, BlockPos center, int radius) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = center.getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getZ() + random.nextInt(radius * 2 + 1) - radius;
            if (Math.abs(x - center.getX()) < 3 && Math.abs(z - center.getZ()) < 3 && radius > 4) continue;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos c = new BlockPos(x, y, z);
            if (walkable(level, c)) return c;
        }
        return null;
    }

    private static boolean walkable(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isSolid();
    }

    private static Component bearing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String ns = dz < -Math.abs(dx) / 2 ? "north" : dz > Math.abs(dx) / 2 ? "south" : "";
        String ew = dx > Math.abs(dz) / 2 ? "east" : dx < -Math.abs(dz) / 2 ? "west" : "";
        String dir = (ns + ew).isEmpty() ? "right here" : "about " + dist + " blocks to the " + ns + ew;
        return Component.literal(dir);
    }

    private static void narrate(MinecraftServer server, String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
    }
}
