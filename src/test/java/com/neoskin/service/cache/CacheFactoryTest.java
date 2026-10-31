package com.neoskin.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

/**
 * {@link CacheFactory} 的驱动选择与 Redis 不可用时的降级。
 */
class CacheFactoryTest {

    @TempDir
    Path dir;

    @Test
    void defaultDriverIsFile() throws Exception {
        NeoSkinSettings settings = settings("file");
        CacheStore store = CacheFactory.create(settings, paths(settings));

        assertInstanceOf(FileCache.class, store);
    }

    @Test
    void arrayDriverUsesInMemoryStore() throws Exception {
        NeoSkinSettings settings = settings("array");

        assertInstanceOf(ArrayCache.class, CacheFactory.create(settings, paths(settings)));
    }

    @Test
    void unsupportedDriverFailsFast() throws Exception {
        NeoSkinSettings settings = settings("database");

        // 源会抛 InvalidArgumentException；这里同样显式失败而不是静默降级
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CacheFactory.create(settings, paths(settings)));
    }

    @Test
    void unreachableRedisDegradesToLocalCache() throws Exception {
        NeoSkinSettings settings = settings("redis");
        settings.getCache().setRedisHost("127.0.0.1");
        settings.getCache().setRedisPort(1); // 必然连不上

        CacheStore store = CacheFactory.create(settings, paths(settings));
        assertInstanceOf(RedisCache.class, store);
        assertTrue(!((RedisCache) store).available());

        // 降级后仍可读写（进程内）
        store.put("k", "v", 60);
        assertEquals("v", store.get("k"));
        store.forget("k");
        assertNull(store.get("k"));
    }

    @Test
    void memcachedDriverUsesMemcachedStore() throws Exception {
        NeoSkinSettings settings = settings("memcached");
        settings.getCache().setMemcachedHost("127.0.0.1");
        settings.getCache().setMemcachedPort(1); // 必然连不上 → 降级

        CacheStore store = CacheFactory.create(settings, paths(settings));
        assertInstanceOf(com.neoskin.service.cache.MemcachedCache.class, store);
        assertTrue(!((com.neoskin.service.cache.MemcachedCache) store).available());

        store.put("k", "v", 60);
        assertEquals("v", store.get("k"));
    }

    private NeoSkinSettings settings(String driver) {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(dir.resolve("t.sqlite").toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        settings.getCache().setDriver(driver);

        return settings;
    }

    private AppPaths paths(NeoSkinSettings settings) throws Exception {
        Files.createDirectories(dir.resolve("storage"));

        return new AppPaths(dir, settings);
    }
}
