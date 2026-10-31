package com.neoskin.service.cache;

/**
 * 缓存存储的进程级单例（与 {@code OAuthRuntime} 同模式）。
 *
 * <p>{@code MiddlewareChain} 的限流中间件拿不到 Spring 注入，{@code WebConfig}
 * 启动时把 {@link CacheStore} 放进来；没有设置时（单元测试）退回
 * {@link FileCache} / {@link ArrayCache}。</p>
 */
public final class CacheRuntime {

    private static volatile CacheStore store;

    private CacheRuntime() {
    }

    public static void set(CacheStore value) {
        store = value;
    }

    public static CacheStore store() {
        return store;
    }
}
