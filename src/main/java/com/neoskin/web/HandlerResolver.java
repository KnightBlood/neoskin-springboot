package com.neoskin.web;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * action ↔ 处理器 的解析接口。
 *
 * <p>源项目的 action 是 {@code HomeController@index} 字符串；Vert.x 的路由
 * 终点必须是 handler。控制器尚未移植的 action 由注册器兜底成 HTTP 501。
 * 移植控制器时实现本接口并把实例交给
 * {@link VertxRouteRegistry#build(Routes, HandlerResolver, io.vertx.core.Vertx)}。</p>
 *
 * <p>个别 action 字符串不足以定位处理器 —— 例如 {@code Illuminate\Routing\ViewController}
 * 被 11 条路由共用（{@code Route::view('bind', 'auth.bind')} 之类），
 * 只有结合 uri 才知道渲染哪个模板。因此提供
 * {@link #resolve(String, String)} 重载，默认退化为 {@link #resolve(String)}。</p>
 */
public interface HandlerResolver {

    /**
     * action → handler；未移植的 action 返回 {@code null}（由注册器兜底）。
     *
     * @param action 源 Laravel action 字符串，如 {@code HomeController@index}
     */
    Handler<RoutingContext> resolve(String action);

    /**
     * action + uri → handler。
     *
     * <p>默认实现忽略 uri，直接委托 {@link #resolve(String)}；需要按路径分流的
     * 解析器（{@code ViewController}）覆盖本方法。</p>
     *
     * @param action 源 Laravel action 字符串
     * @param uri    源 Laravel 路由 uri（不含前导斜杠），如 {@code auth/bind}
     */
    default Handler<RoutingContext> resolve(String action, String uri) {
        return resolve(action);
    }
}
