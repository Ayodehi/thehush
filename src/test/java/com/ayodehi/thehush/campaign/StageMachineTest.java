package com.ayodehi.thehush.campaign;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StageMachineTest {

    /** A snapshot where only the named facts are true. */
    private static CampaignStage.Snapshot world(Set<String> flags, long days, String... facts) {
        Set<String> f = Set.of(facts);
        return new CampaignStage.Snapshot() {
            public boolean hasAdvancement(String id) { return f.contains("adv:" + id); }
            public boolean inStructure(String id) { return f.contains("structure:" + id); }
            public boolean inBiome(String id) { return f.contains("biome:" + id); }
            public boolean inDimension(String id) { return f.contains("dim:" + id); }
            public boolean carries(String itemId) { return f.contains("item:" + itemId); }
            public boolean hasFlag(String flag) { return flags.contains(flag); }
            public long daysOnStage() { return days; }
        };
    }

    @Test
    void bundledCampaignParsesInOrder() {
        CampaignDefinition d = CampaignDefinition.theHush();
        assertEquals("the_hush", d.id);
        assertEquals("Vesper", d.trueName);
        List<String> ids = d.stages.stream().map(s -> s.id).toList();
        assertEquals(List.of("arrival", "geared", "first_night", "stronghold", "end", "dragon", "deep_dark",
                "ancient_city", "named", "quiet", "ended"), ids);
        assertNull(d.first().enter, "the first stage is entered by meeting him, not by a trigger");
        for (int i = 1; i < d.stages.size(); i++) assertNotNull(d.stages.get(i).enter, d.stages.get(i).id + " needs a trigger");
        assertEquals(5, d.forgetting.size());
    }

    @Test
    void bundledPlacesAreWellFormed() {
        CampaignDefinition d = CampaignDefinition.theHush();
        assertTrue(d.places.size() >= 10);
        java.util.Set<String> ids = new HashSet<>();
        for (CampaignDefinition.Place p : d.places) {
            assertTrue(ids.add(p.id), "duplicate place id " + p.id);
            assertFalse(p.prompt.isBlank(), p.id + " needs a prompt");
            int wheres = (p.structure != null ? 1 : 0) + (p.piece != null ? 1 : 0) + (p.biome != null ? 1 : 0)
                    + (p.dimension != null ? 1 : 0) + (p.belowY != null ? 1 : 0) + (p.nearBlock != null ? 1 : 0) + (p.nearEntity != null ? 1 : 0);
            assertEquals(1, wheres, p.id + " must name exactly one place");
            if (p.fromStage != null) assertTrue(d.indexOf(p.fromStage) >= 0, p.id + " fromStage unknown");
            if (p.untilStage != null) assertTrue(d.indexOf(p.untilStage) >= 0, p.id + " untilStage unknown");
        }
    }

    @Test
    void jsonRoundTrip() {
        CampaignDefinition d = CampaignDefinition.theHush();
        CampaignDefinition again = CampaignDefinition.fromJson(d.toJson());
        assertEquals(d.toJson(), again.toJson());
        assertEquals(d.stage("stronghold").guidance, again.stage("stronghold").guidance);
    }

    @Test
    void advancesOnlyToTheNextStage() {
        CampaignDefinition d = CampaignDefinition.theHush();
        Set<String> flags = new HashSet<>();
        // Nothing true: stay.
        assertFalse(StageMachine.evaluate(d, "arrival", flags, world(flags, 0)).enterNext());
        // Armor earned: arrival -> geared.
        StageMachine.Result r = StageMachine.evaluate(d, "arrival", flags, world(flags, 0, "adv:minecraft:story/obtain_armor"));
        assertTrue(r.enterNext());
        assertTrue(r.reachedEarly().isEmpty());
    }

    @Test
    void earlyTriggersBecomeFlagsNotSkips() {
        CampaignDefinition d = CampaignDefinition.theHush();
        Set<String> flags = new HashSet<>();
        // Standing in a stronghold before ever wearing armor.
        StageMachine.Result r = StageMachine.evaluate(d, "arrival", flags, world(flags, 0, "structure:minecraft:stronghold"));
        assertFalse(r.enterNext());
        assertEquals(List.of("stronghold"), r.reachedEarly());
        flags.add("reached:stronghold");
        // Later, armor: geared, then first_night after a day, then stronghold from the remembered flag.
        assertTrue(StageMachine.evaluate(d, "arrival", flags, world(flags, 0, "adv:minecraft:story/obtain_armor")).enterNext());
        assertFalse(StageMachine.evaluate(d, "geared", flags, world(flags, 0)).enterNext());
        assertTrue(StageMachine.evaluate(d, "geared", flags, world(flags, 1)).enterNext());
        assertTrue(StageMachine.evaluate(d, "first_night", flags, world(flags, 0)).enterNext(), "the flag carries the early visit");
        // Already-flagged stages are not reported again.
        assertTrue(StageMachine.evaluate(d, "arrival", flags, world(flags, 0, "structure:minecraft:stronghold")).reachedEarly().isEmpty());
    }

    @Test
    void dayCountsAreNeverPreArmed() {
        CampaignDefinition d = CampaignDefinition.theHush();
        Set<String> flags = new HashSet<>();
        // A day spent on arrival must not count toward first_night, which is a day after geared.
        StageMachine.Result r = StageMachine.evaluate(d, "arrival", flags, world(flags, 3));
        assertFalse(r.enterNext());
        assertTrue(r.reachedEarly().isEmpty(), "first_night was pre-armed by time spent on an earlier stage");
        assertTrue(d.stage("first_night").enter.isRelative());
        assertFalse(d.stage("stronghold").enter.isRelative());
        // Once geared, the count starts over.
        assertFalse(StageMachine.evaluate(d, "geared", flags, world(flags, 0)).enterNext());
        assertTrue(StageMachine.evaluate(d, "geared", flags, world(flags, 1)).enterNext());
        // A save from before the fix is repaired.
        flags.add("reached:first_night");
        flags.add("reached:stronghold");
        assertEquals(List.of("reached:first_night"), StageMachine.staleReachedFlags(d, flags));
    }

    @Test
    void anyAndAllCombinators() {
        CampaignDefinition d = CampaignDefinition.fromJson("""
                {"id":"t","stages":[
                  {"id":"a"},
                  {"id":"b","enter":{"all":[{"flag":"x"},{"item":"minecraft:bell"}]}},
                  {"id":"c","enter":{"any":[{"dimension":"minecraft:the_end"},{"biome":"minecraft:deep_dark"}]}}
                ]}""");
        Set<String> flags = new HashSet<>();
        assertFalse(d.stage("b").enter.test(world(flags, 0, "item:minecraft:bell")));
        flags.add("x");
        assertTrue(d.stage("b").enter.test(world(flags, 0, "item:minecraft:bell")));
        assertFalse(d.stage("c").enter.test(world(flags, 0)));
        assertTrue(d.stage("c").enter.test(world(flags, 0, "biome:minecraft:deep_dark")));
        assertTrue(d.stage("c").enter.test(world(flags, 0, "dim:minecraft:the_end")));
        assertNull(d.stage("a").enter);
    }

    @Test
    void lastStageStops() {
        CampaignDefinition d = CampaignDefinition.theHush();
        assertFalse(StageMachine.evaluate(d, "ended", Set.of(), world(Set.of(), 99, "dim:thehush:quiet")).enterNext());
        assertFalse(StageMachine.evaluate(d, "nope", Set.of(), world(Set.of(), 0)).enterNext());
    }
}
