package com.ayodehi.thehush.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SonarTest {
    @Test
    void sprintingIsHeardFurthestAndSneakingBarely() {
        assertEquals(Sonar.SPRINT, Sonar.hearingRange(true, false, true, false, true));
        assertEquals(Sonar.WALK, Sonar.hearingRange(false, false, true, false, true));
        assertEquals(Sonar.SNEAK, Sonar.hearingRange(false, true, true, false, true));
    }

    @Test
    void standingStillAndSneakingIsSilentButBreathingIsNot() {
        assertEquals(0.0, Sonar.hearingRange(false, true, false, false, true));
        assertEquals(Sonar.BREATHING, Sonar.hearingRange(false, false, false, false, true));
    }

    @Test
    void jumpingGivesYouAwayEvenWhileSneaking() {
        assertEquals(Sonar.SPRINT, Sonar.hearingRange(false, true, false, true, true));
    }

    @Test
    void aWallHalvesEverything() {
        assertEquals(Sonar.WALK / 2, Sonar.hearingRange(false, false, true, false, false));
        assertEquals(0.0, Sonar.hearingRange(false, true, false, false, false));
    }
}
