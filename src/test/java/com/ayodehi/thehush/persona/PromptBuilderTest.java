package com.ayodehi.thehush.persona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {
    @Test
    void forgettingFearDropsOnlyDarkSentences() {
        String text = "He is calm. True darkness undoes him: he asks for a torch. He likes rain! Dark rooms scare him? He hums.";
        assertEquals("He is calm. He likes rain! He hums.", PromptBuilder.withoutSentencesMentioning(text, "dark"));
    }

    @Test
    void travellerPromptLosesFearWhenAsked() {
        Persona t = Persona.travellerPersona();
        String full = PromptBuilder.systemPrompt(t, false);
        String lost = PromptBuilder.systemPrompt(t, true);
        assertTrue(full.contains("True darkness undoes him"));
        assertFalse(lost.contains("True darkness undoes him"));
        assertTrue(lost.contains("Calm, watchful"));
    }
}
