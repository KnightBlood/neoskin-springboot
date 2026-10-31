package com.neoskin.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileCache} 的语义测试（对应 Laravel {@code FileStore}）。
 */
class FileCacheTest {

    @TempDir
    Path dir;

    @Test
    void putGetAndForgetRoundTrip() {
        FileCache cache = new FileCache(dir);
        cache.put("k", 42, 60);

        assertEquals(42, ((Number) cache.get("k")).intValue());
        cache.forget("k");
        assertNull(cache.get("k"));
    }

    @Test
    void zeroTtlMeansForeverAndNegativeMeansExpired() {
        FileCache cache = new FileCache(dir);
        cache.put("forever", "v", 0);
        cache.put("expired", "v", -1);

        assertEquals("v", cache.get("forever"));
        assertEquals(0, cache.ttlSeconds("forever"));
        assertNull(cache.get("expired"));
        assertEquals(-1, cache.ttlSeconds("expired"));
    }

    @Test
    void incrementKeepsOriginalExpiry() {
        FileCache cache = new FileCache(dir);

        assertEquals(1, cache.increment("counter", 1, 60));
        assertEquals(2, cache.increment("counter", 1, 60));
        assertEquals(5, cache.increment("counter", 3, 60));
        long ttl = cache.ttlSeconds("counter");
        assertTrue(ttl > 0 && ttl <= 60, "ttl=" + ttl);
    }

    @Test
    void valueIsStoredInPhpSerializeFormat() throws Exception {
        FileCache cache = new FileCache(dir);
        cache.put("php", "value", 0);

        try (Stream<Path> files = Files.walk(dir)) {
            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            String content = Files.readString(file);
            assertTrue(content.startsWith("a:2:{"), content);
            assertTrue(content.contains("\"expiration\""), content);
            assertTrue(content.contains("\"value\""), content);
        }
    }

    @Test
    void secondInstanceSeesPersistedValue() {
        new FileCache(dir).put("shared", 7, 60);

        assertEquals(7, ((Number) new FileCache(dir).get("shared")).intValue());
        assertEquals(8, new FileCache(dir).increment("shared", 1, 60));
    }
}
