package com.ayodehi.thehush.entity;

/** An entity rendered with the villager model but its own texture, and possibly frozen mid-step. */
public interface Skinned {
    /** Texture id, or empty for the vanilla villager look. */
    String skin();

    /** True while the walk animation should hold still (a watched Pilgrim). */
    default boolean animationFrozen() {
        return false;
    }
}
