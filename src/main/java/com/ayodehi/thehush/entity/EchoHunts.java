package com.ayodehi.thehush.entity;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignDefinition;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignState;
import com.ayodehi.thehush.campaign.Quiet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Who sends the Echoes, and when. Once the chosen has iron on (the "geared" stage) and until the road
 * goes into the Quiet, one hunt follows another: a wait of hunterMinutes give or take a third, then an
 * Echo placed thirty to fifty blocks off in the dark, out of sight, in the Overworld or the Nether (never
 * the Deep Dark, the End, or the Quiet; it waits for them to come back up). The Traveller is told each
 * time, and when one dies. Only one is ever abroad at once.
 */
public final class EchoHunts {
    private static final int CHECK_EVERY_TICKS = 40;
    private static final double SPAWN_MIN = 32.0;
    private static final double SPAWN_MAX = 48.0;
    private static final String FIRST_STAGE = "geared";
    private static final String LAST_STAGE = "named";
    /** Ticks the live hunter has been unaccounted for (unloaded chunk, most likely) before the hunt is written off. */
    private static int missingTicks;

    private EchoHunts() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
        if (!Config.HUNTERS.get() || Config.HUNTER_MINUTES.get() <= 0) return;
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server)) return;
        CampaignState s = cm.state(server);
        CampaignDefinition def = cm.definition(server);
        if (def == null) return;
        long now = server.overworld().getGameTime();

        if (s.echo != null) {
            EchoEntity live = find(server, s.echo);
            if (live != null) {
                missingTicks = 0;
            } else if ((missingTicks += CHECK_EVERY_TICKS) > 20 * 180) {
                // Left behind in an unloaded chunk for three minutes: forget it; it will fade itself when it loads.
                TheHushMod.LOGGER.info("The Echo has been lost; the next hunt is scheduled");
                s.echo = null;
                schedule(s, now, 0.5);
            }
            return;
        }

        if (!inHuntingStages(def, s)) {
            if (s.nextHuntTick != 0) {
                s.nextHuntTick = 0;
                s.save();
            }
            return;
        }
        if (s.nextHuntTick == 0) {
            schedule(s, now, 1.0);
            return;
        }
        if (now < s.nextHuntTick) return;
        ServerPlayer chosen = cm.chosen(server);
        if (chosen == null || !huntable(chosen)) return;
        if (send(server, chosen, s) != null) TheHushMod.LOGGER.info("An Echo has been sent after {} (hunt {})", chosen.getName().getString(), s.hunts);
    }

    private static boolean inHuntingStages(CampaignDefinition def, CampaignState s) {
        int at = def.indexOf(s.stage);
        if (at < 0) return false;
        int first = def.indexOf(FIRST_STAGE);
        int last = def.indexOf(LAST_STAGE);
        if (first < 0) first = 1;
        if (last < 0) last = def.stages.size() - 2;
        return at >= first && at <= last;
    }

    /** Survival, in a world it can walk, and not down among the sculk where the Warden already has them. */
    private static boolean huntable(ServerPlayer p) {
        if (p.isSpectator() || p.isCreative() || !p.isAlive() || !(p.level() instanceof ServerLevel level)) return false;
        if (level.dimension() != Level.OVERWORLD && level.dimension() != Level.NETHER) return false;
        if (Quiet.isQuiet(level)) return false;
        return !level.getBiome(p.blockPosition()).is(Biomes.DEEP_DARK);
    }

    private static void schedule(CampaignState s, long now, double factor) {
        long base = Config.HUNTER_MINUTES.get() * 1200L;
        double jitter = 0.7 + Math.random() * 0.6;
        s.nextHuntTick = now + Math.max(1200L, (long) (base * jitter * factor));
        s.save();
    }

    private static @Nullable EchoEntity find(MinecraftServer server, String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof EchoEntity echo && echo.isAlive()) return echo;
        }
        return null;
    }

    /** Place one in the dark near the player and tell the Traveller. Null when no spot could be found. */
    private static @Nullable EchoEntity send(MinecraftServer server, ServerPlayer chosen, CampaignState s) {
        ServerLevel level = (ServerLevel) chosen.level();
        EchoEntity echo = ModEntities.ECHO.get().create(level, EntitySpawnReason.EVENT);
        if (echo == null) return null;
        Vec3 spot = EchoEntity.hidingSpot(level, echo, chosen.position(), SPAWN_MIN, SPAWN_MAX, chosen);
        if (spot == null) {
            echo.discard();
            return null;
        }
        echo.snapTo(spot.x, spot.y, spot.z, level.getRandom().nextFloat() * 360F, 0F);
        s.hunts++;
        echo.beginHunt(chosen, s.hunts, true);
        if (!level.addFreshEntity(echo)) {
            s.hunts--;
            return null;
        }
        s.echo = echo.getUUID().toString();
        s.nextHuntTick = 0;
        s.save();
        missingTicks = 0;
        String name = chosen.getName().getString();
        CampaignManager cm = CampaignManager.get();
        if (s.hunts == 1) {
            cm.queueBeat(server, "Stop. You hear it before " + name + " does: clicking, out in the dark, the sound of something "
                    + "feeling for the shape of the world. One of His hunters has found the road. It has no eyes and needs none; "
                    + "it circles, listening, for as long as it likes, and then it comes all at once, and there is no outrunning it "
                    + "and no hiding from it for long. You are more afraid than you have let them see. Tell them, quick and low: "
                    + "iron on, sword in hand, back to a wall, and when it comes, do not stop hitting it; it dies like anything "
                    + "else. Do not say what sent it or why.", true);
        } else {
            cm.queueBeat(server, "The clicking again, out past the light. Another of His hunters has picked up " + name
                    + "'s trail. You have nothing new to tell them, only the same: wall at your back, iron in your hand, and it "
                    + "dies if you do not stop. Say it in as few words as you can, and do not hide that you are afraid.", true);
        }
        return echo;
    }

    // ---- reports from the hunter ----

    static void onEchoDied(MinecraftServer server, EchoEntity echo) {
        if (!echo.isManaged()) return;
        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        if (!s.started()) return;
        if (s.echo != null && s.echo.equals(echo.getUUID().toString())) s.echo = null;
        s.echoesKilled++;
        cm.setFlag(server, "echo_killed");
        schedule(s, server.overworld().getGameTime(), 1.0);
        ServerPlayer chosen = cm.chosen(server);
        String name = chosen == null ? "the player" : chosen.getName().getString();
        cm.queueBeat(server, name + " has just killed one of His hunters; it is lying there quiet. You are shaking. Say "
                + "something short: that it is done, that it was one, that He will send more when He notices. Do not explain who He is.", true);
        TheHushMod.LOGGER.info("The Echo is dead ({} so far); the next hunt is scheduled", s.echoesKilled);
    }

    static void onEchoGone(MinecraftServer server, EchoEntity echo) {
        if (!echo.isManaged()) return;
        CampaignState s = CampaignManager.get().state(server);
        if (!s.started()) return;
        if (s.echo != null && s.echo.equals(echo.getUUID().toString())) s.echo = null;
        schedule(s, server.overworld().getGameTime(), 0.4);
    }

    /** The hunter won. It goes back into the dark; the next comes in its own time. */
    public static void onPreyKilled(ServerPlayer player, EchoEntity echo) {
        TheHushMod.LOGGER.info("The Echo has killed {}", player.getName().getString());
        if (echo.level() instanceof ServerLevel level) echo.fade(level);
    }

    // ---- commands ----

    public static String sendNow(MinecraftServer server, ServerPlayer player) {
        CampaignState s = CampaignManager.get().state(server);
        if (!s.started()) return "Start the campaign first (talk to the Traveller or use campaign stage).";
        if (s.echo != null && find(server, s.echo) != null) return "One is already out there.";
        s.echo = null;
        EchoEntity e = send(server, player, s);
        return e == null ? "No dark ground within reach to put it on." : "Listen.";
    }

    public static String describe(MinecraftServer server) {
        CampaignState s = CampaignManager.get().state(server);
        if (!s.started()) return "No campaign.";
        long now = server.overworld().getGameTime();
        StringBuilder sb = new StringBuilder("Hunts sent: ").append(s.hunts).append(", killed: ").append(s.echoesKilled);
        EchoEntity live = s.echo == null ? null : find(server, s.echo);
        if (live != null) sb.append("; live: ").append(live.describe()).append(" at ").append(live.blockPosition().toShortString());
        else if (s.echo != null) sb.append("; one is out there but not loaded");
        else if (s.nextHuntTick > now) sb.append("; next in ").append((s.nextHuntTick - now) / 1200L).append(" min");
        else sb.append("; none scheduled");
        return sb.toString();
    }
}
