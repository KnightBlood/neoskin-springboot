package com.neoskin.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内图片响应缓存（{@code Cache::remember} 的载体），供预览/头像端点与
 * 后台「清除缓存」共用。
 */
public final class ImageCache {

    /** 缓存条目（{@code bytes} 决定 ETag）。 */
    public record Entry(byte[] bytes, String contentType, String lastModified,
            long expireAt) {
    }

    private static final int MAX_ENTRIES = 512;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 取未过期条目；过期或不存在返回 {@code null}。 */
    public Entry get(String key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expireAt() <= System.currentTimeMillis()) {
            entries.remove(key);
            return null;
        }

        return entry;
    }

    public void put(String key, Entry entry) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear(); // 粗粒度护栏
        }
        entries.put(key, entry);
    }

    /** {@code Cache::flush()}：清空所有条目。 */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
