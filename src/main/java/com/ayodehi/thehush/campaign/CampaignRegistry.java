package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The campaigns: the bundled one from the jar, then any config/thehush/campaigns/*.json a modder has put
 * there (same id replaces it; a new id adds). Nothing is ever written to the config folder.
 */
public final class CampaignRegistry {
    private static final CampaignRegistry INSTANCE = new CampaignRegistry();
    private final Map<String, CampaignDefinition> campaigns = new TreeMap<>();

    private CampaignRegistry() {}

    public static CampaignRegistry get() {
        return INSTANCE;
    }

    public Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(TheHushMod.MODID).resolve("campaigns");
    }

    public synchronized void reload() {
        campaigns.clear();
        CampaignDefinition bundled = CampaignDefinition.theHush();
        campaigns.put(bundled.id, bundled);
        Path dir = directory();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(this::loadOne);
            } catch (IOException e) {
                TheHushMod.LOGGER.error("Could not read campaign directory {}", dir, e);
            }
        }
        if (campaigns.isEmpty()) {
            CampaignDefinition d = CampaignDefinition.theHush();
            campaigns.put(d.id, d);
        }
    }

    private void loadOne(Path file) {
        try {
            CampaignDefinition d = CampaignDefinition.load(file);
            TheHushMod.LOGGER.info("Campaign {} {} by {}", d.id, campaigns.containsKey(d.id) ? "overridden" : "added", file.getFileName());
            campaigns.put(d.id, d);
        } catch (IOException e) {
            TheHushMod.LOGGER.error("Skipping campaign {}: {}", file.getFileName(), e.getMessage());
        }
    }

    public synchronized @Nullable CampaignDefinition find(String id) {
        return campaigns.get(id);
    }

    public synchronized Set<String> ids() {
        return new TreeSet<>(campaigns.keySet());
    }
}
