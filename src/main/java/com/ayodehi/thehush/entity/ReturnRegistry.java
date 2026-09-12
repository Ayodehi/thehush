package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Villagers whose persona "returns from death" are stashed here when they die and brought back a short
 * while later, wherever the player is, same identity, same memories, walking up as if nothing happened.
 * Persisted as JSON in the world save so a death survives a restart.
 */
public final class ReturnRegistry {
    private static final ReturnRegistry INSTANCE = new ReturnRegistry();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CHECK_EVERY_TICKS = 40;

    static final class Pending {
        String uuid = "";
        String persona = "";
        long deathDay;
        long deathMillis;
        @Nullable String player;        // who he walks up to: follow target, else last partner
        boolean following;
        String history = "[]";
    }

    static final class Data {
        List<Pending> pending = new ArrayList<>();
    }

    private @Nullable Data data;
    private @Nullable Path file;

    private ReturnRegistry() {}

    public static ReturnRegistry get() {
        return INSTANCE;
    }

    // ---- scheduling (called from the entity's death) ----

    public void schedule(AiVillagerEntity npc) {
        MinecraftServer server = npc.level().getServer();
        if (server == null) return;
        Data d = load(server);
        d.pending.removeIf(p -> p.uuid.equals(npc.getUUID().toString()));
        Pending p = new Pending();
        p.uuid = npc.getUUID().toString();
        p.persona = npc.personaId();
        p.deathDay = npc.currentDay();
        p.deathMillis = System.currentTimeMillis();
        UUID target = npc.followTargetId();
        if (target == null) target = npc.conversationPartner();
        p.player = target == null ? null : target.toString();
        p.following = npc.isFollowing();
        p.history = npc.historyJson().toString();
        d.pending.add(p);
        save(server);
        TheHushMod.LOGGER.info("{} fell; will return in about {} seconds", npc.speakerName(), Config.RETURN_DELAY_SECONDS.get());
    }

    // ---- ticking ----

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
        Data d = load(server);
        if (d.pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        long delay = Config.RETURN_DELAY_SECONDS.get() * 1000L;
        List<Pending> done = new ArrayList<>();
        for (Pending p : d.pending) {
            if (now - p.deathMillis < delay) continue;
            ServerPlayer player = pickPlayer(server, p);
            if (player == null || player.isSpectator()) continue;
            if (wardenNear(player, 24) && !com.ayodehi.thehush.campaign.Quiet.isQuiet(player.level())) {
                if (server.getTickCount() % (CHECK_EVERY_TICKS * 30) == 0) {
                    TheHushMod.LOGGER.info("{} is waiting for the Warden to leave {}", p.persona, player.getName().getString());
                }
                continue;
            }
            if (bringBack(p, player)) done.add(p);
        }
        if (!done.isEmpty()) {
            d.pending.removeAll(done);
            save(server);
        }
    }

    /** Debug: bring back everyone pending, now, next to this player, morning or not. Returns how many came. */
    public int forceReturn(MinecraftServer server, ServerPlayer player) {
        Data d = load(server);
        List<Pending> done = new ArrayList<>();
        for (Pending p : d.pending) {
            if (bringBack(p, player)) done.add(p);
        }
        d.pending.removeAll(done);
        if (!done.isEmpty()) save(server);
        return done.size();
    }

    public int pendingCount(MinecraftServer server) {
        return load(server).pending.size();
    }

    private static boolean wardenNear(ServerPlayer player, double r) {
        return !player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.warden.Warden.class,
                player.getBoundingBox().inflate(r), w -> w.isAlive()).isEmpty();
    }

    private static @Nullable ServerPlayer pickPlayer(MinecraftServer server, Pending p) {
        if (p.player != null) {
            try {
                ServerPlayer sp = server.getPlayerList().getPlayer(UUID.fromString(p.player));
                if (sp != null) return sp;
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        List<ServerPlayer> all = server.getPlayerList().getPlayers();
        return all.isEmpty() ? null : all.get(0);
    }

    private boolean bringBack(Pending p, ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        AiVillagerEntity npc = ModEntities.AI_VILLAGER.get().create(level, EntitySpawnReason.EVENT);
        if (npc == null) {
            TheHushMod.LOGGER.warn("Could not create the returning villager {}", p.persona);
            return false;
        }
        npc.setUUID(UUID.fromString(p.uuid));
        npc.setPersona(p.persona);
        try {
            npc.restoreHistory(JsonParser.parseString(p.history).getAsJsonArray());
        } catch (RuntimeException e) {
            npc.restoreHistory(new JsonArray());
        }
        if (p.following) npc.startFollowing(player);

        BlockPos spot = findArrivalSpot(npc, level, player);
        if (spot == null) {
            TheHushMod.LOGGER.warn("No walkable spot near {} for {} to return to; will try again", player.getName().getString(), p.persona);
            return false;
        }
        double dx = player.getX() - (spot.getX() + 0.5), dz = player.getZ() - (spot.getZ() + 0.5);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        npc.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0F);
        if (!level.addFreshEntity(npc)) {
            TheHushMod.LOGGER.warn("The world refused the returning villager {} ({})", p.persona, p.uuid);
            return false;
        }
        npc.getNavigation().moveTo(player, 1.0D);
        npc.setTalkingTo(player);
        com.ayodehi.thehush.campaign.CampaignManager.get().recordReturn(npc);
        ConversationManager.get().remark(npc, player, "You have just walked up to "
                + player.getName().getString() + " the way you always do, from wherever you were; to you nothing is out of the ordinary. "
                + "If they seem to be in the middle of something dangerous, say what matters for that.");
        TheHushMod.LOGGER.info("{} has returned near {}", npc.speakerName(), player.getName().getString());
        return true;
    }

    /** A walkable block 12-20 blocks from the player, ideally in the open so they see him coming. */
    private static @Nullable BlockPos findArrivalSpot(AiVillagerEntity npc, ServerLevel level, ServerPlayer player) {
        var random = player.getRandom();
        // Measure from the ground under the player, so a flying (creative) player still gets a visitor.
        BlockPos base = player.blockPosition();
        boolean surface = level.dimension() == Level.OVERWORLD && level.canSeeSky(base.above());
        if (surface) {
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
            if (base.getY() > ground) base = new BlockPos(base.getX(), ground, base.getZ());
        }
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 12 + random.nextInt(9);
            int x = base.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (surface) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (Math.abs(y - base.getY()) <= 12 && WalkNodeEvaluator.getPathTypeStatic(npc, pos) == PathType.WALKABLE) return pos;
            } else {
                for (int y = base.getY() + 2; y >= base.getY() - 6; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (WalkNodeEvaluator.getPathTypeStatic(npc, pos) == PathType.WALKABLE) return pos;
                }
            }
        }
        return null;
    }

    // ---- persistence ----

    private Data load(MinecraftServer server) {
        Path f = server.getWorldPath(LevelResource.ROOT).resolve(TheHushMod.MODID).resolve("returning.json");
        if (data != null && f.equals(file)) return data;
        file = f;
        data = new Data();
        if (Files.exists(f)) {
            try {
                Data read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
                if (read != null && read.pending != null) data = read;
            } catch (IOException | JsonSyntaxException e) {
                TheHushMod.LOGGER.warn("Could not read {}; starting empty", f, e);
            }
        }
        return data;
    }

    private void save(MinecraftServer server) {
        if (file == null || data == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TheHushMod.LOGGER.error("Could not save {}", file, e);
        }
    }

    public void clear() {
        data = null;
        file = null;
    }
}
