package com.neoskin.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * PNG「净化器 + 尺寸读取」，对应 {@code Intervention\Image} +
 * {@code getimagesize()} 在上传流程里的用法。
 *
 * <p>源实现用 Imagick 重编码（compression-filter 0 / level 9 / strategy 0 /
 * exclude-chunk all / stripImage）。Java 侧用 {@link ImageIO} 解码像素后
 * 重新编码为「只含关键块」的标准 PNG：元数据与 ancillary chunk 全部丢弃，
 * 等效实现 {@code stripImage + exclude-chunk all} 的语义（字节级输出与
 * Imagick 不同，但结构性一致 —— 见 PORT-NOTES）。</p>
 */
public final class PngSanitizer {

    /** {@code getimagesize()} 的 [0],[1]。 */
    public record Size(int width, int height) {
    }

    private PngSanitizer() {
    }

    /** PNG 魔数（{@code mimes:png} 的文件内容嗅探）。 */
    public static boolean isPng(byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }

        return (data[0] & 0xFF) == 0x89
                && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == '\r' && data[5] == '\n'
                && data[6] == 0x1A && data[7] == '\n';
    }

    /** 解码（拉平到 ARGB 栅格并重编码为标准 PNG），失败得 null（等效无效图）。 */
    public static byte[] sanitize(byte[] data) {
        BufferedImage image = decode(data);

        if (image == null) {
            return null;
        }

        try {
            // 与源 Imagick 参数（compression-filter 0 / level 9 / strategy 0 /
            // exclude-chunk all / stripImage）字节级对齐，见 PngEncoder。
            return com.neoskin.support.PngEncoder.encode(image);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code getimagesize($file)}：读图片头两维。非 PNG 或无法解码 → null。 */
    @SuppressWarnings("unused")
    public static Size size(byte[] data) {
        BufferedImage image = decode(data);

        return image == null ? null : new Size(image.getWidth(), image.getHeight());
    }

    private static BufferedImage decode(byte[] data) {
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
            g.drawImage(input, 0, 0, null);
            g.dispose();

            return argb;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
