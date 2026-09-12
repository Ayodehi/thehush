import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Prints a PNG as a grid of hex colours (or '.' for transparent) so the layout can be read in a terminal. */
public class Dump {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int x0 = a.length > 1 ? Integer.parseInt(a[1]) : 0, y0 = a.length > 2 ? Integer.parseInt(a[2]) : 0;
        int w = a.length > 3 ? Integer.parseInt(a[3]) : img.getWidth(), h = a.length > 4 ? Integer.parseInt(a[4]) : img.getHeight();
        System.out.println(img.getWidth() + "x" + img.getHeight());
        for (int y = y0; y < y0 + h; y++) {
            StringBuilder sb = new StringBuilder(String.format("%3d ", y));
            for (int x = x0; x < x0 + w; x++) {
                int p = img.getRGB(x, y);
                int alpha = (p >>> 24);
                if (alpha == 0) sb.append(" . ");
                else sb.append(String.format("%02x%1x", (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3, hue(p)));
            }
            System.out.println(sb);
        }
    }
    static int hue(int p) { // 0 grey,1 red,2 yellow,3 green,4 cyan,5 blue,6 magenta
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 12) return 0;
        if (max == r) return g >= b ? (g - b > (r - g) ? 2 : 1) : 6;
        if (max == g) return b > r ? 4 : 3;
        return r > g ? 6 : 5;
    }
}
