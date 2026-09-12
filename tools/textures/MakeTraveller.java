import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Derives the Traveller's skin from the vanilla villager texture: pale grey skin, faintly glowing eyes,
 * a deep indigo robe with the original shading preserved, and a hood painted into the model's hat box.
 */
public class MakeTraveller {
    static int lum(int p) { return (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3; }
    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    public static void main(String[] a) throws Exception {
        BufferedImage in = ImageIO.read(new File(a[0]));
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int p = in.getRGB(x, y);
                if ((p >>> 24) == 0) continue;
                int l = lum(p);
                boolean skin = y < 18 && x < 32;          // head box faces + nose
                int c;
                if (skin) {
                    // pale, slightly cold grey; keep the original shading
                    c = rgb((int) (l * 0.75) + 62, (int) (l * 0.78) + 64, (int) (l * 0.78) + 70);
                } else {
                    // deep indigo cloth: dark, blue-violet, shading from the original luminance
                    c = rgb((int) (l * 0.20) + 14, (int) (l * 0.17) + 12, (int) (l * 0.36) + 30);
                }
                out.setRGB(x, y, c);
            }
        }
        // Eyes on the face front (x 8..15, y 8..17): whites at (8,14),(13,14); pupils at (9,14),(12,14).
        out.setRGB(8, 14, rgb(214, 214, 236));
        out.setRGB(13, 14, rgb(214, 214, 236));
        out.setRGB(9, 14, rgb(178, 150, 255));
        out.setRGB(12, 14, rgb(178, 150, 255));
        // Brow line stays dark; mouth (row 16, x 9..12) a little darker so it reads as a thin line.
        for (int x = 9; x <= 12; x++) out.setRGB(x, 16, rgb(58, 54, 66));

        // Hood in the hat box (texOffs 32,0; 8w x 10h x 8d): top (40..47,0..7), right (32..39,8..17),
        // front (40..47,8..17) brow only, left (48..55,8..17), back (56..63,8..17). Bottom stays open.
        int[] hood = {rgb(24, 20, 40), rgb(29, 24, 48), rgb(34, 28, 56), rgb(40, 33, 64)};
        java.util.Random rnd = new java.util.Random(7);
        paintFace(out, 40, 0, 8, 8, hood, rnd, false);    // top
        paintFace(out, 32, 8, 8, 10, hood, rnd, true);    // right side
        paintFace(out, 48, 8, 8, 10, hood, rnd, true);    // left side
        paintFace(out, 56, 8, 8, 10, hood, rnd, true);    // back
        for (int y = 8; y <= 10; y++) {                    // brow: hood hangs over the forehead
            for (int x = 40; x < 48; x++) out.setRGB(x, y, y == 10 ? rgb(18, 15, 30) : hood[1 + (x + y) % 2]);
        }
        // A pale, worn trim along the bottom hem of the robe front/back (jacket region rows near 63)
        for (int x = 0; x < 28; x++) {
            if ((out.getRGB(x, 63) >>> 24) != 0) out.setRGB(x, 63, rgb(70, 62, 92));
        }
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println("wrote " + a[1]);
    }

    static void paintFace(BufferedImage img, int x0, int y0, int w, int h, int[] pal, java.util.Random rnd, boolean fold) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int shade = 1 + (rnd.nextInt(10) < 7 ? 0 : (rnd.nextBoolean() ? 1 : -1));
                if (fold && (x == 1 || x == 5) && y > 1) shade = 0;          // vertical folds of cloth
                if (y == 0) shade = Math.min(3, shade + 1);                  // lit upper edge
                if (y == h - 1) shade = 0;                                   // dark hem
                img.setRGB(x0 + x, y0 + y, pal[Math.max(0, Math.min(3, shade))]);
            }
        }
    }
}
