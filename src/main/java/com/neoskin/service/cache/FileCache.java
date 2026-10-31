package com.neoskin.service.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

/**
 * Laravel {@code FileStore} 的最小移植（{@code storage/framework/cache/data}）。
 *
 * <p>文件格式与 PHP 版一致：{@code serialize(['expiration' => <epoch>, 'value' => ...])}，
 * 路径为 {@code <dir>/<sha1[0:2]>/<sha1[2:4]>/<sha1>}，因此 PHP 版与 Java 版可以
 * 共用同一个缓存目录（{@code expiration=0} 表示永久）。</p>
 *
 * <p>并发：{@code increment} 用 64 个条带锁保证同进程原子性；跨进程的并发自增
 * 仍有竞态（与 Laravel file store 的 {@code increment} 行为一致，都是 read-modify-write）。</p>
 */
public final class FileCache implements CacheStore {

    /** 与 Laravel 的 1 小时登录失败窗口一致（调用方也可自行指定）。 */
    public static final int DEFAULT_TTL_SECONDS = 3600;

    private final Path directory;
    private final Object[] locks = new Object[64];

    public FileCache(Path directory) {
        this.directory = directory;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    /** 读值；缺失/过期/损坏都返回 {@code null}（过期会顺手删除）。 */
    @Override
    public Object get(String key) {
        Path path = path(key);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        long expiration = 0;
        Object value;
        try {
            PhpUnserialize.Result result = PhpUnserialize.parse(Files.readString(path,
                    StandardCharsets.UTF_8));
            if (!(result.value() instanceof Map<?, ?> payload)) {
                return null;
            }
            Object rawExpiration = payload.get("expiration");
            expiration = rawExpiration instanceof Number number ? number.longValue() : 0;
            value = payload.get("value");
        } catch (Exception e) {
            return null;
        }
        if (expiration > 0 && expiration <= nowSeconds()) {
            forget(key);
            return null;
        }

        return value;
    }

    /** 写入；{@code seconds} 语义见 {@link CacheStore}。 */
    @Override
    public void put(String key, Object value, int seconds) {
        write(key, value, seconds);
    }

    /**
     * 自增并返回新值。键不存在（或已过期）时从 {@code amount} 开始，
     * 已存在时保留原过期时间。
     */
    @Override
    public int increment(String key, int amount, int seconds) {
        synchronized (lockFor(key)) {
            long expiration = 0;
            Object current = readRaw(key);
            if (current != null) {
                Map<?, ?> payload = (Map<?, ?>) current;
                Object rawExpiration = payload.get("expiration");
                expiration = rawExpiration instanceof Number number ? number.longValue() : 0;
                Object rawValue = payload.get("value");
                int value = rawValue instanceof Number number ? number.intValue()
                        : Integer.parseInt(String.valueOf(rawValue));
                int next = value + amount;
                writePayload(key, next, expiration);
                return next;
            }
            int next = amount;
            writePayload(key, next, seconds <= 0 ? 0 : nowSeconds() + seconds);
            return next;
        }
    }

    /** 剩余秒数；{@code -1} 表示不存在，{@code 0} 表示永久。 */
    @Override
    public long ttlSeconds(String key) {
        Object payload = readRaw(key);
        if (!(payload instanceof Map<?, ?> map)) {
            return -1;
        }
        Object rawExpiration = map.get("expiration");
        long expiration = rawExpiration instanceof Number number ? number.longValue() : 0;
        if (expiration == 0) {
            return 0;
        }
        long remaining = expiration - nowSeconds();

        return remaining <= 0 ? -1 : remaining;
    }

    @Override
    public void forget(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException ignored) {
            // 与 Laravel 一致：删除失败不抛
        }
    }

    /** 清空缓存目录（{@code Cache::flush()}）。 */
    @Override
    public void flush() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 忽略
                        }
                    });
        } catch (IOException ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 内部

    private Object readRaw(String key) {
        Path path = path(key);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            PhpUnserialize.Result result = PhpUnserialize.parse(Files.readString(path,
                    StandardCharsets.UTF_8));
            if (!(result.value() instanceof Map<?, ?> payload)) {
                return null;
            }
            Object rawExpiration = payload.get("expiration");
            long expiration = rawExpiration instanceof Number number ? number.longValue() : 0;
            if (expiration > 0 && expiration <= nowSeconds()) {
                forget(key);
                return null;
            }

            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    private void write(String key, Object value, int seconds) {
        long expiration;
        if (seconds > 0) {
            expiration = nowSeconds() + seconds;
        } else if (seconds < 0) {
            // Laravel：负 TTL = 已过期（0/null 才是永久）
            expiration = nowSeconds() - 1;
        } else {
            expiration = 0;
        }
        writePayload(key, value, expiration);
    }

    private void writePayload(String key, Object value, long expiration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expiration", expiration);
        payload.put("value", value);
        String serialized = PhpSerialize.serialize(payload);
        Path path = path(key);
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, serialized, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Laravel put() 失败返回 false；这里静默
        }
    }

    private Path path(String key) {
        String hash = sha1(key);

        return directory.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4))
                .resolve(hash);
    }

    private Object lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
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
