package com.ayodehi.thehush.campaign;

import java.util.ArrayList;
import java.util.List;

/** One step of the road. Serialized straight from JSON; see CampaignDefinition. */
public final class CampaignStage {
    public String id = "";
    public String title = "";
    /** Conditions to enter this stage; see Trigger. Null or empty means "entered only by command". */
    public Trigger enter;
    /** What the Traveller is trying to get the player to do now. */
    public String purpose = "";
    /** Things he knows that help at this stage. */
    public List<String> guidance = new ArrayList<>();
    /** What he may admit about himself from this stage on. */
    public List<String> reveal = new ArrayList<>();
    /** [world] text spoken to him when the stage begins. */
    public String onEnter = "";
    /** Minutes on this stage before he nudges; 0 disables. */
    public int nudgeAfterMinutes;
    public String nudge = "";

    /**
     * A condition. Exactly one of the leaf fields is set, or one of any/all. Leaves:
     * advancement (id), structure (id), biome (id), dimension (id), item (item id the player carries),
     * flag (campaign flag name), daysSince (in-game days since the previous stage was entered).
     */
    public static final class Trigger {
        public List<Trigger> any;
        public List<Trigger> all;
        public String advancement;
        public String structure;
        public String biome;
        public String dimension;
        public String item;
        public String flag;
        public Integer daysSince;

        public boolean isEmpty() {
            return (any == null || any.isEmpty()) && (all == null || all.isEmpty()) && advancement == null
                    && structure == null && biome == null && dimension == null && item == null && flag == null
                    && daysSince == null;
        }

        /**
         * True when the condition is measured from the current stage (daysSince), so it means something only
         * once the stage before it is the current one. Such triggers are never pre-armed as reached: flags.
         */
        public boolean isRelative() {
            if (daysSince != null) return true;
            if (any != null) for (Trigger t : any) if (t.isRelative()) return true;
            if (all != null) for (Trigger t : all) if (t.isRelative()) return true;
            return false;
        }

        /** Evaluates against a snapshot of the world; see CampaignTracker.Snapshot. */
        public boolean test(Snapshot s) {
            if (any != null && !any.isEmpty()) {
                for (Trigger t : any) if (t.test(s)) return true;
                return false;
            }
            if (all != null && !all.isEmpty()) {
                for (Trigger t : all) if (!t.test(s)) return false;
                return true;
            }
            if (advancement != null) return s.hasAdvancement(advancement);
            if (structure != null) return s.inStructure(structure);
            if (biome != null) return s.inBiome(biome);
            if (dimension != null) return s.inDimension(dimension);
            if (item != null) return s.carries(item);
            if (flag != null) return s.hasFlag(flag);
            if (daysSince != null) return s.daysOnStage() >= daysSince;
            return false;
        }
    }

    /** What a trigger may ask about. The tracker fills one per second from the live world; tests fake it. */
    public interface Snapshot {
        boolean hasAdvancement(String id);
        boolean inStructure(String id);
        boolean inBiome(String id);
        boolean inDimension(String id);
        boolean carries(String itemId);
        boolean hasFlag(String flag);
        long daysOnStage();
    }

    void normalize() {
        if (id == null) id = "";
        if (title == null) title = id;
        if (purpose == null) purpose = "";
        if (guidance == null) guidance = new ArrayList<>();
        if (reveal == null) reveal = new ArrayList<>();
        if (onEnter == null) onEnter = "";
        if (nudge == null) nudge = "";
        if (enter != null && enter.isEmpty()) enter = null;
    }
}
