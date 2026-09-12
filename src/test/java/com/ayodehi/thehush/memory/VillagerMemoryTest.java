package com.ayodehi.thehush.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerMemoryTest {
    @Test
    void notesRoundTripThroughDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mem.json");
        UUID villager = UUID.randomUUID();
        UUID ben = UUID.randomUUID();

        VillagerMemory m = VillagerMemory.load(file, villager, "traveller");
        VillagerMemory.Note n1 = m.rememberAboutPlayer(ben, "Ben", "Prefers to be called Ben.", 3);
        m.rememberAboutPlayer(ben, "Ben", "Afraid of caves.", 4);
        VillagerMemory.Note w = m.rememberGeneral("A thunderstorm broke on day 4.", 4);
        assertTrue(Files.exists(file));

        VillagerMemory again = VillagerMemory.load(file, villager, "traveller");
        assertEquals(3, again.size());
        assertEquals(2, again.notesAbout(ben).size());
        assertEquals("Prefers to be called Ben.", again.notesAbout(ben).get(0).text);

        String prompt = again.promptSection(ben, "Ben");
        assertTrue(prompt.contains("What you remember about Ben"));
        assertTrue(prompt.contains("[" + n1.id + "] day 3: Prefers to be called Ben."));
        assertTrue(prompt.contains("Other things you remember"));
        assertTrue(prompt.contains(w.text));

        assertTrue(again.forget(n1.id));
        assertFalse(again.forget(n1.id));
        assertEquals(2, VillagerMemory.load(file, villager, "traveller").size());
    }

    @Test
    void emptyMemoryAddsNothingToThePrompt(@TempDir Path dir) {
        VillagerMemory m = VillagerMemory.load(dir.resolve("x.json"), UUID.randomUUID(), "p");
        assertEquals("", m.promptSection(UUID.randomUUID(), "Someone"));
        assertEquals("", m.promptSection(null, null));
    }

    @Test
    void longNotesAreClippedAndOldOnesDropped(@TempDir Path dir) {
        VillagerMemory m = VillagerMemory.load(dir.resolve("x.json"), UUID.randomUUID(), "p");
        UUID p = UUID.randomUUID();
        String big = "x".repeat(1000);
        assertEquals(VillagerMemory.MAX_NOTE_LENGTH, m.rememberAboutPlayer(p, "P", big, 1).text.length());
        for (int i = 0; i < VillagerMemory.MAX_NOTES_PER_PLAYER + 5; i++) m.rememberAboutPlayer(p, "P", "note " + i, 1);
        assertEquals(VillagerMemory.MAX_NOTES_PER_PLAYER, m.notesAbout(p).size());
    }

    @Test
    void forgottenNameLeavesNoTrace(@TempDir Path dir) throws Exception {
        VillagerMemory m = VillagerMemory.load(dir.resolve("mem.json"), UUID.randomUUID(), "traveller");
        UUID id = UUID.randomUUID();
        m.rememberAboutPlayer(id, "Dev", "Prefers to be called Ben (not Dev).", 1);
        m.rememberAboutPlayer(id, "Dev", "Dev's nether portal is in a soul sand valley.", 2);
        m.rememberAboutPlayer(id, "Dev", "Afraid of caves.", 2);

        String remembered = m.promptSection(id, "Dev", VillagerMemory.View.ALL);
        assertTrue(remembered.contains("called Ben"));
        assertTrue(remembered.contains("Dev's nether portal"));

        String forgotten = m.promptSection(id, "Dev", new VillagerMemory.View(true, Long.MAX_VALUE, false));
        assertFalse(forgotten.contains("Ben"), "the name note must be hidden");
        assertFalse(forgotten.contains("Dev"), "the tag must be masked in note text: " + forgotten);
        assertTrue(forgotten.contains("the player's nether portal"));
        assertTrue(forgotten.contains("Afraid of caves."));
        assertTrue(forgotten.contains("about this player"));
    }
}
