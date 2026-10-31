package com.neoskin.render;

/**
 * {@code Blessing\Renderer\CapeRenderer} 的移植：从披风整图裁出可显示区域。
 */
public final class CapeRenderer {

    /** {@code render($source, int $height)}。 */
    public GdImage render(byte[] source, int height) {
        GdImage resource = GdImage.make(source);
        if (resource == null) {
            return null;
        }

        int srcWidth = resource.width();
        int srcHeight = resource.height();
        double hdRatio = srcWidth / 64.0;
        int outHeight = height;
        double outWidthD = outHeight / 16.0 * 10;
        int outWidth = (int) outWidthD;

        GdImage canvas = GdImage.createEmptyCanvas(outWidth, outHeight);
        canvas.resampleRegion(resource, 0, 0, outWidth, outHeight,
                (int) (1 * hdRatio), (int) (1 * hdRatio),
                (int) (srcWidth * 10 / 64.0), (int) (srcHeight * 16 / 32.0));

        return canvas;
    }
}
