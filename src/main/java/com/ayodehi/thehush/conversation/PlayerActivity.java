package com.ayodehi.thehush.conversation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player has been up to lately, fed by game events on the server thread and drained by
 * villagers when they decide to comment. Only "notable" things are kept so the villager doesn't
 * narrate every cobblestone.
 */
public final class PlayerActivity {
    private static final long WINDOW_TICKS = 20L * 150L; // forget anything older than 2.5 minutes
    private static final int MAX_ENTRIES = 40;

    private record Entry(long tick, String what) {}

    private static final Map<UUID, List<Entry>> LOG = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Achievement>> ACHIEVEMENTS = new ConcurrentHashMap<>();
    /** A congratulation older than this is stale (the player died, or wandered off); it is dropped, not spoken late. */
    private static final long ACHIEVEMENT_TTL_MILLIS = 60_000L;

    private record Achievement(long atMillis, String text) {}

    private PlayerActivity() {}

    public static void record(UUID player, long tick, String what) {
        List<Entry> list = LOG.computeIfAbsent(player, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new Entry(tick, what));
            while (list.size() > MAX_ENTRIES) list.remove(0);
        }
    }

    /** "mined coal ore x4, placed a furnace" or null when nothing notable happened recently. Clears what it reports. */
    public static @Nullable String drain(UUID player, long now) {
        List<Entry> list = LOG.get(player);
        if (list == null) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        synchronized (list) {
            list.removeIf(e -> now - e.tick > WINDOW_TICKS);
            for (Entry e : list) counts.merge(e.what, 1, Integer::sum);
            list.clear();
        }
        if (counts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        counts.forEach((what, n) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(what);
            if (n > 1) sb.append(" x").append(n);
        });
        return sb.toString();
    }

    public static void recordAchievement(UUID player, String title, String description) {
        List<Achievement> list = ACHIEVEMENTS.computeIfAbsent(player, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new Achievement(System.currentTimeMillis(),
                    description.isBlank() ? "'" + title + "'" : "'" + title + "' (" + description + ")"));
            while (list.size() > 5) list.remove(0);
        }
    }

    /** Advancements earned in the last minute and not yet mentioned, oldest first, or null. */
    public static @Nullable String drainAchievements(UUID player) {
        List<Achievement> list = ACHIEVEMENTS.get(player);
        if (list == null) return null;
        synchronized (list) {
            long now = System.currentTimeMillis();
            list.removeIf(a -> now - a.atMillis > ACHIEVEMENT_TTL_MILLIS);
            if (list.isEmpty()) return null;
            String out = String.join(", ", list.stream().map(Achievement::text).toList());
            list.clear();
            return out;
        }
    }

    public static void forget(UUID player) {
        LOG.remove(player);
        ACHIEVEMENTS.remove(player);
    }

    // ---- classification helpers used by the event hooks ----

    public static @Nullable String describeBroken(BlockState state) {
        String path = idPath(state.getBlock());
        if (path.endsWith("_ore") || path.equals("ancient_debris") || path.equals("spawner")
                || path.endsWith("amethyst_cluster") || path.equals("obsidian") || path.equals("crying_obsidian")) {
            return "mined " + pretty(path);
        }
        return null;
    }

    public static @Nullable String describePlaced(BlockState state) {
        String path = idPath(state.getBlock());
        return switch (path) {
            case "furnace", "blast_furnace", "smoker", "crafting_table", "chest", "enchanting_table", "anvil",
                 "brewing_stand", "campfire", "lodestone" -> "placed a " + pretty(path);
            case "torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern" -> "placed a torch";
            default -> path.endsWith("_bed") ? "placed a bed" : null;
        };
    }

    private static String idPath(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "" : id.getPath();
    }

    private static String pretty(String path) {
        return path.replace("deepslate_", "").replace('_', ' ');
    }
}
