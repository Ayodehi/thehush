package com.ayodehi.thehush.entity;

/**
 * How far a blind hunter hears a player, from what the player is doing. Kept free of Minecraft types so
 * it can be unit tested. Distances are in blocks; a wall between them halves the range. A ping (its own
 * clicks, thrown out and listened for) finds anything within {@link #PING_RANGE} regardless.
 */
public final class Sonar {
    /** A ping finds a player this far away whatever they are doing. */
    public static final double PING_RANGE = 48.0;
    public static final double SPRINT = 32.0;
    public static final double WALK = 20.0;
    public static final double SNEAK = 6.0;
    public static final double BREATHING = 6.0;

    private Sonar() {}

    /**
     * @param sprinting   the player is sprinting
     * @param crouching   the player is sneaking
     * @param moving      the player is moving horizontally
     * @param airborne    the player is off the ground (jumping, falling)
     * @param lineOfSight nothing solid between hunter and player
     */
    public static double hearingRange(boolean sprinting, boolean crouching, boolean moving, boolean airborne, boolean lineOfSight) {
        double r;
        if (crouching && !airborne) r = moving ? SNEAK : 0.0;
        else if (!moving && !airborne) r = BREATHING;
        else if (sprinting || airborne) r = SPRINT;
        else r = WALK;
        return lineOfSight ? r : r * 0.5;
    }
}
