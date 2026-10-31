package com.neoskin.service.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内缓存（Laravel {@code array} store）。仅用于 {@code CACHE_DRIVER=array}
 * 与测试；进程重启即丢失。
 */
public final class ArrayCache implements CacheStore {

    private record Entry(Object value, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() > 0 && entry.expiresAt() <= nowSeconds()) {
            entries.remove(key);
            return null;
        }

        return entry.value();
    }

    @Override
    public void put(String key, Object value, int seconds) {
        long expiresAt = seconds > 0 ? nowSeconds() + seconds
                : seconds < 0 ? nowSeconds() - 1 : 0;
        entries.put(key, new Entry(value, expiresAt));
    }

    @Override
    public int increment(String key, int amount, int seconds) {
        synchronized (entries) {
            long expiresAt = 0;
            int current = 0;
            Entry entry = entries.get(key);
            if (entry != null && (entry.expiresAt() == 0 || entry.expiresAt() > nowSeconds())) {
                expiresAt = entry.expiresAt();
                current = entry.value() instanceof Number number ? number.intValue()
                        : Integer.parseInt(String.valueOf(entry.value()));
            } else {
                expiresAt = seconds > 0 ? nowSeconds() + seconds
                        : seconds < 0 ? nowSeconds() - 1 : 0;
            }
            int next = current + amount;
            entries.put(key, new Entry(next, expiresAt));

            return next;
        }
    }

    @Override
    public long ttlSeconds(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return -1;
        }
        if (entry.expiresAt() == 0) {
            return 0;
        }
        long remaining = entry.expiresAt() - nowSeconds();
        if (remaining <= 0) {
            entries.remove(key);
            return -1;
        }

        return remaining;
    }

    @Override
    public void forget(String key) {
        entries.remove(key);
    }

    @Override
    public void flush() {
        entries.clear();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
