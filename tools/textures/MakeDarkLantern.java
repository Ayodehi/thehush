import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * The dark soul lantern: the vanilla soul lantern with its fire gone out. Reads the vanilla block texture
 * (16x48, three animation frames; the first is used) and the vanilla item texture (16x16), keeps the iron
 * frame a shade darker, and turns every lit pixel of the glass into cold grey-blue glass with soot in it.
 * Arguments: vanilla block soul_lantern.png, vanilla item soul_lantern.png, output directory (the
 * assets/thehush/textures folder; writes block/dark_soul_lantern.png and item/dark_soul_lantern.png).
 */
public class MakeDarkLantern {
    static final Random RND = new Random(3);

    static int rgb(int r, int g, int b) { return 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** True for the glowing glass: bright, and bluer than it is red. */
    static boolean lit(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return (r + g + b) / 3 > 95 && b > r + 10;
    }

    static int dark(BufferedImage in, int x, int y) {
        int p = in.getRGB(x, y);
        if ((p >>> 24) == 0) return p;
        if (lit(p)) {
            int n = RND.nextInt(10) - 5;
            // A hint of the old glow stays in the very middle so the shape reads as glass, not iron.
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            boolean core = (r + g + b) / 3 > 200;
            return core ? rgb(46 + n, 54 + n, 70 + n) : rgb(30 + n, 34 + n, 44 + n);
        }
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return rgb(r * 3 / 4, g * 3 / 4, b * 3 / 4 + 2);
    }

    static BufferedImage convert(BufferedImage in, int h) {
        BufferedImage out = new BufferedImage(16, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < 16; x++) out.setRGB(x, y, dark(in, x, y));
        return out;
    }

    public static void main(String[] a) throws Exception {
        BufferedImage block = ImageIO.read(new File(a[0]));
        BufferedImage item = ImageIO.read(new File(a[1]));
        File dir = new File(a[2]);
        ImageIO.write(convert(block, 16), "png", new File(dir, "block/dark_soul_lantern.png"));
        ImageIO.write(convert(item, 16), "png", new File(dir, "item/dark_soul_lantern.png"));
        System.out.println("wrote dark_soul_lantern block and item textures to " + dir);
    }
}
