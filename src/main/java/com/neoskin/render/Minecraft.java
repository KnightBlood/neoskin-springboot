package com.neoskin.render;

/**
 * {@code Blessing\Minecraft} 的移植：渲染器门面。
 */
public final class Minecraft {

    /** {@code renderSkin($skin, $ratio = 7.0, $isAlex = false)}。 */
    public GdImage renderSkin(byte[] skin, double ratio, boolean isAlex) {
        int vp = 15; // vertical padding
        int hp = 30; // horizontal padding
        int ip = 15; // internal padding

        SkinRenderer frontRenderer = new SkinRenderer(ratio, false, -45);
        GdImage front = frontRenderer.render(skin, isAlex);
        if (front == null) {
            return null;
        }

        SkinRenderer backRenderer = new SkinRenderer(ratio, false, 135);
        GdImage back = backRenderer.render(skin, isAlex);
        if (back == null) {
            return null;
        }

        int width = front.width();
        int height = front.height();

        GdImage canvas = GdImage.createEmptyCanvas((hp + width + ip) * 2, vp * 2 + height);
        canvas.copyFrom(back, hp, vp);
        canvas.copyFrom(front, hp + width + ip * 2, vp);

        return canvas;
    }

    /** {@code renderSkin($skin)}（默认参数）。 */
    public GdImage renderSkin(byte[] skin) {
        return renderSkin(skin, 7.0, false);
    }

    /** {@code renderCape($cape, $height)}。 */
    public GdImage renderCape(byte[] cape, int height) {
        int vp = 20; // vertical padding
        int hp = 40; // horizontal padding

        CapeRenderer renderer = new CapeRenderer();
        GdImage rendered = renderer.render(cape, height);
        if (rendered == null) {
            return null;
        }

        int width = rendered.width();
        int renderedHeight = rendered.height();

        GdImage canvas = GdImage.createEmptyCanvas(hp * 2 + width, vp * 2 + renderedHeight);
        canvas.copyFrom(rendered, hp, vp);

        return canvas;
    }

    /** {@code render2dAvatar($skin, $ratio = 15.0)}。 */
    public GdImage render2dAvatar(byte[] skin, double ratio) {
        SkinRenderer renderer = new SkinRenderer(ratio, true, 0, 0);

        return renderer.render(skin, false);
    }

    public GdImage render2dAvatar(byte[] skin) {
        return render2dAvatar(skin, 15.0);
    }

    /** {@code render3dAvatar($skin, $ratio = 15.0)}（hR=45，vR 用构造默认 -25）。 */
    public GdImage render3dAvatar(byte[] skin, double ratio) {
        SkinRenderer renderer = new SkinRenderer(ratio, true, 45);

        return renderer.render(skin, false);
    }

    public GdImage render3dAvatar(byte[] skin) {
        return render3dAvatar(skin, 15.0);
    }
}
