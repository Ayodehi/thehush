package com.ayodehi.thehush.persona;

import com.ayodehi.thehush.TheHushMod;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The personas: the bundled ones from the jar, then any config/thehush/personas/*.json a modder has put
 * there (same id replaces the bundled one; a new id adds). Nothing is ever written to the config folder.
 */
public final class PersonaRegistry {
    private static final PersonaRegistry INSTANCE = new PersonaRegistry();

    private final Map<String, Persona> personas = new TreeMap<>();

    private PersonaRegistry() {}

    public static PersonaRegistry get() {
        return INSTANCE;
    }

    public Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(TheHushMod.MODID).resolve("personas");
    }

    public synchronized void reload() {
        personas.clear();
        for (Persona bundled : Persona.bundled()) personas.put(bundled.id(), bundled);
        Path dir = directory();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(this::loadOne);
            } catch (IOException e) {
                TheHushMod.LOGGER.error("Could not read persona directory {}", dir, e);
            }
        }
        if (personas.isEmpty()) {
            Persona fallback = Persona.travellerPersona();
            personas.put(fallback.id(), fallback);
        }
    }

    private void loadOne(Path file) {
        try {
            Persona p = Persona.load(file);
            TheHushMod.LOGGER.info("Persona {} {} by {}", p.id(), personas.containsKey(p.id()) ? "overridden" : "added", file.getFileName());
            personas.put(p.id(), p);
        } catch (IOException e) {
            TheHushMod.LOGGER.error("Skipping persona {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** Entities in the spawn chunks deserialize before the server-starting hooks run; load on first use. */
    private synchronized void ensureLoaded() {
        if (personas.isEmpty()) reload();
    }

    public synchronized @Nullable Persona find(String id) {
        ensureLoaded();
        return personas.get(id);
    }

    /** Never throws: with nothing loaded at all, the bundled Traveller stands in. */
    public synchronized Persona findOrDefault(String id, String defaultId) {
        ensureLoaded();
        Persona p = personas.get(id);
        if (p != null) return p;
        p = personas.get(defaultId);
        if (p != null) return p;
        return personas.isEmpty() ? Persona.travellerPersona() : personas.values().iterator().next();
    }

    public synchronized Set<String> ids() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(personas.keySet()));
    }
}
