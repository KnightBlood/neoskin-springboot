package com.neoskin.storage;

import java.nio.file.Path;

/**
 * 进程级纹理存储单例（与 {@code CacheRuntime} 同模式）。
 *
 * <p>{@code WebConfig} 启动时按 {@code FS_DRIVER} 注入；未注入时（单元测试）
 * 回落到 {@link LocalTextureStorage}。测试结束可用 {@link #set(Object)} 清空。
 * </p>
 */
public final class TextureStorageRuntime {

    private static volatile TextureStorage storage;

    private TextureStorageRuntime() {
    }

    public static void set(TextureStorage value) {
        storage = value;
    }

    /** 当前存储；没有注入时用本地根目录。 */
    public static TextureStorage get(Path localRoot) {
        TextureStorage current = storage;
        if (current instanceof LocalTextureStorage local) {
            // 测试隔离：上一个 Spring 上下文可能设置过另一个临时目录。
            // 本地盘的根与调用方不一致时按调用方路径重建（生产两者一致）。
            if (localRoot != null && !local.root().toAbsolutePath().normalize()
                    .equals(localRoot.toAbsolutePath().normalize())) {
                return new LocalTextureStorage(localRoot);
            }

            return local;
        }
        if (current != null) {
            return current;
        }

        return new LocalTextureStorage(localRoot);
    }

    /** 是否已注入（诊断用）。 */
    public static boolean configured() {
        return storage != null;
    }
}
