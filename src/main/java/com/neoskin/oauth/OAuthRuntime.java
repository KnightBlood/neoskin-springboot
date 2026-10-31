package com.neoskin.oauth;

/**
 * OAuth 服务的组合根单例（与 PageView.unread provider 相同的装配方式）。
 *
 * <p>{@code Authenticate}/{@code scope:*} 中间件位于 MiddlewareChain 的固定注册表里，
 * 无法通过构造参数拿到每请求服务，因此由 WebConfig 启动时注入一次。</p>
 */
public final class OAuthRuntime {

    private static volatile OAuthService service;

    private OAuthRuntime() {
    }

    public static void set(OAuthService value) {
        service = value;
    }

    public static OAuthService service() {
        return service;
    }
}
