package com.ayodehi.thehush.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentTest {
    @Test
    void stayingPut() {
        assertEquals(Intent.Travel.STAY, Intent.travel("stay here while I explore"));
        assertEquals(Intent.Travel.STAY, Intent.travel("Wait here."));
        assertEquals(Intent.Travel.STAY, Intent.travel("stop following me"));
        assertEquals(Intent.Travel.STAY, Intent.travel("don't follow me down there"));
        assertEquals(Intent.Travel.STAY, Intent.travel("stay put, I'll be back"));
    }

    @Test
    void comingAlong() {
        assertEquals(Intent.Travel.COME, Intent.travel("come with me"));
        assertEquals(Intent.Travel.COME, Intent.travel("you should come inside"));
        assertEquals(Intent.Travel.COME, Intent.travel("follow me"));
        assertEquals(Intent.Travel.COME, Intent.travel("stay close to me"));
        assertEquals(Intent.Travel.COME, Intent.travel("Come."));
    }

    @Test
    void coordinatesOnlyWhenAskedFor() {
        org.junit.jupiter.api.Assertions.assertTrue(Intent.wantsCoordinates("what are the coordinates?"));
        org.junit.jupiter.api.Assertions.assertTrue(Intent.wantsCoordinates("give me the coords"));
        org.junit.jupiter.api.Assertions.assertTrue(Intent.wantsCoordinates("exact location please"));
        org.junit.jupiter.api.Assertions.assertFalse(Intent.wantsCoordinates("where is the nearest coal?"));
        org.junit.jupiter.api.Assertions.assertFalse(Intent.wantsCoordinates("which way to the village?"));
    }

    @Test
    void anythingElseIsLeftToHim() {
        assertEquals(Intent.Travel.NONE, Intent.travel("is there coal nearby?"));
        assertEquals(Intent.Travel.NONE, Intent.travel("where did you come from?"));
        assertEquals(Intent.Travel.NONE, Intent.travel("I'll wait here for the storm to pass"));
        assertEquals(Intent.Travel.NONE, Intent.travel(""));
    }
}
