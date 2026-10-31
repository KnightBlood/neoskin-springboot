package com.neoskin.service.cache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

import net.spy.memcached.MemcachedClient;

/**
 * Memcached 缓存（Laravel {@code MemcachedStore} 的移植，
 * {@code CACHE_DRIVER=memcached}）。
 *
 * <p>语义与 Laravel 一致：</p>
 * <ul>
 *   <li>键加 {@code CACHE_PREFIX}；</li>
 *   <li>过期：{@code >0} 相对秒数、{@code 0} 永久、{@code <0} 立即过期；</li>
 *   <li>{@code increment}：键不存在时 {@code add(amount)}，否则 {@code incr}；
 *       失败（非数字）则覆盖为 {@code amount}；</li>
 *   <li>{@code flush} 调 Memcached 的 {@code flush_all}（源同样清空整个实例）。</li>
 * </ul>
 *
 * <p>值编码沿用 PHP {@code serialize()}（与 file/redis store 一致），
 * 便于与 PHP 版共用数据。Memcached 不提供 TTL 查询，{@link #ttlSeconds}
 * 只能返回「不存在 / 永久」（{@code -1} / {@code 0}）。</p>
 *
 * <p>连接失败时记 ERROR 并降级为进程内 {@link ArrayCache}（同 {@link RedisCache}），
 * 站点不至于因缓存后端不可用而 500。</p>
 */
public final class MemcachedCache implements CacheStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemcachedCache.class);

    /** Memcached 的最大相对过期秒数（超过按绝对 Unix 时间解释）。 */
    private static final int MAX_RELATIVE_EXPIRATION = 60 * 60 * 24 * 30;

    private final MemcachedClient client;
    private final String prefix;
    private final ArrayCache fallback = new ArrayCache();
    private volatile boolean available;

    public MemcachedCache(String host, int port, String prefix) {
        this.prefix = prefix == null ? "" : prefix;
        String target = host == null || host.isBlank() ? "127.0.0.1" : host;
        int targetPort = port <= 0 ? 11211 : port;
        MemcachedClient opened = null;
        try {
            opened = new MemcachedClient(new InetSocketAddress(target, targetPort));
            // 触发一次真实操作判断连通性（spymemcached 构造即异步连接）
            opened.get(prefix + "__neoskin_probe__");
            available = true;
            log.info("Memcached cache connected: {}:{}", target, targetPort);
        } catch (Exception e) {
            log.error("Memcached unavailable ({}:{}): {} — 降级为进程内缓存",
                    target, targetPort, e.getMessage());
            if (opened != null) {
                opened.shutdown();
            }
            opened = null;
        }
        this.client = opened;
    }

    @Override
    public Object get(String key) {
        if (!available) {
            return fallback.get(key);
        }
        try {
            Object value = client.get(prefix + key);

            return deserialize(value);
        } catch (Exception e) {
            degrade(e);
            return fallback.get(key);
        }
    }

    @Override
    public void put(String key, Object value, int seconds) {
        if (!available) {
            fallback.put(key, value, seconds);
            return;
        }
        try {
            if (seconds < 0) {
                client.delete(prefix + key).get(1, TimeUnit.SECONDS);

                return;
            }
            client.set(prefix + key, expiration(seconds), serialize(value))
                    .get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            degrade(e);
            fallback.put(key, value, seconds);
        }
    }

    @Override
    public int increment(String key, int amount, int seconds) {
        if (!available) {
            return fallback.increment(key, amount, seconds);
        }
        String full = prefix + key;
        try {
            if (client.add(full, expiration(seconds), String.valueOf(amount))
                    .get(1, TimeUnit.SECONDS)) {
                return amount;
            }
            long next = client.incr(full, amount);
            if (next >= 0) {
                return (int) next;
            }
            // 非数值或已过期：覆盖为 amount
            client.set(full, expiration(seconds), String.valueOf(amount))
                    .get(1, TimeUnit.SECONDS);

            return amount;
        } catch (Exception e) {
            degrade(e);
            return fallback.increment(key, amount, seconds);
        }
    }

    @Override
    public long ttlSeconds(String key) {
        if (!available) {
            return fallback.ttlSeconds(key);
        }
        try {
            // Memcached 无法查询剩余 TTL；只能区分存在/不存在
            return client.get(prefix + key) == null ? -1 : 0;
        } catch (Exception e) {
            degrade(e);
            return fallback.ttlSeconds(key);
        }
    }

    @Override
    public void forget(String key) {
        if (!available) {
            fallback.forget(key);
            return;
        }
        try {
            client.delete(prefix + key).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            degrade(e);
            fallback.forget(key);
        }
    }

    @Override
    public void flush() {
        if (!available) {
            fallback.flush();
            return;
        }
        try {
            client.flush().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            degrade(e);
            fallback.flush();
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.shutdown();
        }
    }

    /** 测试/诊断用：当前是否连上了 Memcached。 */
    public boolean available() {
        return available;
    }

    private void degrade(Exception e) {
        available = false;
        log.error("Memcached error, 降级为进程内缓存：{}", e.getMessage());
    }

    /** Laravel MemcachedStore：0 = 永久；其余按相对秒数（超过 30 天由客户端按绝对时间处理）。 */
    private static int expiration(int seconds) {
        return seconds <= 0 ? 0 : seconds;
    }

    private static Object serialize(Object value) {
        return PhpSerialize.serialize(value);
    }

    private static Object deserialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }

        return parse(String.valueOf(value));
    }

    private static Object parse(String raw) {
        try {
            return PhpUnserialize.parse(raw).value();
        } catch (Exception e) {
            return raw;
        }
    }
}
