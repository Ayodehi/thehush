package com.ayodehi.thehush;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The mod was called "villagerai" before it was "thehush". Config and per-world files under the old name
 * are moved over once, with the old namespace rewritten inside them. Entities saved under the old ids
 * cannot be carried across (the game drops unknown entity types on load); he must be placed again.
 */
public final class Migration {
    private static final String OLD = "villagerai";

    private Migration() {}

    /** Before the config is registered: bring the old toml and the old personas/campaigns folder across. */
    public static void migrateConfig() {
        Path cfg = FMLPaths.CONFIGDIR.get();
        copyIfMissing(cfg.resolve(OLD + "-common.toml"), cfg.resolve(TheHushMod.MODID + "-common.toml"));
        Path oldDir = cfg.resolve(OLD), newDir = cfg.resolve(TheHushMod.MODID);
        if (Files.isDirectory(oldDir) && !Files.exists(newDir)) copyTree(oldDir, newDir);
    }

    /** Before anything touches the world folder: campaign, memory, and returning files. */
    public static void migrateWorld(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path oldDir = root.resolve(OLD), newDir = root.resolve(TheHushMod.MODID);
        if (Files.isDirectory(oldDir) && !Files.exists(newDir)) {
            copyTree(oldDir, newDir);
            TheHushMod.LOGGER.info("Migrated {} to {}", oldDir, newDir);
        }
    }

    private static void copyIfMissing(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) return;
        try {
            Files.writeString(to, rewrite(Files.readString(from, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            TheHushMod.LOGGER.info("Migrated {} to {}", from.getFileName(), to.getFileName());
        } catch (IOException e) {
            TheHushMod.LOGGER.warn("Could not migrate {}", from, e);
        }
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else if (p.toString().endsWith(".json") || p.toString().endsWith(".toml")) {
                    Files.writeString(target, rewrite(Files.readString(p, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                } else {
                    Files.copy(p, target);
                }
            }
        } catch (IOException e) {
            TheHushMod.LOGGER.warn("Could not migrate {} to {}", from, to, e);
        }
    }

    /** Old ids inside the files: texture paths, the Quiet dimension, the Pilgrim entity, level ids. */
    static String rewrite(String text) {
        return text.replace(OLD + ":", TheHushMod.MODID + ":");
    }
}
