import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Two textures from the vanilla villager: the Pilgrim (grey threadbare cloth, hood down over a blank
 * face, no eyes) and the Traveller with his eyes gone dark (same as traveller.png, eye pixels blacked).
 */
public class MakePilgrim {
    static int lum(int p) { return (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3; }
    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    public static void main(String[] a) throws Exception {
        BufferedImage in = ImageIO.read(new File(a[0]));
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(11);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int p = in.getRGB(x, y);
                if ((p >>> 24) == 0) continue;
                int l = lum(p);
                boolean skin = y < 18 && x < 32;
                int c;
                if (skin) {
                    // a face that is only shadow: very dark grey, nearly flat
                    c = rgb((int) (l * 0.12) + 18, (int) (l * 0.12) + 18, (int) (l * 0.12) + 20);
                } else {
                    // grey cloth worn to threads: mid grey with the original shading, random threadbare flecks
                    int base = (int) (l * 0.45) + 60;
                    int fleck = rnd.nextInt(12) == 0 ? -25 : 0;
                    c = rgb(base + fleck, base + fleck, base + fleck + 4);
                }
                out.setRGB(x, y, c);
            }
        }
        // No eyes: the face front stays shadow. Nose stays but darker (already covered by skin rule).
        // Hood in the hat box, grey, hanging low: paint all faces including the front down to row 15.
        int[] hood = {rgb(70, 70, 74), rgb(84, 84, 88), rgb(98, 98, 102), rgb(112, 112, 116)};
        paintFace(out, 40, 0, 8, 8, hood, rnd, false);    // top
        paintFace(out, 32, 8, 8, 10, hood, rnd, true);    // right
        paintFace(out, 48, 8, 8, 10, hood, rnd, true);    // left
        paintFace(out, 56, 8, 8, 10, hood, rnd, true);    // back
        for (int y = 8; y <= 15; y++) {                    // front: the hood hangs over the whole face
            for (int x = 40; x < 48; x++) {
                int shade = y >= 14 ? 0 : 1 + ((x + y) % 3 == 0 ? 1 : 0);
                if (y == 15 && (x == 42 || x == 45)) continue; // ragged hem: a couple of missing pixels
                out.setRGB(x, y, hood[shade]);
            }
        }
        // Frayed hem on the robe bottom row.
        for (int x = 0; x < 28; x++) {
            if ((out.getRGB(x, 63) >>> 24) != 0 && rnd.nextInt(3) == 0) out.setRGB(x, 63, 0);
        }
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println("wrote " + a[1]);

        // Traveller, eyes dark: take the existing traveller texture and black out the eye pixels.
        BufferedImage trav = ImageIO.read(new File(a[2]));
        for (int x = 8; x <= 13; x++) {
            if (x == 10 || x == 11) continue;
            trav.setRGB(x, 14, rgb(16, 12, 24));
        }
        ImageIO.write(trav, "png", new File(a[3]));
        System.out.println("wrote " + a[3]);
    }

    static void paintFace(BufferedImage img, int x0, int y0, int w, int h, int[] pal, java.util.Random rnd, boolean fold) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int shade = 1 + (rnd.nextInt(10) < 7 ? 0 : (rnd.nextBoolean() ? 1 : -1));
                if (fold && (x == 1 || x == 5) && y > 1) shade = 0;
                if (y == 0) shade = Math.min(3, shade + 1);
                if (y == h - 1) shade = 0;
                img.setRGB(x0 + x, y0 + y, pal[Math.max(0, Math.min(3, shade))]);
            }
        }
    }
}
