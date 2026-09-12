package com.ayodehi.thehush.memory;

import com.ayodehi.thehush.TheHushMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Finds (and caches) the memory file for a villager inside the current world's save folder. */
public final class MemoryStore {
    private static final Map<UUID, VillagerMemory> CACHE = new ConcurrentHashMap<>();

    private MemoryStore() {}

    public static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(TheHushMod.MODID).resolve("memory");
    }

    public static VillagerMemory forVillager(MinecraftServer server, UUID villagerId, String personaId) {
        return CACHE.computeIfAbsent(villagerId,
                id -> VillagerMemory.load(directory(server).resolve(id + ".json"), id, personaId));
    }

    /** Drop cached memories when the server (world) stops so the next world starts clean. */
    public static void clear() {
        CACHE.clear();
    }
}
