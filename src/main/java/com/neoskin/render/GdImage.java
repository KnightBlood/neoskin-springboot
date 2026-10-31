package com.neoskin.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/**
 * GD 风格画布（对应 {@code Blessing\Renderer\ImageUtil} 与 GD 操作的最小子集）。
 *
 * <p>色彩约定：内部用 Java ARGB（0 不透明，255 全透明，散在高位）；
 * 「GD int」沿用源码语义 {@code (gdAlpha<<24)|(r<<16)|(g<<8)|b}
 * （gdAlpha 0=不透明，127=透明），仅在渲染判定与输出之间换算。</p>
 */
public final class GdImage {

    private final BufferedImage image;

    private GdImage(BufferedImage image) {
        this.image = image;
    }

    /** {@code ImageUtil::createEmptyCanvas}：全透明画布（GD 的 alpha=127 白底）。 */
    public static GdImage createEmptyCanvas(int width, int height) {
        BufferedImage img = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        // GD 的 fill(white, alpha=127) → Java 里是 0x00FFFFFF（白，全透明）
        int white = 0x00FFFFFF;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, white);
            }
        }

        return new GdImage(img);
    }

    /** {@code ImageManager->make()}：字节数组 → GD 画布（并作 {@code convertToTrueColor}）。失败得 null。 */
    public static GdImage make(byte[] data) {
        if (data == null) {
            return null;
        }

        try {
            BufferedImage input = ImageIO.read(new ByteArrayInputStream(data));
            if (input == null) {
                return null;
            }

            BufferedImage argb = new BufferedImage(input.getWidth(), input.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = argb.createGraphics();
            try {
                g.drawImage(input, 0, 0, null);
            } finally {
                g.dispose();
            }

            return new GdImage(argb);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** PHP {@code imagesetpixel} 等价的直写。 */
    public void pixelSet(int x, int y, int javaArgb) {
        image.setRGB(x, y, javaArgb);
    }

    /** Java 像素（ARGB：opacity 语义）。 */
    public int pixelAt(int x, int y) {
        return image.getRGB(safeX(x), safeY(y));
    }

    /** {@code imagecolorat}：GD int（alpha 0 不透明 / 127 透明）。 */
    public int colorAtGd(int x, int y) {
        int java = image.getRGB(safeX(x), safeY(y));
        int gdAlpha = 127 - Math.round((java >>> 24) * 127 / 255f);
        return (gdAlpha << 24) | (java & 0x00FFFFFF);
    }

    private int safeX(int x) {
        return Math.min(Math.max(0, x), image.getWidth() - 1);
    }

    private int safeY(int y) {
        return Math.min(Math.max(0, y), image.getHeight() - 1);
    }

    /** {@code imagesx}。 */
    public int width() {
        return image.getWidth();
    }

    /** {@code imagesy}。 */
    public int height() {
        return image.getHeight();
    }

    public BufferedImage buffered() {
        return image;
    }

    /** {@code imagecopyresampled}（双线性）：把 source 缩放到本画布。 */
    public void resampleFrom(GdImage source, int dstX, int dstY, int dstW, int dstH) {
        BufferedImage src = source.image;
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, dstX, dstY, dstW, dstH, null);
        } finally {
            g.dispose();
        }
    }

    /** {@code imagecopyresampled} 带源区域（CapeRenderer 用）。 */
    public void resampleRegion(GdImage source, int dstX, int dstY, int dstW, int dstH,
            int srcX, int srcY, int srcW, int srcH) {
        BufferedImage src = source.image;
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, dstX, dstY, dstX + dstW, dstY + dstH,
                    srcX, srcY, srcX + srcW, srcY + srcH, null);
        } finally {
            g.dispose();
        }
    }

    /** {@code imagecopy}（整块直拷）。 */
    public void copyFrom(GdImage source, int dstX, int dstY) {
        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(source.image, dstX, dstY, null);
        } finally {
            g.dispose();
        }
    }

    /** {@code imagefilledrectangle}（GD int 颜色）。 */
    public void filledRectangle(int x1, int y1, int x2, int y2, int gdColor) {
        double alpha = gdAlphaToJava(gdColor);
        java.awt.Color c = new java.awt.Color((gdColor >> 16) & 0xFF,
                (gdColor >> 8) & 0xFF, gdColor & 0xFF, (int) alpha);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(c);
            g.fillRect(Math.min(x1, x2), Math.min(y1, y2),
                    Math.abs(x2 - x1) + 1, Math.abs(y2 - y1) + 1);
        } finally {
            g.dispose();
        }
    }

    /** {@code imagefilledpolygon}（GD int 颜色；GD 无抗锯齿）。 */
    public void filledPolygon(double[] points, int gdColor) {
        int depth = points.length / 2;
        double alpha = gdAlphaToJava(gdColor);

        if (alpha <= 0) {
            return;
        }

        int[] xs = new int[depth];
        int[] ys = new int[depth];

        for (int i = 0; i < depth; i++) {
            xs[i] = (int) Math.round(points[2 * i]);
            ys[i] = (int) Math.round(points[2 * i + 1]);
        }

        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(new java.awt.Color((gdColor >> 16) & 0xFF, (gdColor >> 8) & 0xFF,
                    gdColor & 0xFF, (int) alpha));
            g.fillPolygon(xs, ys, depth);
        } finally {
            g.dispose();
        }
    }

    /** GD alpha → Java alpha（0..255，255=不透明）。 */
    public static double gdAlphaToJava(int gdColor) {
        int gdAlpha = (gdColor >>> 24) & 0x7F;
        return (127 - gdAlpha) * 255.0 / 127;
    }

    /** 等比/双维重新采样成新画布（GD 的 copyresampled 语义）。 */
    public GdImage scaled(double factorX, double factorY) {
        int w = Math.max(1, (int) (image.getWidth() * factorX));
        int h = Math.max(1, (int) (image.getHeight() * factorY));

        GdImage out = createEmptyCanvas(w, h);
        out.resampleFrom(this, 0, 0, w, h);

        return out;
    }

    /** {@code resize($w, $h)}。 */
    public GdImage resize(int w, int h) {
        GdImage out = createEmptyCanvas(w, h);
        out.resampleFrom(this, 0, 0, w, h);

        return out;
    }

    /** PNG 编码（{@code ->response('png', 100)} 的内容体）。 */
    public byte[] toPng() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);

            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
