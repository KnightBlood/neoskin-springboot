package com.neoskin.service.cache;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

/**
 * 按 {@code CACHE_DRIVER} 创建 {@link CacheStore}：
 *
 * <ul>
 *   <li>{@code file}（默认）→ {@link FileCache}（{@code storage/framework/cache/data}，
 *       与 PHP file store 同格式）；</li>
 *   <li>{@code redis} → {@link RedisCache}（{@code REDIS_*} / {@code REDIS_CACHE_DB}）；</li>
 *   <li>{@code array} → {@link ArrayCache}；</li>
 *   <li>其他值 → 记 WARN 并按 file 处理（源会抛异常；这里不让缓存配置打挂站点）。</li>
 * </ul>
 */
public final class CacheFactory {

    private CacheFactory() {
    }

    public static CacheStore create(NeoSkinSettings settings, AppPaths paths) {
        NeoSkinSettings.Cache cache = settings.getCache();
        String driver = cache.getDriver() == null ? "file"
                : cache.getDriver().trim().toLowerCase();
        switch (driver) {
            case "redis":
                return new RedisCache(cache.getRedisUrl(), cache.getRedisHost(),
                        cache.getRedisPort(), cache.getRedisUsername(),
                        cache.getRedisPassword(), cache.getRedisDatabase(),
                        cache.getPrefix(), cache.getRedisPrefix());
            case "array":
                return new ArrayCache();
            case "memcached":
                return new MemcachedCache(cache.getMemcachedHost(), cache.getMemcachedPort(),
                        cache.getPrefix());
            case "dynamodb":
                return new DynamoDbCache(cache.getDynamodbEndpoint(), cache.getDynamodbRegion(),
                        cache.getDynamodbAccessKey(), cache.getDynamodbSecretKey(),
                        cache.getDynamodbTable(), cache.getPrefix());
            case "file", "":
                return new FileCache(paths.storage("framework/cache/data"));
            case "database", "apc", "octane":
                // 源支持这些 store；Java 侧未移植 → 与源「不认识的驱动直接报错」一致，
                // 不再静默降级成 file（静默降级会让部署以为缓存生效）。
                throw new IllegalArgumentException("Unsupported CACHE_DRIVER: " + driver
                        + "（本移植仅支持 file / redis / memcached / dynamodb / array）");
            default:
                throw new IllegalArgumentException("Unsupported CACHE_DRIVER: " + driver
                        + " (expected file / redis / memcached / dynamodb / array)");
        }
    }
}
