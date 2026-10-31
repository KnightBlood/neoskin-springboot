package com.neoskin.service.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Redis 缓存（Laravel {@code RedisStore} 的移植，{@code CACHE_DRIVER=redis}）。
 *
 * <p>与 Laravel 一致：数字直接以字符串存储（{@code incrBy} 可用），其他值用
 * PHP {@code serialize()}；缓存键加 {@code CACHE_PREFIX}。{@code ttlSeconds}
 * 把 Redis 的 {@code -2}（不存在）/ {@code -1}（永久）映射为 {@code -1} / {@code 0}。</p>
 *
 * <p>连接失败时（启动时 ping 失败）记 ERROR 并降级为进程内 {@link ArrayCache}，
 * 避免因缓存不可用导致整站 500；恢复 Redis 后需重启进程。</p>
 */
public final class RedisCache implements CacheStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);

    private final JedisPool pool;
    private final String prefix;
    private final ArrayCache fallback = new ArrayCache();
    private volatile boolean available;

    public RedisCache(String host, int port, String username, String password, int database,
            String prefix) {
        this(null, host, port, username, password, database, prefix, "");
    }

    /**
     * @param url               {@code REDIS_URL}（优先于 host/port/username/password/database）
     * @param connectionPrefix  {@code REDIS_PREFIX}（连接级前缀，拼在缓存前缀之前）
     */
    public RedisCache(String url, String host, int port, String username, String password,
            int database, String cachePrefix, String connectionPrefix) {
        RedisUrl parsed = RedisUrl.parse(url);
        String actualHost = host;
        int actualPort = port;
        String actualUser = username;
        String actualPass = password;
        int actualDatabase = database;
        if (parsed != null) {
            actualHost = parsed.host();
            actualPort = parsed.port();
            actualUser = parsed.username() == null ? username : parsed.username();
            actualPass = parsed.password() == null ? password : parsed.password();
            actualDatabase = parsed.database();
        }
        this.prefix = (connectionPrefix == null ? "" : connectionPrefix)
                + (cachePrefix == null ? "" : cachePrefix);
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        String user = blankToNull(actualUser);
        String pass = blankToNull(actualPass);
        this.pool = new JedisPool(config,
                actualHost == null || actualHost.isBlank() ? "127.0.0.1" : actualHost,
                actualPort <= 0 ? 6379 : actualPort, 2000, user, pass, actualDatabase, null);
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
            available = true;
            log.info("Redis cache connected: {}:{}/{}", actualHost, actualPort, actualDatabase);
        } catch (Exception e) {
            available = false;
            log.error("Redis cache unavailable ({}:{}): {} — 降级为进程内缓存",
                    actualHost, actualPort, e.getMessage());
        }
    }

    @Override
    public Object get(String key) {
        if (!available) {
            return fallback.get(key);
        }
        try (Jedis jedis = pool.getResource()) {
            return deserialize(jedis.get(prefix + key));
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
        try (Jedis jedis = pool.getResource()) {
            String payload = serialize(value);
            if (seconds > 0) {
                jedis.setex(prefix + key, seconds, payload);
            } else if (seconds == 0) {
                jedis.set(prefix + key, payload);
            } else {
                // 负 TTL = 已过期：等价于写入后立即失效（Laravel file store 也是如此）
                jedis.del(prefix + key);
            }
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
        try (Jedis jedis = pool.getResource()) {
            String full = prefix + key;
            long value = jedis.incrBy(full, amount);
            if (seconds > 0 && jedis.ttl(full) == -1) {
                jedis.expire(full, seconds);
            }

            return (int) value;
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
        try (Jedis jedis = pool.getResource()) {
            long ttl = jedis.ttl(prefix + key);
            if (ttl == -2) {
                return -1;
            }

            return ttl == -1 ? 0 : ttl;
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
        try (Jedis jedis = pool.getResource()) {
            jedis.del(prefix + key);
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
        try (Jedis jedis = pool.getResource()) {
            ScanParams params = new ScanParams().match(prefix + "*").count(1000);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                if (!result.getResult().isEmpty()) {
                    jedis.del(result.getResult().toArray(String[]::new));
                }
                cursor = result.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            degrade(e);
            fallback.flush();
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    /** 测试/诊断用：当前是否连上了 Redis。 */
    public boolean available() {
        return available;
    }

    private void degrade(Exception e) {
        available = false;
        log.error("Redis cache error, 降级为进程内缓存：{}", e.getMessage());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value)
                ? null : value;
    }

    private static String serialize(Object value) {
        if (value instanceof Number number) {
            return String.valueOf(number);
        }

        return PhpSerialize.serialize(value);
    }

    private static Object deserialize(String value) {
        if (value == null) {
            return null;
        }
        if (value.matches("-?\\d+")) {
            return Long.parseLong(value);
        }
        try {
            return PhpUnserialize.parse(value).value();
        } catch (Exception e) {
            return value;
        }
    }
}
