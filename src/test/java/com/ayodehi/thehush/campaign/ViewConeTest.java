package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.entity.ViewCone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewConeTest {
    @Test
    void straightAheadIsSeen() {
        assertTrue(ViewCone.contains(0, 0, 1, 0, 0, 10, ViewCone.DEFAULT_THRESHOLD));
    }

    @Test
    void behindIsNotSeen() {
        assertFalse(ViewCone.contains(0, 0, 1, 0, 0, -10, ViewCone.DEFAULT_THRESHOLD));
        assertFalse(ViewCone.contains(1, 0, 0, -3, 0, 1, ViewCone.DEFAULT_THRESHOLD));
    }

    @Test
    void peripheryCountsUpToAboutSeventyDegrees() {
        // 60 degrees off centre: cos = 0.5 > 0.35, seen.
        assertTrue(ViewCone.contains(0, 0, 1, Math.sin(Math.toRadians(60)), 0, Math.cos(Math.toRadians(60)), ViewCone.DEFAULT_THRESHOLD));
        // 80 degrees off centre: cos = 0.17 < 0.35, not seen.
        assertFalse(ViewCone.contains(0, 0, 1, Math.sin(Math.toRadians(80)), 0, Math.cos(Math.toRadians(80)), ViewCone.DEFAULT_THRESHOLD));
    }

    @Test
    void standingInsideItCountsAsSeen() {
        assertTrue(ViewCone.contains(0, 0, 1, 0, 0, 0, ViewCone.DEFAULT_THRESHOLD));
    }
}
