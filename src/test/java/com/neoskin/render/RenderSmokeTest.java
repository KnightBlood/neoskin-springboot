package com.neoskin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * 渲染器移植的烟测护栏：用随库自带的 steve.png（64x32 旧版皮肤）走全部渲染路径，
 * 确认尺寸计算与边界行为与 PHP 侧一致（head-only 2D 头像应为 135x135）。
 */
class RenderSmokeTest {

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = RenderSmokeTest.class
                .getResourceAsStream("/misc/textures/" + name)) {
            assertNotNull(in, "missing test resource " + name);

            return in.readAllBytes();
        }
    }

    private static void assertPng(byte[] bytes) {
        assertNotNull(bytes);
        assertTrue(bytes.length > 8, "empty png");
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        assertEquals((byte) 'N', bytes[2]);
        assertEquals((byte) 'G', bytes[3]);
    }

    @Test
    void steveIsNotAlex() throws IOException {
        assertFalse(TextureDetector.isAlex(GdImage.make(resource("steve.png"))));
    }

    @Test
    void render2dAvatarHeadOnly() throws IOException {
        Minecraft minecraft = new Minecraft();
        GdImage avatar = minecraft.render2dAvatar(resource("steve.png"));

        assertNotNull(avatar);
        // bounds: 头/helmet 包围盒 x/y ∈ [-0.5, 8.5] → 宽高 9，src = 30*9+1 = 271，缩半 135
        assertEquals(135, avatar.width());
        assertEquals(135, avatar.height());
        assertPng(avatar.toPng());

        // 非「空画面」护栏：steve 脸部应有足够多的不同颜色像素
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < avatar.height(); y++) {
            for (int x = 0; x < avatar.width(); x++) {
                int pixel = avatar.pixelAt(x, y);
                if ((pixel >>> 24) > 0x10) { // 忽略近全透明像素
                    colors.add(pixel);
                }
            }
        }
        assertTrue(colors.size() > 30, "rendered avatar looks blank, distinct colors = "
                + colors.size());
    }

    @Test
    void render3dAvatar() throws IOException {
        Minecraft minecraft = new Minecraft();
        GdImage avatar = minecraft.render3dAvatar(resource("steve.png"));

        assertNotNull(avatar);
        assertTrue(avatar.width() > 0 && avatar.height() > 0);
        assertPng(avatar.toPng());
    }

    @Test
    void renderSkinReturnsJoinedFrontBack() throws IOException {
        Minecraft minecraft = new Minecraft();
        GdImage rendered = minecraft.renderSkin(resource("steve.png"));

        assertNotNull(rendered);
        assertTrue(rendered.width() > 0 && rendered.height() > 0);
        assertPng(rendered.toPng());
    }

    @Test
    void renderCape() throws IOException {
        Minecraft minecraft = new Minecraft();
        GdImage rendered = minecraft.renderCape(fakeCapePng(), 64);

        assertNotNull(rendered);
        // CapeRenderer: out 40x64；门面画布 (40*2+40) x (20*2+64)
        assertEquals(120, rendered.width());
        assertEquals(104, rendered.height());
        assertPng(rendered.toPng());
    }

    private static byte[] fakeCapePng() throws IOException {
        BufferedImage cape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                cape.setRGB(x, y, 0xFF00FF00); // 不透明纯色
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(cape, "png", out);

        return out.toByteArray();
    }
}
