package com.ayodehi.thehush.commands;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignState;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.EchoEntity;
import com.ayodehi.thehush.entity.EchoHunts;
import com.ayodehi.thehush.entity.PilgrimEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A text picture of where the player is, for debugging from outside the game: position and facing, the
 * campaign state, every entity within 24 blocks with what it is doing, doors nearby, and block maps of the
 * surrounding 17x17 columns on four layers (feet minus one to feet plus two). Written to
 * &lt;world&gt;/thehush/snapshot.txt by /hush campaign snapshot.
 */
public final class Snapshot {
    private static final int RADIUS = 8;

    private Snapshot() {}

    /** Renders the picture and writes it to &lt;world&gt;/thehush/snapshot.txt; returns the file path. */
    public static String write(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(TheHushMod.MODID).resolve("snapshot.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(player), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TheHushMod.LOGGER.warn("Could not write {}", file, e);
            return "nowhere (see the log)";
        }
        return file.toAbsolutePath().toString();
    }

    /** The picture as text. Server thread only. */
    public static String render(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ServerLevel level = (ServerLevel) player.level();
        StringBuilder sb = new StringBuilder();
        BlockPos at = player.blockPosition();
        Vec3 look = player.getLookAngle();
        sb.append("Snapshot at game time ").append(level.getGameTime()).append(", day time ").append(level.getOverworldClockTime() % 24000L)
          .append(", ").append(level.isBrightOutside() ? "day" : "night").append(level.isRaining() ? ", raining" : "").append('\n');
        sb.append("Player ").append(player.getName().getString()).append(" in ").append(level.dimension().identifier())
          .append(" at ").append(at.toShortString()).append(String.format(" (%.1f %.1f %.1f)", player.getX(), player.getY(), player.getZ()))
          .append(", facing ").append(compass(look)).append(String.format(" (yaw %.0f, pitch %.0f)", player.getYRot(), player.getXRot()))
          .append(", ").append(player.isCreative() ? "creative" : player.isSpectator() ? "spectator" : "survival")
          .append(player.isCrouching() ? ", sneaking" : "").append(player.isSprinting() ? ", sprinting" : "")
          .append(", sky visible: ").append(level.canSeeSky(at.above())).append('\n');

        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        sb.append("Campaign: ").append(s.started() ? s.stage + ", chosen " + s.chosenName + ", flags " + s.flags : "not started")
          .append("; hunts: ").append(EchoHunts.describe(server)).append('\n');

        sb.append("\nEntities within 24 blocks:\n");
        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(24), en -> en != player && en.distanceTo(player) <= 24F)) {
            sb.append("  ").append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
            if (e.hasCustomName()) sb.append(" \"").append(e.getCustomName().getString()).append('"');
            sb.append(" at ").append(e.blockPosition().toShortString()).append(String.format(", %.1f blocks away", e.distanceTo(player)));
            if (e instanceof AiVillagerEntity v) {
                sb.append("; persona ").append(v.personaId()).append(v.isFollowing() ? ", following " : ", not following")
                  .append(v.isFollowing() && v.followedPlayer() != null ? v.followedPlayer().getName().getString() : "")
                  .append(", sees player: ").append(v.hasLineOfSight(player))
                  .append(", navigation ").append(v.getNavigation().isDone() ? "idle" : v.getNavigation().isStuck() ? "stuck" : "moving");
            } else if (e instanceof EchoEntity echo) {
                sb.append("; ").append(echo.describe());
            } else if (e instanceof PilgrimEntity p) {
                sb.append("; ").append(p.isFrozen() ? "frozen (watched)" : "free");
            } else if (e instanceof LivingEntity le) {
                sb.append(String.format("; health %.0f/%.0f", le.getHealth(), le.getMaxHealth()));
            }
            sb.append('\n');
        }

        sb.append("\nDoors within 8 blocks:\n");
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-RADIUS, -3, -RADIUS), at.offset(RADIUS, 3, RADIUS))) {
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof DoorBlock d && st.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                sb.append("  ").append(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).append(" at ").append(pos.toShortString())
                  .append(d.isOpen(st) ? ", open" : ", closed").append(", facing ").append(st.getValue(DoorBlock.FACING).getName()).append('\n');
            }
        }

        Map<String, Character> legend = new LinkedHashMap<>();
        String symbols = "#abcdefghijklmnopqrstuvwxyzABCDFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int dy = 2; dy >= -1; dy--) {
            int y = at.getY() + dy;
            sb.append("\nLayer y=").append(y).append(dy == 0 ? " (feet)" : dy == 1 ? " (head)" : dy == -1 ? " (floor)" : "")
              .append(", north is up, west is left, ").append(RADIUS).append(" blocks each way; @ = player, E = other entity\n");
            for (int z = at.getZ() - RADIUS; z <= at.getZ() + RADIUS; z++) {
                sb.append("  ");
                for (int x = at.getX() - RADIUS; x <= at.getX() + RADIUS; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    char c;
                    if (dy == 0 && x == at.getX() && z == at.getZ()) c = '@';
                    else if (dy == 0 && !level.getEntities(player, new net.minecraft.world.phys.AABB(pos), en -> en != player).isEmpty()) c = 'E';
                    else {
                        BlockState st = level.getBlockState(pos);
                        if (st.isAir()) c = '.';
                        else {
                            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                            Character known = legend.get(id);
                            if (known == null) {
                                known = legend.size() < symbols.length() ? symbols.charAt(legend.size()) : '?';
                                legend.put(id, known);
                            }
                            c = known;
                        }
                    }
                    sb.append(c);
                }
                sb.append('\n');
            }
        }
        sb.append("\nLegend: . air");
        for (var e : legend.entrySet()) sb.append(", ").append(e.getValue()).append(' ').append(e.getKey());
        sb.append('\n');
        return sb.toString();
    }

    private static String compass(Vec3 look) {
        double ax = Math.abs(look.x), az = Math.abs(look.z);
        String h = ax > az ? (look.x > 0 ? "east" : "west") : (look.z > 0 ? "south" : "north");
        if (look.y > 0.7) return "up";
        if (look.y < -0.7) return "down";
        return h;
    }
}
