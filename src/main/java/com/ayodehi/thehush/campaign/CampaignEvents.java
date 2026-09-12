package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.PilgrimEntity;
import com.ayodehi.thehush.network.HushSilencePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** World-side set pieces keyed to stages, days, and the player's hands: clues, sculk, the frame, the crossing, the ending. */
public final class CampaignEvents {
    private static final HushEncounter HUSH = new HushEncounter();

    private CampaignEvents() {}

    public static HushEncounter hush() {
        return HUSH;
    }

    // ---- stage entry ----

    public static void onStageEntered(MinecraftServer server, CampaignManager cm, CampaignStage stage) {
        CampaignState s = cm.state(server);
        ServerPlayer chosen = cm.chosen(server);
        ServerLevel overworld = server.overworld();
        switch (stage.id) {
            case "stronghold" -> {
                if (chosen != null) {
                    s.strongholdEntry = new int[] {chosen.getBlockX(), chosen.getBlockY(), chosen.getBlockZ()};
                    ServerLevel level = (ServerLevel) chosen.level();
                    StructureStart start = Clues.strongholdNear(level, chosen.blockPosition());
                    BoundingBox library = start == null ? null : Clues.libraryBox(start);
                    if (library != null) {
                        BlockPos lectern = Clues.placeRegisterLectern(level, library, s);
                        BlockPos c = library.getCenter();
                        s.library = new int[] {c.getX(), library.minY() + 1, c.getZ()};
                        TheHushMod.LOGGER.info("The register lies in the library at {}", lectern);
                    }
                    s.save();
                }
            }
            case "dragon" -> {
                s.dragonDay = overworld.getOverworldClockTime() / 24000L;
                if (s.arrival != null) Clues.placeSensorPatch(overworld, new BlockPos(s.arrival[0], s.arrival[1], s.arrival[2]).offset(14, 0, 9), 3, s);
                if (s.strongholdEntry != null) Clues.placeSensorPatch(overworld, new BlockPos(s.strongholdEntry[0], s.strongholdEntry[1], s.strongholdEntry[2]), 2, s);
                if (s.library != null) {
                    BlockPos lib = new BlockPos(s.library[0], s.library[1], s.library[2]);
                    StructureStart start = Clues.strongholdNear(overworld, lib);
                    BoundingBox box = start == null ? null : Clues.libraryBox(start);
                    BlockPos corner = box == null ? lib : Clues.libraryCorner(overworld, box);
                    if (corner != null) PilgrimEntity.place(overworld, corner);
                }
                s.save();
            }
            case "ancient_city" -> {
                if (chosen != null) TheHushMod.LOGGER.info("Frame search: {}", findFrameAndStockIt(server, cm, chosen, s));
            }
            case "named" -> {
                if (s.flags.contains("heard") && !s.flags.contains("shrieker_fired")) s.flags.add("shrieker_due");
                s.save();
            }
            case "quiet" -> {
                AiVillagerEntity t = cm.traveller(server, chosen);
                if (chosen != null && t != null && !Quiet.isQuiet(t.level())) t.teleportBeside(chosen);
            }
            case "ended" -> ending(server, cm, s);
            default -> { }
        }
    }

