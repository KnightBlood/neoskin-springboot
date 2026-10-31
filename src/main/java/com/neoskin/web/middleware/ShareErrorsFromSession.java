package com.neoskin.web.middleware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpUnserialize;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Illuminate\View\Middleware\ShareErrorsFromSession} 的移植（web 组第 4 个）。
 *
 * <pre>
 * $this->view->share('errors', $request->session()->get('errors') ?: new ViewErrorBag);
 * return $next($request);
 * </pre>
 *
 * <p>Twig 侧是「共享视图变量」，模板里直接写 {@code errors.any} / {@code errors.first}。
 * 本工程没有全局视图工厂，所以把解出来的错误包挂到 {@code ctx} 上，控制器渲染时取用
 * —— 语义等价：无论有没有错误，模板里 {@code errors} 总是存在的（空包）。</p>
 *
 * <p>会话里 {@code errors} 的结构是 PHP 序列化后的
 * {@code Illuminate\Support\ViewErrorBag}（见
 * {@code ValidationException} 的 {@code withErrors} 路径）：
 * {@code \0*\0bags['default']} → {@code MessageBag}，其
 * {@code \0*\0messages} 是 {@code 字段名 → 消息数组}。</p>
 *
 * <p>值是<b>不可变快照</b>：这一步只在请求开始时读一次，与源一致（源也是取值后共享）。</p>
 */
public final class ShareErrorsFromSession implements Handler<RoutingContext> {

    /** {@code ctx} 里 {@code errors} 的键。 */
    public static final String CTX_KEY = "errors";

    /** 私有属性名的前缀（PHP 的 protected 属性在 serialize 里是 {@code \0*\0name}）。 */
    private static final String BAGS = "\0*\0bags";

    private static final String MESSAGES = "\0*\0messages";

    @Override
    public void handle(RoutingContext ctx) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        ctx.put(CTX_KEY, extract(session == null ? null : session.get("errors")));
        ctx.next();
    }

    /**
     * {@code ViewErrorBag → Map<字段, 消息列表>}；非预期结构（缺失 /
     * 未序列化对象 / 空包）一律得到空 map，对应 Twig 里 {@code errors.any == false}。
     */
    public static Map<String, List<String>> extract(Object errors) {
        Map<String, List<String>> out = new LinkedHashMap<>();

        if (!(errors instanceof PhpUnserialize.PhpObject bag)) {
            return out;
        }

        Object bags = bag.properties().get(BAGS);

        if (!(bags instanceof PhpJson.Array bagsArray)) {
            return out;
        }

        Object def = bagsArray.get("default");

        if (!(def instanceof PhpUnserialize.PhpObject messageBag)) {
            return out;
        }

        Object messages = messageBag.properties().get(MESSAGES);

        if (!(messages instanceof PhpJson.Array messagesArray)) {
            return out;
        }

        messagesArray.forEach((key, value) -> {
            List<String> list = new ArrayList<>();

            if (value instanceof PhpJson.Array array) {
                for (Object item : array.values()) {
                    list.add(String.valueOf(item));
                }
            } else if (value != null) {
                list.add(String.valueOf(value));
            }

            out.put(String.valueOf(key), list);
        });

        return out;
    }
}
