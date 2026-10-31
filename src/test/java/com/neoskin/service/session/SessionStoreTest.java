package com.neoskin.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SessionStore} / {@link FileSessionHandler} 的语义测试。
 *
 * <p>会话文件内容就是 {@code serialize($attributes)}（字节形态由
 * {@code conformance/data/cookie.json} 的 serialize 语料担保），这里重点锁：</p>
 * <ol>
 *   <li>过期（mtime &lt; now - lifetime）→ read 返回空串；未过期 → 正常读；</li>
 *   <li>id 校验：40 位字母数字，非法一律换新；</li>
 *   <li>flash 轮转：save 后 new→old（仍在），再 save 后整个消失；</li>
 *   <li>与 PHP 会话文件的互通：PHP 写的字节 Java 读，Java 存的文件即 serialize 载荷。</li>
 * </ol>
 */
class SessionStoreTest {

    @TempDir
    Path dir;

    /** 一份手写的 serialize 载荷（_token 为 40 位占位串）。 */
    private static final String PAYLOAD = "a:2:{s:6:\"_token\";s:40:\"a0b1c2d3e4f5a6b7c8d9"
            + "e0f1a2b3c4d5e6f7a8b9\";s:4:\"lang\";s:5:\"zh_CN\";}";

    @Test
    void expiredFileReadsAsEmpty() throws Exception {
        Path file = dir.resolve("x".repeat(40));
        Files.createDirectories(dir);
        Files.writeString(file, PAYLOAD);

        // mtime 拉到 121 分钟前（> 120 分钟阈值）
        Files.setLastModifiedTime(file,
                FileTime.from(Instant.now().minusSeconds(121 * 60)));

        FileSessionHandler handler = new FileSessionHandler(dir, 120);
        var store = SessionStore.begin("BS_SESSION", handler, "x".repeat(40));
        store.start();

        assertFalse(store.has("lang"));

        // 不再写：save 后本文件保留 _token（start 补的）与空_lang
        store.save();
        String raw = Files.readString(file);

        assertTrue(raw.startsWith("a:3:"), raw);
    }

    @Test
    void freshFileReadsAttributesAndKeepsTokenIfPresent() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("x".repeat(40)), PAYLOAD);

        FileSessionHandler handler = new FileSessionHandler(dir, 120);
        var store = SessionStore.begin("BS_SESSION", handler, "x".repeat(40));
        store.start();

        assertTrue(store.has("lang"));
        assertEquals("zh_CN", store.get("lang"));
        // start 不会重新生成已有 _token
        assertEquals("a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9",
                String.valueOf(store.token()));
    }

    @Test
    void invalidIdsAreRegenerated() {
        for (String bad : new String[]{"a".repeat(41), "short", "b".repeat(39) + "/", null}) {
            var store = SessionStore.begin("BS_SESSION", new FileSessionHandler(dir, 120), bad);

            assertNotEquals(bad, store.getId());
            assertEquals(40, store.getId().length());
            assertTrue(store.isValidId(store.getId()));
        }
    }

    @Test
    void flashAgingRoundTrip() {
        FileSessionHandler handler = new FileSessionHandler(dir, 120);
        var store = SessionStore.begin("BS_SESSION", handler, null);
        store.start();

        store.put("keep", 1);
        store.flash("notice", "ok");
        store.save();
        store.start();

        // 第一次 save：new→old，数据仍在（重定向后可读）
        assertEquals("ok", store.get("notice"));
        assertEquals(1L, ((Number) store.get("keep")).longValue());

        store.save();
        store.start();

        // 第二次 save：_flash.old 里的键被 forget
        assertEquals(null, store.get("notice"));
        assertEquals(1L, ((Number) store.get("keep")).longValue());
    }

    @Test
    void javaWriteIsPhpSerializePayload(@TempDir Path dir2) throws Exception {
        FileSessionHandler handler = new FileSessionHandler(dir, 120);
        var store = SessionStore.begin("BS_SESSION", handler, null);
        store.start();
        store.put("lang", "zh_CN");
        store.put("n", 42);
        store.save();

        String raw = Files.readString(dir.resolve(store.getId()));

        // 载荷即 PHP serialize()：键序 = put 顺序（PHP 数组保持插入序，与
        // PhpJson.Array 的 LinkedHashMap 一致）。
        assertTrue(raw.startsWith("a:5:"), raw);
        assertTrue(raw.contains("s:4:\"lang\";s:5:\"zh_CN\";"), raw);
        assertTrue(raw.contains("s:1:\"n\";i:42;"), raw);
        // _flash 包龄轮转的空包也随载荷写盘
        assertTrue(raw.contains("s:10:\"_flash.old\";a:0:{}"), raw);
    }

    @Test
    void phpWrittenFileIsReadable(@TempDir Path dir2) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a".repeat(40)), PAYLOAD, StandardCharsets.UTF_8);

        var store = SessionStore.begin("BS_SESSION",
                new FileSessionHandler(dir, 120), "a".repeat(40));
        store.start();

        assertEquals("zh_CN", store.get("lang"));
        assertEquals(40, String.valueOf(store.token()).length());
    }
}
