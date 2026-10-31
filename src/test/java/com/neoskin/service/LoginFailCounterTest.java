package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.cache.FileCache;

/**
 * {@link LoginFailCounter} 两种实现（进程内 / file cache）的语义测试。
 */
class LoginFailCounterTest {

    @TempDir
    Path dir;

    @Test
    void inMemoryCounterIncrementsAndForgets() {
        LoginFailCounter counter = new LoginFailCounter();
        String key = LoginFailCounter.keyForIp("127.0.0.1");

        assertEquals(0, counter.get(key));
        assertEquals(1, counter.increment(key));
        assertEquals(2, counter.increment(key));
        counter.forget(key);
        assertEquals(0, counter.get(key));
    }

    @Test
    void fileCacheCounterIsSharedAcrossInstances() {
        FileCache cache = new FileCache(dir.resolve("cache"));
        String key = LoginFailCounter.keyForIp("10.0.0.1");

        LoginFailCounter first = new LoginFailCounter(cache);
        assertEquals(1, first.increment(key));
        assertEquals(2, first.increment(key));

        // 新实例（模拟另一个进程）读同一个 file cache
        LoginFailCounter second = new LoginFailCounter(new FileCache(dir.resolve("cache")));
        assertEquals(2, second.get(key));
        assertEquals(3, second.increment(key));

        second.forget(key);
        assertEquals(0, second.get(key));

        // 1 小时窗口
        FileCache probe = new FileCache(dir.resolve("cache"));
        probe.increment(key, 1, 3600);
        long ttl = probe.ttlSeconds(key);
        assertTrue(ttl > 0 && ttl <= 3600, "ttl=" + ttl);
    }
}
