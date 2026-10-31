package com.neoskin.service.cache;

/**
 * 缓存存储接口（Laravel {@code Cache} 的最小抽象）。
 *
 * <p>语义与 Laravel 一致：</p>
 * <ul>
 *   <li>{@code seconds > 0} → 过期时间；{@code 0} → 永久；{@code < 0} → 立即过期；</li>
 *   <li>{@code increment} 在键过期/缺失时从 {@code amount} 开始，并设置 TTL；</li>
 *   <li>{@code ttlSeconds}：{@code -1} 表示不存在，{@code 0} 表示永久。</li>
 * </ul>
 *
 * <p>实现：{@link FileCache}（默认，兼容 PHP file store）、{@link RedisCache}
 * （{@code CACHE_DRIVER=redis}）、{@link ArrayCache}（{@code array} 驱动/测试）。</p>
 */
public interface CacheStore {

    /** 读值；缺失或过期返回 {@code null}。 */
    Object get(String key);

    /** 写入。{@code seconds <= 0} 按 {@link CacheStore} 类注释的 TTL 规则处理。 */
    void put(String key, Object value, int seconds);

    /** 自增并返回新值；键不存在时用 {@code seconds} 设置 TTL。 */
    int increment(String key, int amount, int seconds);

    /** 剩余秒数：{@code -1} 不存在，{@code 0} 永久。 */
    long ttlSeconds(String key);

    /** 删除单个键。 */
    void forget(String key);

    /** 清空（只清本前缀下的键）。 */
    void flush();
}
