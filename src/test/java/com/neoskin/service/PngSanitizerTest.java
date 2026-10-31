package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * PNG 净化字节级对齐：语料由 PHP 8.5 + Imagick 按源上传流程生成
 * （{@code png:compression-filter=0/level=9/strategy=0}、{@code exclude-chunk all}、
 * {@code stripImage()}）。
 *
 * <p>对齐后同一张上传在 PHP 版与 Java 版得到相同的 SHA-256 → 纹理 hash 一致。</p>
 */
class PngSanitizerTest {

    @Test
    void steveMatchesImagickBytes() throws Exception {
        assertMatches("steve");
    }

    @Test
    void capeMatchesImagickBytes() throws Exception {
        assertMatches("cape");
    }

    // avatar2d / avatar3d 是内置回退头像，不是上传对象：Imagick 会对少色图
    // 自动输出调色板 PNG（color type 3），Java 侧不做量化，故不做字节断言。

    private void assertMatches(String name) throws Exception {
        byte[] input = resource(name + ".png");
        byte[] expected = resource(name + ".imagick.png");

        byte[] actual = PngSanitizer.sanitize(input);

        assertArrayEquals(expected, actual,
                "与 Imagick 输出不一致：expected sha256=" + sha256(expected)
                        + " actual sha256=" + sha256(actual));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = PngSanitizerTest.class.getResourceAsStream("/png/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: " + name);
            }

            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
