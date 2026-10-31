package com.neoskin.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code REDIS_URL} 解析（源 {@code config/database.php} 的 redis url）。
 */
class RedisUrlTest {

    @Test
    void parsesHostPortAndDatabase() {
        RedisUrl url = RedisUrl.parse("redis://127.0.0.1:6380/3");

        assertEquals("127.0.0.1", url.host());
        assertEquals(6380, url.port());
        assertEquals(3, url.database());
        assertNull(url.password());
    }

    @Test
    void parsesCredentials() {
        RedisUrl url = RedisUrl.parse("redis://user:secret@redis.local:6379/1");

        assertEquals("redis.local", url.host());
        assertEquals("user", url.username());
        assertEquals("secret", url.password());
        assertEquals(1, url.database());
    }

    @Test
    void parsesPasswordOnly() {
        RedisUrl url = RedisUrl.parse("redis://:p%40ss@localhost/2");

        assertEquals("p@ss", url.password());
        assertEquals(2, url.database());
    }

    @Test
    void defaultsAndInvalidValues() {
        assertEquals(6379, RedisUrl.parse("redis://localhost").port());
        assertEquals(0, RedisUrl.parse("redis://localhost").database());
        assertNull(RedisUrl.parse(""));
        assertNull(RedisUrl.parse("http://localhost"));
    }

    @Test
    void redissIsAcceptedAsPlain() {
        RedisUrl url = RedisUrl.parse("rediss://localhost:6380/0");

        assertEquals(6380, url.port());
    }
}
