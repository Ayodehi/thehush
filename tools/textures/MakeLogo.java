import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The mod's logo for the mods list: the Traveller's hooded face, taken straight from his skin (head front
 * face under the hood overlay, eyes from the glow map), scaled up crisp on a dark indigo ground with a
 * faint violet glow around the eyes.
 *
 * Arguments: traveller.png, traveller_glow.png, output png (src/main/resources/thehush_logo.png).
 */
public final class MakeLogo {
    public static void main(String[] a) throws Exception {
        BufferedImage skin = ImageIO.read(new File(a[0]));
        BufferedImage glow = ImageIO.read(new File(a[1]));
        int size = 128, scale = 10;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        // ground: indigo, darker toward the edges
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double dx = (x - 63.5) / 64.0, dy = (y - 63.5) / 64.0;
            double d = Math.min(1.0, Math.sqrt(dx * dx + dy * dy));
            int r = (int) (0x1a - 0x0c * d), g = (int) (0x14 - 0x0a * d), b = (int) (0x2a - 0x14 * d);
            out.setRGB(x, y, 0xff000000 | (r << 16) | (g << 8) | b);
        }
        // the face: 8x10 head front at (8,8), hood front at (40,8), scaled by 10, centred
        int ox = (size - 8 * scale) / 2, oy = (size - 10 * scale) / 2 + 2;
        for (int v = 0; v < 10; v++) for (int u = 0; u < 8; u++) {
            int c = skin.getRGB(8 + u, 8 + v);
            int h = skin.getRGB(40 + u, 8 + v);
            if ((h >>> 24) > 0) c = h;
            if ((c >>> 24) == 0) continue;
            for (int y = 0; y < scale; y++) for (int x = 0; x < scale; x++) out.setRGB(ox + u * scale + x, oy + v * scale + y, c);
        }
        // eye glow: a soft violet halo behind each lit pixel of the glow map, then the lit pixels themselves
        for (int v = 0; v < 10; v++) for (int u = 0; u < 8; u++) {
            int c = glow.getRGB(8 + u, 8 + v);
            if ((c >>> 24) == 0 || (c & 0xffffff) == 0) continue;
            int cx = ox + u * scale + scale / 2, cy = oy + v * scale + scale / 2;
            for (int y = -14; y <= 14; y++) for (int x = -14; x <= 14; x++) {
                int px = cx + x, py = cy + y;
                if (px < 0 || py < 0 || px >= size || py >= size) continue;
                double d = Math.sqrt(x * x + y * y) / 14.0;
                if (d >= 1.0) continue;
                double k = 0.35 * (1 - d) * (1 - d);
                int p = out.getRGB(px, py);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                r = Math.min(255, (int) (r + (0xb8 - r) * k));
                g = Math.min(255, (int) (g + (0x88 - g) * k));
                b = Math.min(255, (int) (b + (0xff - b) * k));
                out.setRGB(px, py, 0xff000000 | (r << 16) | (g << 8) | b);
            }
        }
        for (int v = 0; v < 10; v++) for (int u = 0; u < 8; u++) {
            int c = glow.getRGB(8 + u, 8 + v);
            if ((c >>> 24) == 0 || (c & 0xffffff) == 0) continue;
            for (int y = 0; y < scale; y++) for (int x = 0; x < scale; x++) out.setRGB(ox + u * scale + x, oy + v * scale + y, 0xff000000 | (c & 0xffffff));
        }
        ImageIO.write(out, "png", new File(a[2]));
        System.out.println("wrote " + a[2]);
    }
}
