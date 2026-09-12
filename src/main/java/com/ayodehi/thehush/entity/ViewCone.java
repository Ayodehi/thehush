package com.ayodehi.thehush.entity;

/** The "is it being looked at" test, kept free of Minecraft types so it can be unit tested. */
public final class ViewCone {
    /** Generous: about 70 degrees either side of centre, so peripheral vision counts. */
    public static final double DEFAULT_THRESHOLD = 0.35;

    private ViewCone() {}

    /** True when the target direction lies inside the viewer's cone. Vectors need not be normalised. */
    public static boolean contains(double lookX, double lookY, double lookZ, double toX, double toY, double toZ, double threshold) {
        double ll = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double tl = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (ll < 1e-9 || tl < 1e-9) return true; // standing inside it: certainly seen
        double dot = (lookX * toX + lookY * toY + lookZ * toZ) / (ll * tl);
        return dot >= threshold;
    }
}
