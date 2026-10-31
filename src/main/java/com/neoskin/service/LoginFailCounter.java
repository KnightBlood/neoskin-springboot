package com.neoskin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.neoskin.service.cache.CacheStore;

/**
 * 登录失败计数器 —— 对应源里 {@code Cache::get(sha1('login_fails_'.$ip))} 的用法。
 *
 * <pre>
 * $ip = $filter-&gt;apply('client_ip', (new Whip())-&gt;getValidIpAddress());
 * $key = sha1('login_fails_'.$ip);
 * $tooManyFails = cache($key) &gt; 3;
 * // 登录失败时：Cache::put($key, $loginFails, 3600)
 * </pre>
 *
 * <p>两种实现：传 {@link CacheStore}（file/redis）时与 Laravel 的 Cache 等价（跨进程、
 * 1 小时过期）；不传时退回进程内计数器（测试/无缓存目录场景）。
 * 生产由 {@code WebConfig} 注入 {@code storage/framework/cache/data}。</p>
 */
public final class LoginFailCounter {

    /** 源：{@code Cache::put($key, $loginFails, 3600)}。 */
    public static final int TTL_SECONDS = 3600;

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final CacheStore cache;

    public LoginFailCounter() {
        this(null);
    }

    public LoginFailCounter(CacheStore cache) {
        this.cache = cache;
    }

    /** {@code sha1('login_fails_'.$ip)}。 */
    public static String keyForIp(String ip) {
        return sha1("login_fails_" + (ip == null ? "" : ip));
    }

    /** {@code cache($key)} 的整数语义（未记录时为 0）。 */
    public int get(String key) {
        if (cache != null) {
            Object value = cache.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return value == null ? 0 : Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        AtomicInteger counter = counts.get(key);

        return counter == null ? 0 : counter.get();
    }

    /** {@code cache($key)} 自增。 */
    public int increment(String key) {
        if (cache != null) {
            return cache.increment(key, 1, TTL_SECONDS);
        }

        return counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** {@code Cache::forget($key)}（登录成功后清计数）。 */
    public void forget(String key) {
        if (cache != null) {
            cache.forget(key);
            return;
        }
        counts.remove(key);
    }

    /** 测试用：清空进程内计数（file cache 请用独立的临时目录）。 */
    public void clear() {
        counts.clear();
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
