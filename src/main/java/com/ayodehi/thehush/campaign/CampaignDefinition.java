package com.ayodehi.thehush.campaign;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A story arc: an ordered list of stages, what each return from death costs, and the name that ends it.
 * Loaded from config/thehush/campaigns/&lt;id&gt;.json so the text can be edited without touching Java.
 * The bundled campaign ("the_hush") is written there when missing.
 */
public final class CampaignDefinition {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String id = "";
    public String title = "";
    /** The Traveller's true name; never in the prompt until the player says it. */
    public String trueName = "";
    public List<CampaignStage> stages = new ArrayList<>();
    public List<Forgetting> forgetting = new ArrayList<>();
    /** Standing rule for every stage, appended to the prompt. */
    public String guidanceRule = "";
    /** Things he says when the chosen reaches a place; see Place. */
    public List<Place> places = new ArrayList<>();

    /**
     * A location beat. Exactly one of the where-fields is set: structure (id or #tag), piece (structure piece
     * type path, e.g. "shli" for the stronghold library, "shpr" for its portal room), biome, dimension,
     * belowY, nearBlock (+radius), nearEntity (+radius). fromStage gates it to that stage or later; untilStage
     * to that stage or earlier. once=true fires a single time per campaign; otherwise repeatMinutes applies.
     */
    public static final class Place {
        public String id = "";
        public String structure;
        public String piece;
        public String biome;
        public String dimension;
        public Integer belowY;
        public String nearBlock;
        public String nearEntity;
        public int radius = 8;
        public String fromStage;
        public String untilStage;
        public boolean once = true;
        public int repeatMinutes = 10;
        public String prompt = "";

        public boolean matches(PlaceSnapshot s) {
            if (structure != null) return s.inStructure(structure);
            if (piece != null) return s.inPiece(piece);
            if (biome != null) return s.inBiome(biome);
            if (dimension != null) return s.inDimension(dimension);
            if (belowY != null) return s.y() < belowY;
            if (nearBlock != null) return s.blockNear(nearBlock, radius);
            if (nearEntity != null) return s.entityNear(nearEntity, radius);
            return false;
        }
    }

    /** What a Place may ask about the chosen's surroundings. */
    public interface PlaceSnapshot {
        boolean inStructure(String idOrTag);
        boolean inPiece(String piecePath);
        boolean inBiome(String id);
        boolean inDimension(String id);
        int y();
        boolean blockNear(String id, int radius);
        boolean entityNear(String id, int radius);
    }

    /** A memory lost after N returns from death. */
    public static final class Forgetting {
        public int deaths;
        /** One of: recent_days, player_name, mission_next, own_fear, crossings. */
        public String forget = "";
        public String prompt = "";
    }

    public static CampaignDefinition load(Path file) throws IOException {
        try {
            CampaignDefinition d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CampaignDefinition.class);
            if (d == null || d.stages == null || d.stages.isEmpty()) throw new IOException("campaign " + file.getFileName() + " has no stages");
            if (d.id == null || d.id.isBlank()) {
                String name = file.getFileName().toString();
                d.id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            }
            if (d.forgetting == null) d.forgetting = new ArrayList<>();
            if (d.places == null) d.places = new ArrayList<>();
            for (CampaignStage s : d.stages) s.normalize();
            return d;
        } catch (JsonSyntaxException e) {
            throw new IOException("campaign " + file.getFileName() + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static CampaignDefinition fromJson(String json) {
        CampaignDefinition d = GSON.fromJson(json, CampaignDefinition.class);
        if (d.forgetting == null) d.forgetting = new ArrayList<>();
        if (d.places == null) d.places = new ArrayList<>();
        for (CampaignStage s : d.stages) s.normalize();
        return d;
    }

    public @Nullable CampaignStage stage(String id) {
        for (CampaignStage s : stages) if (s.id.equals(id)) return s;
        return null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).id.equals(id)) return i;
        return -1;
    }

    public CampaignStage first() {
        return stages.get(0);
    }

    public @Nullable CampaignStage after(String id) {
        int i = indexOf(id);
        return i < 0 || i + 1 >= stages.size() ? null : stages.get(i + 1);
    }

    /** The bundled campaign. Text mirrors docs/narrative.md. */
    public static CampaignDefinition theHush() {
        return fromJson(TheHush.JSON);
    }
}
