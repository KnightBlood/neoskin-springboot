package com.neoskin.service.session;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.service.cache.ArrayCache;
import com.neoskin.service.cache.CacheFactory;
import com.neoskin.service.cache.CacheStore;
import com.neoskin.service.cache.RedisCache;

/**
 * 按 {@code SESSION_DRIVER} 创建 {@link SessionHandler}
 * （源 {@code Illuminate\Session\SessionManager#driver}）：
 *
 * <ul>
 *   <li>{@code file}（默认）→ {@link FileSessionHandler}
 *       （{@code storage/framework/sessions/<id>}，与 PHP 版共用目录）；</li>
 *   <li>{@code redis} → {@link CacheBasedSessionHandler} + {@link RedisCache}；
 *       与 Laravel 的 redis session driver 一样走 Redis，不随 {@code CACHE_DRIVER}
 *       变化（偏差见 PORT-NOTES）；</li>
 *   <li>{@code array} → 进程内（测试）；</li>
 *   <li>其他值 → 记 WARN 按 file 处理（源支持 memcached/dynamodb 等）。</li>
 * </ul>
 */
public final class SessionHandlerFactory {

    private SessionHandlerFactory() {
    }

    public static SessionHandler create(NeoSkinSettings settings, AppPaths paths,
            CacheStore cacheStore) {
        NeoSkinSettings.Session session = settings.getSession();
        int seconds = (int) Math.max(1, session.getLifetimeMinutes()) * 60;
        String driver = session.getDriver() == null ? "file"
                : session.getDriver().trim().toLowerCase();

        switch (driver) {
            case "redis":
                CacheStore store = cacheStore instanceof RedisCache cache
                        ? cache
                        : null;
                if (store == null && paths != null) {
                    // 会话驱动显式叫 redis：直接建 Redis 连接，不依赖 CACHE_DRIVER。
                    NeoSkinSettings.Cache conf = settings.getCache();
                    store = new RedisCache(conf.getRedisUrl(), conf.getRedisHost(),
                            conf.getRedisPort(), conf.getRedisUsername(),
                            conf.getRedisPassword(), conf.getRedisDatabase(),
                            conf.getPrefix(), conf.getRedisPrefix());
                }
                return store == null ? null : new CacheBasedSessionHandler(store, seconds);
            case "array":
                CacheStore inMemory = cacheStore == null ? new ArrayCache() : cacheStore;

                return new CacheBasedSessionHandler(inMemory, seconds);
            case "memcached":
                CacheStore memcached = cacheStore instanceof com.neoskin.service.cache.MemcachedCache
                        ? cacheStore : null;
                if (memcached == null && paths != null) {
                    NeoSkinSettings.Cache conf = settings.getCache();
                    memcached = new com.neoskin.service.cache.MemcachedCache(
                            conf.getMemcachedHost(), conf.getMemcachedPort(), conf.getPrefix());
                }

                return memcached == null ? null
                        : new CacheBasedSessionHandler(memcached, seconds);
            case "dynamodb":
                CacheStore dynamo = cacheStore instanceof com.neoskin.service.cache.DynamoDbCache
                        ? cacheStore : null;
                if (dynamo == null && paths != null) {
                    NeoSkinSettings.Cache conf = settings.getCache();
                    dynamo = new com.neoskin.service.cache.DynamoDbCache(
                            conf.getDynamodbEndpoint(), conf.getDynamodbRegion(),
                            conf.getDynamodbAccessKey(), conf.getDynamodbSecretKey(),
                            conf.getDynamodbTable(), conf.getPrefix());
                }

                return dynamo == null ? null
                        : new CacheBasedSessionHandler(dynamo, seconds);
            case "file", "":
                return paths == null ? null
                        : new FileSessionHandler(paths.storage("framework/sessions"), seconds / 60);
            case "cookie", "database", "apc":
                // 源支持这些 session driver；未移植 → 显式报错（不再静默退回 file）。
                throw new IllegalArgumentException("Unsupported SESSION_DRIVER: " + driver
                        + "（本移植仅支持 file / redis / memcached / dynamodb / array）");
            default:
                throw new IllegalArgumentException("Unsupported SESSION_DRIVER: " + driver
                        + " (expected file / redis / memcached / dynamodb / array)");
        }
    }

    /**
     * {@code WebConfig} 只注入 {@code CacheStore} 时的便捷重载（生产装配用；
     * 缺 store 时退 {@link CacheFactory} 处理未知驱动）。
     */
    public static SessionHandler create(NeoSkinSettings settings, AppPaths paths) {
        CacheStore store = com.neoskin.service.cache.CacheRuntime.store();

        // paths 为空（安装向导前的单元测试）时不构造 FileCache，让 create(...) 走「放弃」分支。
        if (store == null && paths != null) {
            store = CacheFactory.create(settings, paths);
        }

        return create(settings, paths, store);
    }
}
