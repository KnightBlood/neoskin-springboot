package com.neoskin.service.session;

import com.neoskin.service.cache.CacheStore;

/**
 * 缓存型会话 handler（Laravel {@code CacheBasedSessionHandler}），
 * {@code SESSION_DRIVER=redis} / {@code array} 时启用。
 *
 * <p>与源语义一致：缓存键就是 40 位会话 id，载荷是上层
 * {@link SessionStore} PHP 序列化后的整包字符串；TTL =
 * {@code session.lifetime} 分钟，过期由缓存层兜底，所以
 * {@code gc()} 在缓存驱动里本来就是 no-op（源里逐字如此）。</p>
 *
 * <p>偏差：Laravel 的 redis 会话走独立 {@code RedisStore}
 * （键为 {@code 缓存前缀:id}），这里直接复用应用的 {@link CacheStore}
 * ——键同{@code CACHE_PREFIX} 命名空间、{@code flush()} 是「只清本前缀」
 * 的 SCAN 实现，不会截会话键；多实例共享与源等价。</p>
 */
public final class CacheBasedSessionHandler implements SessionHandler {

    /** TTL（秒），来自 {@code session.lifetime}。 */
    private final int seconds;

    private final CacheStore cache;

    public CacheBasedSessionHandler(CacheStore cache, int seconds) {
        this.cache = cache;
        this.seconds = seconds;
    }

    @Override
    public String read(String id) {
        Object value = cache.get(id);

        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public boolean write(String id, String data) {
        // CacheStore 语义：seconds > 0 过期；与源 put($id, $data, $expiration) 一致。
        cache.put(id, data, seconds);

        return true;
    }

    @Override
    public boolean destroy(String id) {
        cache.forget(id);

        return true;
    }
}
