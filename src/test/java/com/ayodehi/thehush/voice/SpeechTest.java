package com.ayodehi.thehush.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeechTest {
    @Test
    void stageDirectionsAndMarkupGo() {
        assertEquals("Aye, plenty. Follow the stone up.", Speech.clean("*points* Aye, plenty. Follow the stone up."));
        assertEquals("There, four.", Speech.clean("There, `four`."));
    }

    @Test
    void coordinatesAreSpokenAsThere() {
        assertEquals("The coal is there, up a ways.", Speech.clean("The coal is (-111, 77, -18), up a ways."));
        assertEquals("Try there.", Speech.clean("Try (12, ~, -40)."));
    }

    @Test
    void silenceIsEmpty() {
        assertEquals("", Speech.clean("..."));
        assertEquals("", Speech.clean("*shrugs*"));
        assertEquals("", Speech.clean(null));
        assertEquals("", Speech.spoken("[sighs] ...", true));
    }

    @Test
    void cuesAreHeardNotSeen() {
        String line = "[whispers] Not here. [low, urgent] Behind you, Ben.";
        assertEquals(List.of("whispers", "low, urgent"), Speech.cues(line));
        assertEquals("Not here. Behind you, Ben.", Speech.shown(line));
        assertEquals(line, Speech.spoken(line, true));
        assertEquals("Not here. Behind you, Ben.", Speech.spoken(line, false));
        assertEquals(Speech.Manner.WHISPER, Speech.manner(Speech.cues(line)));
    }

    @Test
    void coordinatesInBracketsAreNotCues() {
        String line = "It is at [12, 64, -8].";
        assertEquals(List.of(), Speech.cues(line));
        assertEquals(line, Speech.shown(line));
    }

    @Test
    void aCueIsAddedOnlyWhenNoneIsThere() {
        assertEquals("[whispers] Stay close.", Speech.withCue("Stay close.", "whispers"));
        assertEquals("[sharp] Run.", Speech.withCue("[sharp] Run.", "whispers"));
        assertEquals(Speech.Manner.SHOUT, Speech.manner(List.of("calls out")));
        assertEquals(Speech.Manner.PLAIN, Speech.manner(List.of()));
    }
}
