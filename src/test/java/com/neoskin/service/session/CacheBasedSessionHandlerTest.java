package com.neoskin.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.cache.ArrayCache;
import com.neoskin.service.cache.FileCache;

/**
 * {@link CacheBasedSessionHandler} 的缓存型会话语义（对齐 Laravel
 * {@code CacheBasedSessionHandler}）。
 */
class CacheBasedSessionHandlerTest {

    @TempDir
    Path dir;

    @Test
    void writeThenReadRoundTrip() {
        ArrayCache cache = new ArrayCache();
        CacheBasedSessionHandler handler = new CacheBasedSessionHandler(cache, 120);

        assertTrue(handler.write("a".repeat(40), "a:2:{s:6:\"_token\";s:40:\"t\";}"));
        assertEquals("a:2:{s:6:\"_token\";s:40:\"t\";}", handler.read("a".repeat(40)));
    }

    @Test
    void destroyRemovesKey() {
        String id = "b".repeat(40);
        ArrayCache cache = new ArrayCache();
        CacheBasedSessionHandler handler = new CacheBasedSessionHandler(cache, 120);

        handler.write(id, "payload");
        assertTrue(handler.destroy(id));
        assertEquals("", handler.read(id));
    }

    @Test
    void missingKeyReadsEmptyString() {
        CacheBasedSessionHandler handler =
                new CacheBasedSessionHandler(new ArrayCache(), 120);

        assertEquals("", handler.read("c".repeat(40)));
    }

    /** 进程内共享（多实例语义）：两个 handler 实例看到同一份数据。 */
    @Test
    void sharedAcrossHandlerInstances() throws Exception {
        FileCache cache = new FileCache(dir.resolve("cache"));
        String id = "d".repeat(40);

        new CacheBasedSessionHandler(cache, 120).write(id, "payload");
        assertEquals("payload",
                new CacheBasedSessionHandler(cache, 120).read(id));
    }

    /** TTL 到期直接消失（缓存层兜底过期，handler 的 gc 是 no-op）。 */
    @Test
    void expiredPayloadReadsEmptyString() throws Exception {
        String id = "e".repeat(40);
        FileCache cache = new FileCache(dir.resolve("cache-exp"));
        CacheBasedSessionHandler handler = new CacheBasedSessionHandler(cache, 1);

        handler.write(id, "payload");
        Thread.sleep(1100);
        assertEquals("", handler.read(id));
        assertNull(cache.get(id));
    }

    @Test
    void fileHandlerIsTheFileDriverImplementation() throws Exception {
        assertInstanceOf(SessionHandler.class,
                new FileSessionHandler(dir.resolve("sessions"), 120));
    }
}
