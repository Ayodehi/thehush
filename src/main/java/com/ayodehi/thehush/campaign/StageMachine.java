package com.ayodehi.thehush.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The pure part of stage progression, kept free of Minecraft types so it can be unit tested. */
public final class StageMachine {
    private StageMachine() {}

    /** Whether to enter the next stage, and which later stages fired early (to be remembered as reached: flags). */
    /** Flags a save should not have: reached: marks for stages whose trigger is relative (a bug in earlier builds set them). */
    public static java.util.List<String> staleReachedFlags(CampaignDefinition def, Set<String> flags) {
        java.util.List<String> stale = new ArrayList<>();
        for (CampaignStage st : def.stages) {
            if (st.enter != null && st.enter.isRelative() && flags.contains("reached:" + st.id)) stale.add("reached:" + st.id);
        }
        return stale;
    }

    public record Result(boolean enterNext, List<String> reachedEarly) {}

    public static Result evaluate(CampaignDefinition def, String currentStage, Set<String> flags, CampaignStage.Snapshot snap) {
        int current = def.indexOf(currentStage);
        if (current < 0 || current + 1 >= def.stages.size()) return new Result(false, List.of());
        CampaignStage next = def.stages.get(current + 1);
        boolean enterNext = flags.contains("reached:" + next.id) || (next.enter != null && next.enter.test(snap));
        List<String> early = new ArrayList<>();
        for (int i = current + 2; i < def.stages.size(); i++) {
            CampaignStage later = def.stages.get(i);
            // A day count is relative to the stage before it; a day spent anywhere else must not pre-arm it.
            if (later.enter == null || later.enter.isRelative()) continue;
            if (later.enter.test(snap) && !flags.contains("reached:" + later.id)) early.add(later.id);
        }
        return new Result(enterNext, early);
    }
}