    /** Find the frame, build the pedestal at its foot, stock the bell, seed the Pilgrims. Returns a status line. */
    public static String findFrameAndStockIt(MinecraftServer server, CampaignManager cm, ServerPlayer chosen, CampaignState s) {
        ServerLevel level = (ServerLevel) chosen.level();
        Structure city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.ANCIENT_CITY);
        if (city == null) return "No ancient city structure in the registry.";
        StructureStart start = level.structureManager().getStructureAt(chosen.blockPosition(), city);
        if (!start.isValid()) return "The chosen is not inside an ancient city.";
        BoundingBox frame = Clues.findFrame(level, start.getBoundingBox());
        if (frame == null) {
            TheHushMod.LOGGER.warn("No reinforced deepslate frame found in the ancient city at {}", start.getBoundingBox().getCenter());
            return "No reinforced deepslate found near the city's middle; are its centre chunks loaded?";
        }
        BlockPos c = frame.getCenter();
        s.frame = new int[] {c.getX(), frame.minY(), c.getZ(), frame.minX(), frame.minY(), frame.minZ(), frame.maxX(), frame.maxY(), frame.maxZ()};
        if (s.pedestal != null) {
            Clues.restoreRecordedNear(level, s, new BlockPos(s.pedestal[0], s.pedestal[1], s.pedestal[2]), 4);
            s.pedestal = null;
        }
        BlockPos bellSpot = Clues.buildPedestal(level, frame, s);
        if (bellSpot != null) s.pedestal = new int[] {bellSpot.getX(), bellSpot.getY(), bellSpot.getZ()};
        TheHushMod.LOGGER.info("The frame spans {}; the pedestal's empty mount is at {}", frame, bellSpot);
        // Pilgrims between the lanterns (only the first time).
        int placed = 0;
        boolean seeded = s.flags.contains("pilgrims_seeded");
        if (!seeded) s.flags.add("pilgrims_seeded");
        var random = level.getRandom();
        BoundingBox box = start.getBoundingBox();
        for (int i = 0; i < 200 && placed < 4 && !seeded; i++) {
            BlockPos p = new BlockPos(box.minX() + random.nextInt(box.getXSpan()), box.minY() + random.nextInt(box.getYSpan()), box.minZ() + random.nextInt(box.getZSpan()));
            if (p.distSqr(chosen.blockPosition()) < 24 * 24 || !Clues.darkFloor(level, p)) continue;
            if (PilgrimEntity.place(level, p) != null) placed++;
        }
        s.save();
        return "Frame " + frame + "; pedestal at " + bellSpot + "; " + placed + " Pilgrim(s) placed.";
    }

    // ---- once a second ----

    public static void tick(MinecraftServer server, CampaignManager cm, CampaignState s) {
        ServerLevel overworld = server.overworld();
        long day = overworld.getOverworldClockTime() / 24000L;
        long t = overworld.getOverworldClockTime() % 24000L;
        boolean night = t >= 13000 && t < 23000;
        ServerPlayer chosen = cm.chosen(server);
        long arrivalDay = s.history.isEmpty() ? day : s.history.get(0).day;
        boolean ended = "ended".equals(s.stage);
        if (!s.arrivalVillage && !ended && chosen != null && chosen.level() == overworld && server.getTickCount() % 100 == 0) {
            adoptVillage(server, cm, s, chosen, overworld);
        }
        // A seat by the well for a Traveller who has none (he was away, or dead, when the village was found).
        if (s.arrivalVillage && s.arrival != null && !ended && server.getTickCount() % 100 == 0) {
            AiVillagerEntity him = cm.traveller(server, chosen);
            BlockPos well = new BlockPos(s.arrival[0], s.arrival[1], s.arrival[2]);
            if (him != null && him.seat() == null && him.level() == overworld && him.blockPosition().distSqr(well) < 64 * 64 && overworld.isLoaded(well)) {
                BlockPos seat = Arrival.standingSpotBeside(overworld, well);
                if (seat != null) him.setSeat(seat);
            }
        }

        // The dread beats keyed to days stop once the road has ended; the village is at peace.
        if (s.arrival != null && !ended) {
            BlockPos arrival = new BlockPos(s.arrival[0], s.arrival[1], s.arrival[2]);
            if (day >= arrivalDay + 3 && !s.flags.contains("well_sculk") && overworld.isLoaded(arrival)) {
                Clues.growSculkOnWell(overworld, arrival, s);
                cm.setFlag(server, "well_sculk");
            }
            if (day >= arrivalDay + 4 && !s.flags.contains("villager_missing") && overworld.isLoaded(arrival)) {
                s.missingVillager = Clues.removeAVillager(overworld, arrival);
                cm.setFlag(server, "villager_missing");
                if (s.missingVillager != null) TheHushMod.LOGGER.info("Gone: {}", s.missingVillager);
            }
            boolean due = s.flags.contains("shrieker_due") || (s.dragonDay >= 0 && day >= s.dragonDay + 2);
            if (due && night && !s.flags.contains("shrieker_fired") && overworld.isLoaded(arrival) && chosen != null
                    && chosen.level() == overworld && chosen.blockPosition().distSqr(arrival) < 64 * 64) {
                BlockPos at = Clues.placeShrieker(overworld, arrival.offset(-12, 0, 8), s);
                if (at != null) Clues.fireShrieker(overworld, at);
                cm.setFlag(server, "shrieker_fired");
                cm.queueBeat(server, "A shriek came out of the dark at the village edge and for three seconds there was no light anywhere. "
                        + "You went to your knees. You are getting up now and pretending nothing happened.", true);
            }
        }
        if (chosen != null) {
            // The frame can only be found while the chosen stands in the city; if the stage was entered
            // elsewhere (an early visit remembered as a flag), keep trying until they are.
            if (s.frame == null && cm.definition(server) != null && cm.definition(server).indexOf(s.stage) >= cm.definition(server).indexOf("ancient_city")
                    && server.getTickCount() % 100 == 0 && chosen.level() == overworld) {
                String r = findFrameAndStockIt(server, cm, chosen, s);
                if (s.frame != null) TheHushMod.LOGGER.info("Frame search (late): {}", r);
            }
            LanternEvents.tick(server, cm, s, chosen);
            // Wool in hand changes what he says in the dark.
            if (!s.flags.contains("has_soul") && carriesSoul(chosen)) {
                cm.setFlag(server, "has_soul");
            }
            if (!s.flags.contains("has_soul") && chosen.level().dimension() == net.minecraft.world.level.Level.NETHER
                    && server.overworld().getGameTime() - lastSoulNudgeTick > 20L * 60L * 8L) {
                lastSoulNudgeTick = server.overworld().getGameTime();
                cm.queueBeat(server, "The player is in the Nether and carries no soul sand or soul soil yet. One line, the way you would "
                        + "remind someone of an errand: the valleys of soul sand, a stack of it, for the blue torches. Nothing about why.", false);
            }
            if (!s.flags.contains("has_wool") && cm.definition(server) != null && cm.definition(server).indexOf(s.stage) >= cm.definition(server).indexOf("deep_dark")
                    && carriesWool(chosen)) {
                cm.setFlag(server, "has_wool");
            }
            // Standing in the lit frame: cross.
            if (s.frame != null && s.flags.contains("frame_lit") && !s.flags.contains("crossed") && chosen.level() == overworld
                    && (frameBox(s).inflatedBy(1).isInside(chosen.blockPosition()) || nearPedestal(s, chosen.blockPosition(), 3))) {
                cm.setFlag(server, "crossed");
                List<ServerPlayer> companions = new ArrayList<>();
                for (ServerPlayer p : overworld.players()) if (p != chosen && p.distanceTo(chosen) < 12) companions.add(p);
                overworld.playSound(null, chosen.getX(), chosen.getY(), chosen.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 2.0F, 0.5F);
                Quiet.enter(server, chosen, cm.traveller(server, chosen), companions, s);
            }
        }
        if (s.frame != null && s.flags.contains("frame_lit") && server.getTickCount() % 40 == 0 && overworld.isLoaded(frameBox(s).getCenter())) {
            BlockPos c = s.pedestal != null ? new BlockPos(s.pedestal[0], s.pedestal[1], s.pedestal[2]) : frameBox(s).getCenter().atY(frameBox(s).minY() + 1);
            overworld.sendParticles(ParticleTypes.SCULK_SOUL, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 8, 1.5, 1.0, 1.5, 0.02);
        }
        HUSH.tick(server, cm, s);
    }

    private static long lastSoulNudgeTick = Long.MIN_VALUE / 2;

    private static boolean carriesSoul(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(net.minecraft.world.item.Items.SOUL_SAND) || st.is(net.minecraft.world.item.Items.SOUL_SOIL)
                    || st.is(net.minecraft.world.item.Items.SOUL_TORCH)) return true;
        }
        return false;
    }

    private static boolean carriesWool(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            var id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && id.getPath().endsWith("_wool")) return true;
        }
        return false;
    }

    static boolean nearPedestal(CampaignState s, BlockPos pos, int r) {
        return s.pedestal != null && pos.distSqr(new BlockPos(s.pedestal[0], s.pedestal[1], s.pedestal[2])) <= (long) r * r;
    }

    static BoundingBox frameBox(CampaignState s) {
        int[] f = s.frame;
        if (f == null) return new BoundingBox(BlockPos.ZERO);
        if (f.length >= 9) return new BoundingBox(f[3], f[4], f[5], f[6], f[7], f[8]);
        return new BoundingBox(new BlockPos(f[0], f[1], f[2])).inflatedBy(3);
    }

    // ---- the player's hands ----

    /** A bell rung: inside the frame it lights it; in the throat it pulls the wardens. */
    public static void onBellRung(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server)) return;
        CampaignState s = cm.state(server);
        if (Quiet.isQuiet(level)) {
            HUSH.onBellRung(server, level, pos, player);
            return;
        }
        if (level == server.overworld() && Clues.isVillageBell(s, pos)) Clues.villageBellTell(server, player, s, cm);
        if (s.frame == null || s.flags.contains("frame_lit") || level != server.overworld()) return;
        // Anywhere in the frame's footprint, from the pedestal's level up to the top of the arch, counts,
        // and so does the pedestal itself; the player must be standing there too.
        BoundingBox f = frameBox(s);
        int floorY = s.pedestal != null ? Math.min(s.pedestal[1] - 2, f.minY()) : f.minY() - 20;
        BoundingBox frame = new BoundingBox(f.minX() - 1, floorY, f.minZ() - 1, f.maxX() + 1, f.maxY() + 1, f.maxZ() + 1);
        boolean onPedestal = nearPedestal(s, pos, 2) && nearPedestal(s, player.blockPosition(), 5);
        boolean inFrame = frame.isInside(pos) && frame.inflatedBy(3).isInside(player.blockPosition());
        if (!onPedestal && !inFrame) return;
        lightFrame(server, cm, level, s);
    }

    public static void onNoteBlock(ServerLevel level, BlockPos pos) {
        HUSH.onNoteBlock(level, pos);
    }

    private static void lightFrame(MinecraftServer server, CampaignManager cm, ServerLevel level, CampaignState s) {
        BoundingBox f = frameBox(s);
        cm.setFlag(server, "frame_lit");
        BlockPos c = f.getCenter();
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.BLOCKS, 4.0F, 0.5F);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 3.0F, 0.6F);
        // The frame is a plane one block thick, so shrinking it would invert an axis; the pair form of
        // betweenClosed sorts its corners, and the interior is simply the plane minus its rim.
        BlockPos a = new BlockPos(f.minX(), f.minY() + 1, f.minZ()), b = new BlockPos(f.maxX(), f.maxY() - 1, f.maxZ());
        if (f.getXSpan() > 2) { a = a.offset(1, 0, 0); b = b.offset(-1, 0, 0); }
        if (f.getZSpan() > 2) { a = a.offset(0, 0, 1); b = b.offset(0, 0, -1); }
        if (f.getYSpan() > 2) {
            for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
                if (level.getBlockState(pos).isAir()) {
                    Clues.setRecorded(level, pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9), s);
                }
            }
        }
        level.sendParticles(ParticleTypes.SONIC_BOOM, c.getX() + 0.5, f.minY() + 1.5, c.getZ() + 0.5, 1, 0, 0, 0, 0);
        if (s.pedestal != null) {
            BlockPos ped = new BlockPos(s.pedestal[0], s.pedestal[1], s.pedestal[2]);
            for (BlockPos pos : BlockPos.betweenClosed(ped.offset(-1, 0, -1), ped.offset(1, 2, 1))) {
                if (level.getBlockState(pos).isAir()) Clues.setRecorded(level, pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9), s);
            }
        }
        cm.queueBeat(server, "The bell has been rung on the pedestal at the frame's foot. Everything down here heard it. The frame is lit: the air "
                + "around the pedestal glows and pulls. Tell them to stand at the pedestal and it will take them, and you, across. This is the crossing you made once before.", true);
        s.save();
    }

    // ---- dimension changes ----

    public static void onPlayerChangedDimension(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        if (to == Quiet.DIMENSION) PacketDistributor.sendToPlayer(player, new HushSilencePayload(true));
        else if (from == Quiet.DIMENSION) PacketDistributor.sendToPlayer(player, new HushSilencePayload(false));
        // Followers left behind stop ticking once their chunk unloads, so move them now rather than
        // waiting for an AI step that will never come. Nobody follows into the End: he waits in the village.
        MinecraftServer server = player.level().getServer();
        ServerLevel fromLevel = server.getLevel(from);
        if (fromLevel == null) return;
        List<AiVillagerEntity> followers = new ArrayList<>();
        fromLevel.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(AiVillagerEntity.class),
                v -> v.isAlive() && v.isFollowing() && player.getUUID().equals(v.followTargetId()), followers);
        for (AiVillagerEntity v : followers) {
            if (to == Level.END) {
                v.stopFollowing();
                CampaignManager cm = CampaignManager.get();
                if (cm.takesPart(v)) cm.queueBeat(server, player.getName().getString()
                        + " has gone through the portal into the End without you. You could not follow; you stopped at the edge. You are alone now, waiting.", true);
                continue;
            }
            v.stopLeading();
            v.teleportBeside(player);
        }
    }

    /** After a respawn the follower is usually far away in a chunk about to unload: bring him now. */
    public static void onPlayerRespawn(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        for (ServerLevel level : server.getAllLevels()) {
            List<AiVillagerEntity> followers = new ArrayList<>();
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(AiVillagerEntity.class),
                    v -> v.isAlive() && v.isFollowing() && player.getUUID().equals(v.followTargetId()), followers);
            for (AiVillagerEntity v : followers) {
                v.stopLeading();
                v.teleportBeside(player);
            }
        }
    }

    public static void onPlayerLoggedIn(ServerPlayer player) {
        if (Quiet.isQuiet(player.level())) PacketDistributor.sendToPlayer(player, new HushSilencePayload(true));
        Arrival.onPlayerJoined(player.level().getServer(), player);
    }

    // ---- the ending and the loop ----

    /** Dawn. The chosen wakes at home; he is on the well and has never met them. */
    static void ending(MinecraftServer server, CampaignManager cm, CampaignState s) {
        ServerLevel overworld = server.overworld();
        ServerPlayer chosen = cm.chosen(server);
        AiVillagerEntity t = cm.traveller(server, chosen);
        HUSH.reset(server);
        if (chosen != null) {
            chosen.removeEffect(MobEffects.DARKNESS);
            LevelData.RespawnData home = chosen.getRespawnConfig() == null ? overworld.getRespawnData() : chosen.getRespawnConfig().respawnData();
            ServerLevel homeLevel = server.getLevel(home.dimension());
            if (homeLevel == null) homeLevel = overworld;
            BlockPos p = home.pos();
            chosen.teleportTo(homeLevel, p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5, Set.<Relative>of(), home.yaw(), 0F, true);
            PacketDistributor.sendToPlayer(chosen, new HushSilencePayload(false));
        }
        // Sunrise.
        overworld.dimensionTypeRegistration().value().defaultClock().ifPresent((Holder<WorldClock> clock) -> {
            long day = overworld.getOverworldClockTime() / 24000L;
            server.clockManager().setTotalTicks(clock, (day + 1) * 24000L);
        });
        Clues.restoreRecorded(overworld, s);
        if (t != null) {
            // Everything that lives on the entity object must happen before the crossing, which replaces it.
            t.stopFollowing();
            t.stopLeading();
            t.removeEffect(MobEffects.DARKNESS);
            t.forgetEverything();
            t.setEyesDark(false);
            if (s.chosenPlayer != null) {
                try {
                    t.memory().archivePlayer(java.util.UUID.fromString(s.chosenPlayer));
                } catch (IllegalArgumentException ignored) {
                    // no chosen id
                }
            }
            t.memory().restoreTaken();
            if (s.arrival != null) {
                t.teleportTo(overworld, s.arrival[0] + 0.5, s.arrival[1], s.arrival[2] + 0.5, Set.<Relative>of(), 0F, 0F, false);
            } else if (chosen != null) {
                t.teleportBeside(chosen);
            }
            AiVillagerEntity live = t.current();
            live.getNavigation().stop();
        }
        s.strangerToPlayer = true;
        s.deaths = 0;
        s.forgotten.clear();
        s.save();
        if (chosen != null) ConversationManager.get().tellQuietly(chosen, "You wake in your own bed. It is morning. Villagers are outside.");
        TheHushMod.LOGGER.info("The road has ended; he is waiting on the well again");
    }

    /** Undo everything scripted so another run starts clean. */
    /**
     * He arrived in open country. The first village the chosen walks into becomes his: the arrival point
     * moves to its well, so the day-count dread (sculk on the well, a villager gone), the shriek at the
     * village edge, and the ending all have somewhere to happen.
     */
    private static void adoptVillage(MinecraftServer server, CampaignManager cm, CampaignState s, ServerPlayer chosen, ServerLevel overworld) {
        BlockPos at = chosen.blockPosition();
        if (!overworld.structureManager().getStructureWithPieceAt(at, h -> h.is(net.minecraft.tags.StructureTags.VILLAGE)).isValid()) return;
        BlockPos well = Clues.findWell(overworld, at, 32);
        if (well == null) return;
        s.arrival = new int[] {well.getX(), well.getY(), well.getZ()};
        s.arrivalVillage = true;
        s.save();
        cm.setFlag(server, "village_found");
        AiVillagerEntity him = cm.traveller(server, chosen);
        if (him != null && him.level() == overworld) {
            BlockPos seat = Arrival.standingSpotBeside(overworld, well);
            if (seat != null) him.setSeat(seat);
        }
        TheHushMod.LOGGER.info("The first village: its well at {} is now where he waits", well.toShortString());
    }

    public static void cleanup(MinecraftServer server, CampaignState state) {
        HUSH.reset(server);
        Clues.restoreRecorded(server.overworld(), state);
        for (ServerLevel level : server.getAllLevels()) {
            for (PilgrimEntity p : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(PilgrimEntity.class), e -> true)) p.discard();
        }
    }
}
