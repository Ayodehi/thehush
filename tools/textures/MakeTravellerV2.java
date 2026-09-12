import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * The Traveller's texture set for TravellerModel (128x64), after the quad-panel illustration: indigo robe
 * with gold trim, a hood framing the face, a cowl at the neck, wide sleeves with gold cuffs, boots, and
 * small violet lights on the sleeves, chest, and hem. Writes four files: the base skin, the eyes-dark
 * variant, and a glow map for each (eyes and lights; lights only).
 *
 * Box UV layout (u, v, w, h, d):
 *   head 0,0 8x10x8      hood 32,0 8x10x8      nose 64,0 2x4x2     cowl 72,0 8x3x8
 *   body 0,18 8x12x6     robe 28,18 8x20x6
 *   right arm 56,18 4x12x4   left arm 72,18 (mirrored)   sleeves 88,18 and 104,18 4x9x4
 *   right leg 56,34 4x12x4   left leg 72,34 (mirrored)
 * Face order within a box region: right(u), front(u+d), left(u+d+w), back(u+2d+w) on row v+d;
 * top(u+d) and bottom(u+d+w) on row v.
 */
public class MakeTravellerV2 {
    static final Random RND = new Random(19);
    static BufferedImage base = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
    static BufferedImage glow = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);

    // palette
    static final int P1 = rgb(0x3b, 0x2a, 0x5e), P2 = rgb(0x30, 0x21, 0x4c), P3 = rgb(0x47, 0x35, 0x70), P4 = rgb(0x26, 0x1a, 0x3e);
    static final int G1 = rgb(0xc9, 0xa7, 0x5e), G2 = rgb(0x9d, 0x7d, 0x3d), G3 = rgb(0xe3, 0xc6, 0x7e);
    static final int V1 = rgb(0xc5, 0xa8, 0xff), V2 = rgb(0xf0, 0xe8, 0xff), V3 = rgb(0x6f, 0x4f, 0xb5);
    static final int S1 = rgb(0xb9, 0x89, 0x5f), S2 = rgb(0x9f, 0x73, 0x4d), S3 = rgb(0xc9, 0x9a, 0x70);
    static final int B1 = rgb(0x2b, 0x2a, 0x30), B2 = rgb(0x3c, 0x3b, 0x42), B3 = rgb(0x1c, 0x1b, 0x21);
    static final int H1 = rgb(0x4a, 0x33, 0x24), H2 = rgb(0x7d, 0x5c, 0x42), EYE_DARK = rgb(0x10, 0x0e, 0x18);
    static final int E1 = rgb(0xb2, 0x8c, 0xff), E2 = rgb(0xcd, 0xb4, 0xff);

    static int rgb(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }

    interface Face { int px(String face, int x, int y, int w, int h); }

    static void box(int u, int v, int w, int h, int d, Face f) {
        fill(u + d, v, w, d, "top", f);
        fill(u + d + w, v, w, d, "bottom", f);
        fill(u, v + d, d, h, "right", f);
        fill(u + d, v + d, w, h, "front", f);
        fill(u + d + w, v + d, d, h, "left", f);
        fill(u + 2 * d + w, v + d, w, h, "back", f);
    }

    static void fill(int x0, int y0, int w, int h, String face, Face f) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = f.px(face, x, y, w, h);
            if (c != 0) base.setRGB(x0 + x, y0 + y, c);
        }
    }

    static void lit(int x, int y, int c) { glow.setRGB(x, y, c); base.setRGB(x, y, c); }

    /** Indigo cloth with a faint weave and darker folds; a little darker on the sides and back. */
    static int cloth(String face, int x, int y) {
        int c = ((x + y) % 2 == 0) ? P1 : P2;
        if (RND.nextInt(9) == 0) c = P3;
        if (RND.nextInt(11) == 0) c = P4;
        if (face.equals("back") || face.equals("bottom")) c = darker(c);
        return c;
    }

    static int darker(int c) {
        int r = ((c >> 16) & 0xff) * 85 / 100, g = ((c >> 8) & 0xff) * 85 / 100, b = (c & 0xff) * 85 / 100;
        return rgb(r, g, b);
    }

    static int skin(String face, int x, int y) {
        int c = RND.nextInt(7) == 0 ? S2 : S1;
        if (face.equals("top") || face.equals("bottom") || face.equals("back")) c = darker(c);
        return c;
    }

    static int gold(int x, int y) { return ((x * 3 + y) % 5 == 0) ? G2 : (RND.nextInt(6) == 0 ? G3 : G1); }

    public static void main(String[] a) throws Exception {
        // ---- head: villager face, brows, violet eyes, mouth; hair on top and back ----
        // The hood is painted straight onto the head (the overlay cube adds depth when it draws): cloth on
        // the top, sides and back, a gold edge where it meets the face, and the face itself framed in gold.
        box(0, 0, 8, 10, 8, (face, x, y, w, h) -> {
            if (face.equals("front")) {
                if (x == 0 || x == w - 1 || y == 0) return gold(x, y);                 // the hood's edge round the face
                if (y == 3 && (x == 1 || x == 2 || x == 5 || x == 6)) return H2;      // brows, a lighter brown
                if (y == 4 && (x == 1 || x == 2 || x == 5 || x == 6)) return 0;       // eyes drawn below
                if (y == 8 && (x == 3 || x == 4)) return S2;                          // mouth
                return skin(face, x, y);
            }
            if (face.equals("bottom")) return S2;
            if (face.equals("right") && x == w - 1) return gold(x, y);
            if (face.equals("left") && x == 0) return gold(x, y);
            if (face.equals("top") && y == h - 1) return gold(x, y);
            return cloth(face, x, y);
        });
        // eyes: two pixels of one violet each, the inner a shade brighter, lit from within (no white, no dark ring)
        int fu = 8, fv = 8; // head front face origin
        lit(fu + 1, fv + 4, E1); lit(fu + 2, fv + 4, E2);
        lit(fu + 5, fv + 4, E2); lit(fu + 6, fv + 4, E1);

        // ---- nose ----
        box(64, 0, 2, 4, 2, (face, x, y, w, h) -> face.equals("bottom") ? S2 : (face.equals("front") && y == 3 ? S2 : S3));

        // ---- hood: open at the face, gold trim round the opening, cloth elsewhere ----
        box(32, 0, 8, 10, 8, (face, x, y, w, h) -> {
            if (face.equals("front")) {
                boolean edge = x == 0 || x == w - 1 || y == 0;
                return edge ? gold(x, y) : (y == h - 1 ? P2 : 0);
            }
            if (face.equals("bottom")) return 0;
            if (face.equals("right") && x == w - 1) return gold(x, y);
            if (face.equals("left") && x == 0) return gold(x, y);
            if (face.equals("top") && y == h - 1) return gold(x, y);
            return cloth(face, x, y);
        });

        // ---- cowl at the neck: cloth with a gold lower edge ----
        box(72, 0, 8, 3, 8, (face, x, y, w, h) -> {
            if (face.equals("top") || face.equals("bottom")) return face.equals("top") ? P2 : 0;
            if (y == h - 1) return gold(x, y);
            return cloth(face, x, y);
        });

        // ---- body (under the robe) ----
        box(0, 18, 8, 12, 6, (face, x, y, w, h) -> cloth(face, x, y));

        // ---- robe ----
        box(28, 18, 8, 20, 6, (face, x, y, w, h) -> {
            if (face.equals("top")) return 0;
            if (face.equals("bottom")) return P4;
            boolean hem = y == h - 1 || (y == h - 2 && (x % 2 == 0));
            if (hem) return gold(x, y);
            if (face.equals("front")) {
                // two gold lines down the centre panel, a clasp at the chest, a gem lower down
                if (x == 2 || x == 5) return gold(x, y);
                if (y >= 1 && y <= 3 && (x == 3 || x == 4)) return y == 2 ? G3 : G1;   // clasp
                if (y == 11 && (x == 3 || x == 4)) return 0;                          // gem, lit below
                if ((y == 10 || y == 12) && (x == 3 || x == 4)) return G2;
            }
            if (face.equals("back")) {
                // a diamond of gold with a light at its heart, and a seam below
                if ((y == 3 && (x == 3 || x == 4)) || (y == 7 && (x == 3 || x == 4))) return G1;
                if ((y == 4 || y == 6) && (x == 2 || x == 5)) return G1;
                if (y == 5 && (x == 1 || x == 6)) return G1;
                if (y == 5 && (x == 3 || x == 4)) return 0;
                if (y >= 9 && y <= 15 && (x == 3 || x == 4) && y % 2 == 1) return G2;
            }
            return cloth(face, x, y);
        });
        // robe lights: chest gem (front face origin 28+6=34, 18+6=24), back heart (34+8+6=48), hem sparks
        lit(34 + 3, 24 + 11, V1); lit(34 + 4, 24 + 11, V2);
        lit(48 + 3, 24 + 5, V1); lit(48 + 4, 24 + 5, V2);
        lit(34 + 0, 24 + 17, V1); lit(34 + 7, 24 + 17, V1);            // front hem corners
        lit(28 + 1, 24 + 17, V1); lit(28 + 6 + 8 + 4, 24 + 17, V1);    // side hem lights

        // ---- arms: cloth, hands showing under the sleeve ----
        Face arm = (face, x, y, w, h) -> {
            if (face.equals("top")) return P2;
            if (face.equals("bottom") || y >= h - 3) return skin(face, x, y);
            return cloth(face, x, y);
        };
        box(56, 18, 4, 12, 4, arm);
        box(72, 18, 4, 12, 4, arm);
        // ---- sleeves: cloth, gold cuff with a light ----
        Face sleeve = (face, x, y, w, h) -> {
            if (face.equals("top")) return 0;
            if (face.equals("bottom")) return P4;
            if (y >= h - 2) return gold(x, y);
            if (y == 0 && face.equals("front")) return G2;
            return cloth(face, x, y);
        };
        box(88, 18, 4, 9, 4, sleeve);
        box(104, 18, 4, 9, 4, sleeve);
        lit(88 + 4 + 1, 18 + 4 + 5, V1); lit(104 + 4 + 2, 18 + 4 + 5, V1);   // front of each sleeve, above the cuff
        lit(88 + 4 + 4 + 1, 18 + 4 + 5, V1); lit(104 + 0 + 1, 18 + 4 + 5, V1); // outer sides

        // ---- legs: hidden cloth above, boots below ----
        Face leg = (face, x, y, w, h) -> {
            if (face.equals("top")) return P4;
            if (face.equals("bottom")) return B3;
            if (y >= h - 4) return y == h - 4 ? B2 : (RND.nextInt(5) == 0 ? B2 : B1);
            return cloth(face, x, y);
        };
        box(56, 34, 4, 12, 4, leg);
        box(72, 34, 4, 12, 4, leg);

        File dir = new File(a[0]);
        ImageIO.write(base, "png", new File(dir, "traveller.png"));
        ImageIO.write(glow, "png", new File(dir, "traveller_glow.png"));

        // eyes gone dark: black where the violet was, and no glow for them
        BufferedImage dark = copy(base), darkGlow = copy(glow);
        for (int x = fu + 1; x <= fu + 6; x++) {
            if (x == 3 + fu || x == 4 + fu) continue;
            dark.setRGB(x, fv + 4, EYE_DARK);
            darkGlow.setRGB(x, fv + 4, 0);
        }
        ImageIO.write(dark, "png", new File(dir, "traveller_dark.png"));
        ImageIO.write(darkGlow, "png", new File(dir, "traveller_glow_dark.png"));
        System.out.println("wrote 4 textures to " + dir);
    }

    static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) out.setRGB(x, y, src.getRGB(x, y));
        return out;
    }
}
