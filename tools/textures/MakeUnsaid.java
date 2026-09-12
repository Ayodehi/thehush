import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * The Unsaid: three ghost variants on the Traveller's 128x64 layout (see MakeTravellerV2 for the map).
 * Grey, translucent cloth with no gold; a hood over a face that is only shadow, with two pale lights for
 * eyes; hands the colour of ash; a ragged hem that fades out; no legs at all (they float). Also writes
 * one shared glow map for the eyes.
 */
public class MakeUnsaid {
    static Random rnd;
    static BufferedImage img;

    static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }

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
            if (c != 0) img.setRGB(x0 + x, y0 + y, c);
        }
    }

    /** Robe palettes: ash, faded violet, cold blue-grey. */
    static final int[][] ROBES = {
            {0x7e7a86, 0x69656f, 0x8f8b98},
            {0x6f6478, 0x5a5062, 0x817489},
            {0x66707c, 0x525a65, 0x78838f}
    };

    static int cloth(int[] pal, String face, int x, int y, int alpha) {
        int c = ((x + y) % 2 == 0) ? pal[0] : pal[1];
        if (rnd.nextInt(8) == 0) c = pal[2];
        if (face.equals("back") || face.equals("bottom")) c = darker(c);
        return argb(alpha, (c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff);
    }

    static int darker(int c) {
        return (((c >> 16) & 0xff) * 85 / 100 << 16) | (((c >> 8) & 0xff) * 85 / 100 << 8) | ((c & 0xff) * 85 / 100);
    }

    static int edge(int[] pal, int alpha) {
        int c = pal[2];
        return argb(alpha, Math.min(255, ((c >> 16) & 0xff) + 30), Math.min(255, ((c >> 8) & 0xff) + 30), Math.min(255, (c & 0xff) + 30));
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        BufferedImage glow = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        int eye = argb(255, 0xd8, 0xd6, 0xec);
        glow.setRGB(8 + 1, 8 + 4, eye); glow.setRGB(8 + 2, 8 + 4, eye);
        glow.setRGB(8 + 5, 8 + 4, eye); glow.setRGB(8 + 6, 8 + 4, eye);
        ImageIO.write(glow, "png", new File(dir, "unsaid_glow.png"));

        for (int variant = 0; variant < ROBES.length; variant++) {
            rnd = new Random(31 + variant);
            img = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
            int[] pal = ROBES[variant];
            int A = 200, HAND = argb(210, 0x9a, 0x96, 0xa0), SHADOW = argb(230, 0x1e, 0x1b, 0x26);
            final int hoodDepth = variant == 1 ? 2 : 1; // how far the hood hangs over the brow
            // head: the hood is the head; the face is shadow with two pale eyes
            box(0, 0, 8, 10, 8, (face, x, y, w, h) -> {
                if (face.equals("front")) {
                    if (x == 0 || x == w - 1 || y < hoodDepth) return edge(pal, A);
                    if (y == 4 && (x == 1 || x == 2 || x == 5 || x == 6)) return eye;
                    return SHADOW;
                }
                if (face.equals("bottom")) return SHADOW;
                if (face.equals("right") && x == w - 1) return edge(pal, A);
                if (face.equals("left") && x == 0) return edge(pal, A);
                return cloth(pal, face, x, y, A);
            });
            // hood overlay: same as the head, open at the face
            box(32, 0, 8, 10, 8, (face, x, y, w, h) -> {
                if (face.equals("front")) return (x == 0 || x == w - 1 || y == 0) ? edge(pal, A) : 0;
                if (face.equals("bottom")) return 0;
                return cloth(pal, face, x, y, A);
            });
            // nose: shadow (the face is only shadow)
            box(64, 0, 2, 4, 2, (face, x, y, w, h) -> SHADOW);
            // cowl
            box(72, 0, 8, 3, 8, (face, x, y, w, h) -> face.equals("top") || face.equals("bottom") ? 0
                    : (y == h - 1 ? edge(pal, A) : cloth(pal, face, x, y, A)));
            // body under the robe
            box(0, 18, 8, 12, 6, (face, x, y, w, h) -> cloth(pal, face, x, y, A));
            // robe: no gold; a paler seam down the front on variant 2; the hem frays and fades out
            final int seam = variant;
            box(28, 18, 8, 20, 6, (face, x, y, w, h) -> {
                if (face.equals("top") || face.equals("bottom")) return 0;
                int rows = h - y; // rows from the hem
                if (rows <= 3) {
                    if (rnd.nextInt(rows + 1) == 0) return 0; // frayed
                    return cloth(pal, face, x, y, 60 + rows * 40);
                }
                if (face.equals("front") && seam == 2 && (x == 3 || x == 4) && y % 3 != 2) return edge(pal, A);
                if (face.equals("front") && seam == 0 && y == 2 && (x == 3 || x == 4)) return edge(pal, A); // a clasp that was gold
                if (face.equals("back") && seam == 1 && y >= 3 && y <= 7 && (x == 3 || x == 4) && (y == 3 || y == 7 || x == 3 + (y % 2))) return edge(pal, A);
                return cloth(pal, face, x, y, A);
            });
            // arms: cloth, ash-grey hands
            Face arm = (face, x, y, w, h) -> face.equals("top") ? cloth(pal, face, x, y, A)
                    : (face.equals("bottom") || y >= h - 3) ? HAND : cloth(pal, face, x, y, A);
            box(56, 18, 4, 12, 4, arm);
            box(72, 18, 4, 12, 4, arm);
            // sleeves: cloth with a pale frayed cuff
            Face sleeve = (face, x, y, w, h) -> {
                if (face.equals("top") || face.equals("bottom")) return 0;
                if (y == h - 1) return rnd.nextInt(3) == 0 ? 0 : edge(pal, 160);
                return cloth(pal, face, x, y, A);
            };
            box(88, 18, 4, 9, 4, sleeve);
            box(104, 18, 4, 9, 4, sleeve);
            // legs: none; the regions stay transparent
            ImageIO.write(img, "png", new File(dir, "unsaid_" + variant + ".png"));
        }
        System.out.println("wrote unsaid_0..2 and unsaid_glow to " + dir);
    }
}
