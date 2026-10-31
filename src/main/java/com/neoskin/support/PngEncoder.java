package com.neoskin.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 与 Imagick（{@code png:compression-filter=0/level=9/strategy=0} +
 * {@code stripImage()}）字节级对齐的 PNG 编码器。
 *
 * <p>对齐依据（本项目用 PHP 8.5 + Imagick 生成的参照文件验证）：</p>
 * <ol>
 *   <li>只写 IHDR / IDAT / IEND（{@code exclude-chunk all}）；</li>
 *   <li>RGBA（颜色类型 6）或 RGB（颜色类型 2），8 位；</li>
 *   <li>行过滤使用 libpng 的启发式：None 为基线，Sub/Up/Avg/Paeth 取
 *       {@code sum(abs(signed byte))} 最小者；第一行只测 None/Sub
 *       （libpng 在无上一行时不测 Up/Avg/Paeth）；</li>
 *   <li>zlib：deflate level 9、默认策略 —— 与 Imagick 的 IDAT 字节一致
 *       （已用相同扫描线重压验证）；</li>
 *   <li>IDAT 按 8192 字节分块（libpng 默认缓冲）。</li>
 * </ol>
 */
public final class PngEncoder {

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    /** libpng 的压缩缓冲大小（同时也是 IDAT 分块上限）。 */
    private static final int IDAT_CHUNK = 8192;

    private PngEncoder() {
    }

    public static byte[] encode(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean alpha = image.getColorModel().hasAlpha();
        int bpp = alpha ? 4 : 3;
        int colorType = alpha ? 6 : 2;

        byte[] raw = scanlines(image, width, height, bpp, alpha);
        byte[] compressed = deflate(raw);

        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 128);
        out.write(SIGNATURE);

        byte[] ihdr = new byte[13];
        writeInt(ihdr, 0, width);
        writeInt(ihdr, 4, height);
        ihdr[8] = 8;                 // bit depth
        ihdr[9] = (byte) colorType;  // 2 = RGB, 6 = RGBA
        ihdr[10] = 0;                // compression
        ihdr[11] = 0;                // filter method
        ihdr[12] = 0;                // interlace
        chunk(out, "IHDR", ihdr);

        for (int offset = 0; offset < compressed.length; offset += IDAT_CHUNK) {
            int length = Math.min(IDAT_CHUNK, compressed.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(compressed, offset, part, 0, length);
            chunk(out, "IDAT", part);
        }
        chunk(out, "IEND", new byte[0]);

        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 扫描线

    private static byte[] scanlines(BufferedImage image, int width, int height, int bpp,
            boolean alpha) {
        int stride = width * bpp;
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        byte[] out = new byte[height * (stride + 1)];

        int pos = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = pixels[y * width + x];
                int base = x * bpp;
                current[base] = (byte) ((argb >> 16) & 0xFF);
                current[base + 1] = (byte) ((argb >> 8) & 0xFF);
                current[base + 2] = (byte) (argb & 0xFF);
                if (alpha) {
                    current[base + 3] = (byte) ((argb >>> 24) & 0xFF);
                }
            }

            int filter = pickFilter(current, previous, y > 0, bpp, stride);
            out[pos++] = (byte) filter;
            filterRow(current, previous, y > 0, bpp, stride, filter, out, pos);
            pos += stride;

            byte[] swap = previous;
            previous = current;
            current = swap;
        }

        return out;
    }

    /** libpng {@code png_write_find_filter} 的启发式。 */
    private static int pickFilter(byte[] row, byte[] prev, boolean hasPrev, int bpp,
            int stride) {
        int[] sums = new int[5];
        sums[0] = sum(row, 0, stride);
        for (int i = 0; i < stride; i++) {
            int cur = row[i] & 0xFF;
            int left = i >= bpp ? row[i - bpp] & 0xFF : 0;
            int up = hasPrev ? prev[i] & 0xFF : 0;
            int upperLeft = hasPrev && i >= bpp ? prev[i - bpp] & 0xFF : 0;

            sums[1] += absSigned(cur - left);
            if (hasPrev) {
                sums[2] += absSigned(cur - up);
                sums[3] += absSigned(cur - ((left + up) >>> 1));
                sums[4] += absSigned(cur - paeth(left, up, upperLeft));
            }
        }

        int best = 0;
        int min = sums[0];
        for (int f = 1; f < 5; f++) {
            if (f >= 2 && !hasPrev) {
                continue;
            }
            if (sums[f] < min) {
                min = sums[f];
                best = f;
            }
        }

        return best;
    }

    private static void filterRow(byte[] row, byte[] prev, boolean hasPrev, int bpp,
            int stride, int filter, byte[] out, int offset) {
        for (int i = 0; i < stride; i++) {
            int cur = row[i] & 0xFF;
            int left = i >= bpp ? row[i - bpp] & 0xFF : 0;
            int up = hasPrev ? prev[i] & 0xFF : 0;
            int upperLeft = hasPrev && i >= bpp ? prev[i - bpp] & 0xFF : 0;
            int value = switch (filter) {
                case 0 -> cur;
                case 1 -> cur - left;
                case 2 -> cur - up;
                case 3 -> cur - ((left + up) >>> 1);
                case 4 -> cur - paeth(left, up, upperLeft);
                default -> throw new IllegalStateException("filter " + filter);
            };
            out[offset + i] = (byte) value;
        }
    }

    private static int sum(byte[] row, int offset, int length) {
        int total = 0;
        for (int i = 0; i < length; i++) {
            total += absSigned(row[offset + i] & 0xFF);
        }

        return total;
    }

    /** libpng 的 {@code abs((signed char) v)}。 */
    private static int absSigned(int value) {
        int v = (byte) value;

        return Math.abs(v);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }

        return pb <= pc ? b : c;
    }

    // ------------------------------------------------------------------ 压缩 / 块

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(9);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        byte[] compressed = out.toByteArray();

        // Java 固定 windowBits=15（头 78 DA）；Imagick/libpng 按图像大小缩小窗口。
        // 压缩流本身一致，只需改写 zlib 头（CMF/FLG）。
        int windowBits = windowBits(raw.length);
        if (windowBits < 15 && compressed.length >= 2) {
            int cmf = ((windowBits - 8) << 4) | 8;
            int flg = 3 << 6;
            int rem = ((cmf << 8) + flg) % 31;
            if (rem != 0) {
                flg += 31 - rem;
            }
            compressed[0] = (byte) cmf;
            compressed[1] = (byte) flg;
        }

        return compressed;
    }

    /**
     * libpng/ImageMagick 的窗口选择：能容纳 {@code raw + 262} 字节的最小
     * windowBits（9..15）。实测 64x32（8224 字节）→ 14，64x64（16448）→ 15。
     */
    private static int windowBits(int rawLength) {
        long needed = rawLength + 262L;
        int bits = 9;
        while (bits < 15 && (1L << bits) < needed) {
            bits++;
        }

        return bits;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        writeInt(length, 0, data.length);
        out.write(length, 0, 4);

        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        writeInt(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes, 0, 4);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }
}
