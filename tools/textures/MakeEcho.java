import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * The Echo's texture on the enderman's 64x32 layout: bone-pale ash skin with darker veins, a face with no
 * eyes at all, only a wide dark mouth of small teeth and two sonar pits, ribs on the body, dark joints on
 * the limbs. The hat layer stays empty.
 */
public class MakeEcho {
    static final Random RND = new Random(7);

    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** Pale skin with grain and the odd darker vein. */
    static int skin(int x, int y, int shade) {
        int base = 196 + shade;
        int grain = RND.nextInt(14) - 7;
        boolean vein = ((x * 7 + y * 13) % 17 == 0) && RND.nextInt(3) == 0;
        int v = vein ? -38 : 0;
        return rgb(base + grain + v, base + grain + v - 4, base + grain + v - 12);
    }

    static void box(BufferedImage img, int u, int v, int w, int h, int d, int shade) {
        // top, bottom
        fill(img, u + d, v, w, d, shade + 12);
        fill(img, u + d + w, v, w, d, shade - 30);
        // right, front, left, back
        fill(img, u, v + d, d, h, shade - 10);
        fill(img, u + d, v + d, w, h, shade);
        fill(img, u + d + w, v + d, d, h, shade - 10);
        fill(img, u + 2 * d + w, v + d, w, h, shade - 18);
    }

    static void fill(BufferedImage img, int x0, int y0, int w, int h, int shade) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) img.setRGB(x, y, skin(x, y, shade));
    }

    public static void main(String[] a) throws Exception {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        // head at (0,0): 8x8x8
        box(img, 0, 0, 8, 8, 8, 0);
        // body at (32,16): 8x12x4
        box(img, 32, 16, 8, 12, 4, -6);
        // limbs at (56,0): 2x30x2
        box(img, 56, 0, 2, 30, 2, -4);

        // Face (front face is x 8..15, y 8..15). No eyes. A wide mouth low on the face, teeth in it.
        int dark = rgb(28, 18, 20);
        int gum = rgb(70, 30, 34);
        int tooth = rgb(232, 226, 210);
        for (int x = 8; x <= 15; x++) {
            img.setRGB(x, 13, x == 8 || x == 15 ? gum : dark);
            img.setRGB(x, 14, x == 8 || x == 15 ? gum : dark);
        }
        for (int x = 9; x <= 14; x++) img.setRGB(x, 13, (x % 2 == 1) ? tooth : dark);
        for (int x = 10; x <= 13; x++) img.setRGB(x, 14, (x % 2 == 0) ? tooth : dark);
        // Sonar pits where eyes would be: two small dark dimples, and the skin around them smooth.
        img.setRGB(10, 10, rgb(120, 110, 100));
        img.setRGB(13, 10, rgb(120, 110, 100));
        // A faint seam down the middle of the face, like something that healed shut.
        for (int y = 8; y <= 12; y++) img.setRGB(11 + (y % 2), y, rgb(170, 160, 148));

        // Ribs on the body front (x 36..43, y 20..31): darker lines every other row on the upper half.
        for (int y = 21; y <= 27; y += 2) for (int x = 37; x <= 42; x++) if (x != 39 && x != 40) img.setRGB(x, y, rgb(150, 142, 130));
        // Spine on the back (x 48..55): a dark line of knuckles.
        for (int y = 20; y <= 31; y++) img.setRGB(51 + (y % 2 == 0 ? 0 : 1), y, rgb(120, 112, 102));

        // Limb joints: darker bands at the elbow/knee, and dark tips (claws / feet).
        for (int x = 56; x <= 63; x++) {
            for (int y = 14; y <= 15; y++) if ((img.getRGB(x, y) >>> 24) != 0) img.setRGB(x, y, rgb(140, 132, 120));
            for (int y = 29; y <= 31; y++) if ((img.getRGB(x, y) >>> 24) != 0) img.setRGB(x, y, rgb(60, 50, 48));
        }

        ImageIO.write(img, "png", new File(a[0]));
        System.out.println("wrote " + a[0]);
    }
}
