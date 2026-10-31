package com.neoskin.service.session;

import java.security.SecureRandom;
import java.time.Instant;

import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

/**
 * Laravel {@code Illuminate\Session\FileSessionHandler} 的移植。
 *
 * <p>本应用 {@code config/session.php} 锁定 {@code driver=file}、
 * {@code lifetime=120} 分钟、{@code serialization=php}（改 json 会把存量用户
 * 全部顶下线，源站的注释也是这么写的，所以 Java 侧同样锁死 php 载荷）。
 *
 * <p>对齐的行为：</p>
 * <ul>
 *   <li>{@code read($id)}：文件存在且 <b>mtime >= now - lifetime</b> 才读，
 *       否则一律 {@code ''}（过期文件留在磁盘上，不删——gc 端另行处理）；</li>
 *   <li>{@code write($id, $data)}：直接覆盖写；</li>
 *   <li>会话 id 校验是 {@code ctype_alnum + 长度 40}（{@link SessionStore#isValidId}），
 *       所以文件名只能是 {@code [A-Za-z0-9]{40}}，无路径注入面；</li>
 *   <li>载荷的 序列化/反序列化由 {@code SessionStore} 负责（这里只搬字节）。</li>
 * </ul>
 */
public final class FileSessionHandler implements SessionHandler {

    /** {@code session.lifetime}（分钟）。 */
    private final int minutes;

    /** 会话文件目录（对应 {@code storage/framework/sessions}）。 */
    private final java.nio.file.Path directory;

    public FileSessionHandler(java.nio.file.Path directory, int minutes) {
        this.directory = directory;
        this.minutes = minutes;
    }

    /**
     * {@code read()}: 未过期才返回内容，否则空串（Laravel 用 '' 表示无数据，
     * 上层 {@code if ($data = ...) } 对这两个都当作「没有」）。
     */
    @Override
    public String read(String id) {
        java.nio.file.Path file = directory.resolve(id);

        if (!java.nio.file.Files.isRegularFile(file)) {
            return "";
        }

        try {
            Instant last = java.nio.file.Files.getLastModifiedTime(file).toInstant();

            if (last.isAfter(Instant.now().minusSeconds((long) minutes * 60))) {
                return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            // 共享读失败按「无数据」处理（Laravel 的 sharedGet 也是尽力而为）。
        }

        return "";
    }

    /** {@code write()}: 覆盖写（父目录已存在由安装流程保证）。 */
    @Override
    public boolean write(String id, String data) {
        try {
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve(id), data,
                    java.nio.charset.StandardCharsets.UTF_8);

            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** {@code destroy()}: 静默删除（不存在也算成功）。 */
    @Override
    public boolean destroy(String id) {
        try {
            java.nio.file.Files.deleteIfExists(directory.resolve(id));

            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** {@link SessionHandler#gc(long)}：文件驱动清理过期会话。 */
    @Override
    public long gc(long lifetimeSeconds) {
        return gcSeconds(lifetimeSeconds);
    }

    // ------------------------------------------------------------------ gc

    /** {@code gc(lifetime)}：删除 mtime 早于阈值的会话文件，返回删除数。 */
    public int gcSeconds(long lifetimeSeconds) {        int removed = 0;
        Instant threshold = Instant.now().minusSeconds(lifetimeSeconds);

        if (!java.nio.file.Files.isDirectory(directory)) {
            return 0;
        }

        try (var files = java.nio.file.Files.list(directory)) {
            for (var file : files.toList()) {
                try {
                    if (file.getFileName().toString().length() == 40
                            && java.nio.file.Files.getLastModifiedTime(file).toInstant()
                                    .isBefore(threshold)
                            && java.nio.file.Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (java.io.IOException e) {
                    // 单个文件失败不中断 gc。
                }
            }
        } catch (java.io.IOException e) {
            // 目录读不动就当作没扫到。
        }

        return removed;
    }
}
