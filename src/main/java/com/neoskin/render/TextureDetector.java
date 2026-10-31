package com.neoskin.render;

import java.awt.image.BufferedImage;

/**
 * {@code Blessing\Renderer\TextureUtil::isAlex} 的移植：
 * 检查 (46..47, 52..63)（以 64 宽度归一）是否全黑（alpha 版型判定）。
 */
public final class TextureDetector {

    private TextureDetector() {
    }

    public static boolean isAlex(GdImage image) {
        int width = image.width();

        if (width == image.height()) {
            int ratio = width / 64;

            for (int x = 46 * ratio; x < 48 * ratio; x += 1) {
                for (int y = 52 * ratio; y < 64 * ratio; y += 1) {
                    if (!checkPixel(image.pixelAt(x, y))) {
                        return false;
                    }
                }
            }

            return true;
        }

        return false;
    }

    private static boolean checkPixel(int javaPixel) {
        return (javaPixel & 0xFF) == 0
                && ((javaPixel >> 8) & 0xFF) == 0
                && ((javaPixel >> 16) & 0xFF) == 0;
    }
}
