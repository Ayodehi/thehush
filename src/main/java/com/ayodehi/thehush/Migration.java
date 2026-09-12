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
        nestSections(cfg.resolve(TheHushMod.MODID + "-common.toml"));
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

    /**
     * Settings that used to sit flat under a section now live in per-backend subsections ([llm.anthropic],
     * [llm.ollama], [voice.elevenlabs], ...). Move the old keys where they belong so nobody's API key is
     * silently dropped as "unknown" and replaced with the default.
     */
    private static void nestSections(Path toml) {
        if (!Files.exists(toml)) return;
        try {
            String before = Files.readString(toml, StandardCharsets.UTF_8);
            String after = nestVoiceSections(nestLlmSections(before));
            if (!after.equals(before)) {
                Files.writeString(toml, after, StandardCharsets.UTF_8);
                TheHushMod.LOGGER.info("Moved the provider settings in {} into their own sections", toml.getFileName());
            }
        } catch (IOException e) {
            TheHushMod.LOGGER.warn("Could not update {}", toml, e);
        }
    }

    /** Where each old flat key under a section goes: subsection name -> (old key -> new key). */
    private record Move(String subsection, java.util.Map<String, String> keys) {}

    private static final java.util.List<Move> LLM_MOVES = java.util.List.of(
            new Move("anthropic", keysAsIs("apiKey", "apiKeyEnvVar", "baseUrl", "model", "effort", "inputPricePerMTok", "outputPricePerMTok")),
            new Move("ollama", java.util.Map.of("ollamaUrl", "url", "ollamaModel", "model", "ollamaContext", "context", "ollamaThink", "think")));

    private static final java.util.List<Move> VOICE_MOVES = java.util.List.of(
            new Move("elevenlabs", keysAsIs("apiKey", "apiKeyEnvVar", "voiceId", "model", "stability", "similarity", "pricePerThousandChars")));

    private static java.util.Map<String, String> keysAsIs(String... keys) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String k : keys) m.put(k, k);
        return m;
    }

    static String nestLlmSections(String text) {
        return nestSection(text, "llm", LLM_MOVES);
    }

    static String nestVoiceSections(String text) {
        return nestSection(text, "voice", VOICE_MOVES);
    }

    /** Pure text transform; the file is left alone when it already has the subsections or has nothing to move. */
    static String nestSection(String text, String section, java.util.List<Move> moves) {
        String header = "[" + section + "]";
        if (!text.contains(header) || text.contains("[" + section + "." + moves.get(0).subsection() + "]")) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        java.util.List<String> keep = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<String>> moved = new java.util.LinkedHashMap<>();
        for (Move mv : moves) moved.put(mv.subsection(), new java.util.ArrayList<>());
        java.util.List<String> pendingComments = new java.util.ArrayList<>();
        boolean inSection = false;
        boolean any = false;
        int i = 0;
        java.util.regex.Pattern kv = java.util.regex.Pattern.compile("^(\\s*)([A-Za-z0-9_]+)(\\s*=\\s*)(.*)$");
        for (; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (!inSection) {
                out.append(line).append('\n');
                if (trimmed.equals(header)) inSection = true;
                continue;
            }
            if (trimmed.startsWith("[")) break;
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                pendingComments.add(line);
                continue;
            }
            java.util.regex.Matcher m = kv.matcher(line);
            Move target = null;
            if (m.matches()) {
                for (Move mv : moves) if (mv.keys().containsKey(m.group(2))) target = mv;
            }
            if (target == null) {
                keep.addAll(pendingComments);
                keep.add(line);
            } else {
                java.util.List<String> dest = moved.get(target.subsection());
                dest.addAll(pendingComments);
                dest.add(m.group(1) + target.keys().get(m.group(2)) + m.group(3) + m.group(4));
                any = true;
            }
            pendingComments.clear();
        }
        if (!any) return text;
        for (String l : keep) out.append(l).append('\n');
        for (var e : moved.entrySet()) {
            out.append('\n').append("\t[").append(section).append('.').append(e.getKey()).append("]\n");
            for (String l : e.getValue()) out.append(l).append('\n');
        }
        out.append('\n');
        for (; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** Old ids inside the files: texture paths, the Quiet dimension, the Pilgrim entity, level ids. */
    static String rewrite(String text) {
        return text.replace(OLD + ":", TheHushMod.MODID + ":");
    }
}
