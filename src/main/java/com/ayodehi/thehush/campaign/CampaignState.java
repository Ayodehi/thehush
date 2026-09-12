package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where one world is on the road. Saved as &lt;world&gt;/thehush/campaign.json with the same atomic write
 * as the memory files. Everything the later phases need to remember lives here so a restart loses nothing.
 */
public final class CampaignState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String campaignId = "";
    public String stage = "";
    public @Nullable String chosenPlayer;
    public String chosenName = "";
    public Set<String> flags = new LinkedHashSet<>();
    public long stageEnteredDay;
    public long stageEnteredTick;
    public long lastNudgeTick = Long.MIN_VALUE / 2;
    public int deaths;
    public int failedAttempts;
    public int touches;
    public Set<String> forgotten = new LinkedHashSet<>();
    public List<Visit> history = new ArrayList<>();
    /** Scripted blocks placed in the Overworld ("x,y,z,previous_block_id"); restored at the ending. */
    public List<String> placed = new ArrayList<>();
    public int @Nullable [] strongholdEntry;
    public int @Nullable [] library;
    public long dragonDay = -1;
    public boolean quietBuilt;
    /** Where he was last seen (dimension id and x,y,z) and whether he was following; used to fetch him from unloaded chunks. */
    public @Nullable String travellerLevel;
    public int @Nullable [] travellerPos;
    public boolean travellerFollowing;
    /** A chunk this mod force-loaded while looking for him: level id, cx, cz. Released on the next load if left behind. */
    public @Nullable String forcedChunk;
    /** Centre of the ancient city frame, once found: x, y, z. */
    public int @Nullable [] frame;
    /** Where the bell goes: the top of the pedestal built at the frame's foot (x, y, z of the bell block). */
    public int @Nullable [] pedestal;
    /** Where he first arrived (the village), for the bell and the ending. */
    public int @Nullable [] arrival;
    public @Nullable String missingVillager;
    public boolean strangerToPlayer;
    /** The opening storm: game time it ends (0 = not started), and whether he has arrived. */
    public long stormUntilTick;
    public boolean arrivalDone;
    /** True once he has a village: he arrived at one, or the chosen found one later and the arrival point moved to its well. */
    public boolean arrivalVillage;
    /** The hunters: when the next is due (0 = not scheduled), how many have been sent, how many killed, and the live one. */
    public long nextHuntTick;
    public int hunts;
    public int echoesKilled;
    public @Nullable String echo;

    public static final class Visit {
        public String stage = "";
        public long day;
    }

    private transient @Nullable Path file;

    public static CampaignState load(Path file) {
        CampaignState s = null;
        if (Files.exists(file)) {
            try {
                s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CampaignState.class);
            } catch (IOException | JsonSyntaxException e) {
                TheHushMod.LOGGER.warn("Could not read {}; starting a fresh campaign", file, e);
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        if (s == null) s = new CampaignState();
        if (s.flags == null) s.flags = new LinkedHashSet<>();
        if (s.forgotten == null) s.forgotten = new LinkedHashSet<>();
        if (s.history == null) s.history = new ArrayList<>();
        if (s.placed == null) s.placed = new ArrayList<>();
        if (s.chosenName == null) s.chosenName = "";
        s.file = file;
        return s;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TheHushMod.LOGGER.error("Could not save {}", file, e);
        }
    }

    public @Nullable Path file() {
        return file;
    }

    public boolean started() {
        return !stage.isEmpty();
    }

    /** Back to nothing, keeping the file path. */
    public void reset() {
        campaignId = "";
        stage = "";
        chosenPlayer = null;
        chosenName = "";
        flags.clear();
        stageEnteredDay = 0;
        stageEnteredTick = 0;
        lastNudgeTick = Long.MIN_VALUE / 2;
        deaths = 0;
        failedAttempts = 0;
        touches = 0;
        forgotten.clear();
        history.clear();
        placed.clear();
        frame = null;
        pedestal = null;
        arrival = null;
        strongholdEntry = null;
        library = null;
        dragonDay = -1;
        travellerLevel = null;
        travellerPos = null;
        travellerFollowing = false;
        forcedChunk = null;
        missingVillager = null;
        strangerToPlayer = false;
        stormUntilTick = 0;
        arrivalDone = false;
        arrivalVillage = false;
        nextHuntTick = 0;
        hunts = 0;
        echoesKilled = 0;
        echo = null;
        save();
    }
}
