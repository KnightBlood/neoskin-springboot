package com.neoskin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;

/**
 * 插件钩子门面（源 {@code App\Services\Hook} 的 7 个静态方法）。
 *
 * <p>与源的对应关系：</p>
 * <ul>
 *   <li>{@link #addMenuItem} → 监听 {@code ConfigureUser/Admin/ExploreMenu}
 *       并按位置插入；</li>
 *   <li>{@link #addRoute} → 监听 {@code ConfigureRoutes}；</li>
 *   <li>{@link #addStyleFileToPage} / {@link #addScriptFileToPage}
 *       → {@code BlessingFilter} 的 {@code head_links} / {@code scripts}；</li>
 *   <li>{@link #addUserBadge} → {@code user_badges}；</li>
 *   <li>{@link #sendNotification} → 站内信投递（由平台注入 sink）；</li>
 *   <li>{@link #pushMiddleware} → 记录自定义中间件，供启动装配读取
 *       （Java 的中间件表是构建期固定的，运行期 push 不生效 —— 偏差）。</li>
 * </ul>
 *
 * <p>与源一样是<b>静态门面</b>：插件（未来 PF4J）在 boot 阶段直接调用。</p>
 */
public final class Hook {

    private static final Logger log = LoggerFactory.getLogger(Hook.class);

    /** 全局过滤器总线（源 {@code resolve(Filter::class)} 的单例）。 */
    private static final BlessingFilter FILTER = new BlessingFilter();

    /** 运行期 push 的中间件（启动装配时读取；之后 push 只记录并 WARN）。 */
    private static final List<Object> MIDDLEWARES = Collections.synchronizedList(
            new ArrayList<>());

    /** 站内信投递 sink（{@code WebConfig} 注入；测试可自定义）。 */
    private static volatile NotificationSink notificationSink;

    private Hook() {
    }

    /** 站内信投递接口（{@code users} 可以是 {@code UserRow}、uid 或列表）。 */
    @FunctionalInterface
    public interface NotificationSink {

        void send(Object users, String title, String content);
    }

    /** 当前过滤器总线（渲染层 {@code apply("head_links", ...)} 用）。 */
    public static BlessingFilter filter() {
        return FILTER;
    }

    /** 注入站内信投递实现（{@code WebConfig} 启动时调用）。 */
    public static void setNotificationSink(NotificationSink sink) {
        notificationSink = sink;
    }

    /** 清空所有钩子（测试隔离用）。 */
    public static void reset() {
        FILTER.clear();
        MIDDLEWARES.clear();
        notificationSink = null;
    }

    // ------------------------------------------------------------ 7 个方法

    /**
     * {@code addMenuItem($category, $position, $menu)}。
     *
     * @param category {@code user} / {@code admin} / {@code explore}
     */
    public static void addMenuItem(String category, int position, Map<String, Object> menu) {
        Consumer<Map<String, List<Map<String, Object>>>> listener =
                target -> insertAt(target, category, position, menu);

        switch (category.toLowerCase(java.util.Locale.ROOT)) {
            case "user" -> EventRuntime.dispatcher().listen(Events.ConfigureUserMenu.class,
                    event -> listener.accept(event.menu()));
            case "admin" -> EventRuntime.dispatcher().listen(Events.ConfigureAdminMenu.class,
                    event -> listener.accept(event.menu()));
            case "explore" -> EventRuntime.dispatcher().listen(Events.ConfigureExploreMenu.class,
                    event -> listener.accept(event.menu()));
            default -> log.warn("未知菜单分类：{}（源会拼出不存在的类名而抛错）", category);
        }
    }

    /** {@code addRoute(Closure $callback)}：插件往路由表里注册路由。 */
    public static void addRoute(Consumer<Object> callback) {
        EventRuntime.dispatcher().listen(Events.ConfigureRoutes.class,
                event -> callback.accept(event.router()));
    }

    /**
     * {@code addStyleFileToPage($urls, $pages = ['*'])}。
     *
     * <p>过滤器接收 {@code (links, [currentPath])}；页面匹配用 Ant 风格
     * {@code *} 通配（源是 {@code request()->is()}）。</p>
     */
    public static void addStyleFileToPage(List<String> urls, List<String> pages) {
        FILTER.add("head_links", (carry, args) -> {
            String path = args.isEmpty() ? "/" : String.valueOf(args.get(0));
            if (!matchesAny(path, pages)) {
                return carry;
            }
            List<Map<String, Object>> links = asList(carry);
            for (String url : urls) {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("rel", "stylesheet");
                link.put("href", url);
                link.put("crossorigin", "anonymous");
                links.add(link);
            }

            return links;
        });
    }

    /** {@code addScriptFileToPage($urls, $pages = ['*'])}。 */
    public static void addScriptFileToPage(List<String> urls, List<String> pages) {
        FILTER.add("scripts", (carry, args) -> {
            String path = args.isEmpty() ? "/" : String.valueOf(args.get(0));
            if (!matchesAny(path, pages)) {
                return carry;
            }
            List<Map<String, Object>> scripts = asList(carry);
            for (String url : urls) {
                Map<String, Object> script = new LinkedHashMap<>();
                script.put("src", url);
                script.put("crossorigin", "anonymous");
                scripts.add(script);
            }

            return scripts;
        });
    }

    /** {@code addUserBadge($text, $color = 'primary')}（源标记为 deprecated，仍保留）。 */
    public static void addUserBadge(String text, String color) {
        FILTER.add("user_badges", (carry, args) -> {
            List<Map<String, Object>> badges = asList(carry);
            Map<String, Object> badge = new LinkedHashMap<>();
            badge.put("text", text);
            badge.put("color", color == null || color.isBlank() ? "primary" : color);
            badges.add(badge);

            return badges;
        });
    }

    /** {@code sendNotification($users, $title, $content = '')}。 */
    public static void sendNotification(Object users, String title, String content) {
        NotificationSink sink = notificationSink;
        if (sink == null) {
            log.warn("sendNotification 被调用但未注入 NotificationSink（无站内信后端）");

            return;
        }
        sink.send(users, title, content == null ? "" : content);
    }

    /**
     * {@code pushMiddleware($middleware)}：记录供启动期装配。
     *
     * <p>Java 的中间件表在 {@code WebConfig} 构建时固定；运行期 push 不生效
     * （与源「插件 boot 时 push」等价的前提是插件在启动阶段注册）。</p>
     */
    public static void pushMiddleware(Object middleware) {
        MIDDLEWARES.add(middleware);
    }

    /** 已 push 的中间件（启动装配读取；之后为只读诊断视图）。 */
    public static List<Object> middlewares() {
        synchronized (MIDDLEWARES) {
            return List.copyOf(MIDDLEWARES);
        }
    }

    // ------------------------------------------------------------ 内部

    @SuppressWarnings("unchecked")
    private static void insertAt(Map<String, List<Map<String, Object>>> menu, String category,
            int position, Map<String, Object> item) {
        List<Map<String, Object>> items = menu.computeIfAbsent(category,
                key -> new ArrayList<>());
        List<Map<String, Object>> result = new ArrayList<>();
        int offset = 0;
        for (Map<String, Object> existing : items) {
            if (offset == position) {
                result.add(item);
            }
            result.add(existing);
            offset++;
        }
        if (position >= offset) {
            result.add(item);
        }
        menu.put(category, result);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object carry) {
        if (carry instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new ArrayList<>();
    }

    /** 源的 {@code request()->is($pages)} 子集：支持 {@code *} 通配（忽略前导 {@code /}）。 */
    static boolean matchesAny(String path, List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return false;
        }
        String normalizedPath = path == null ? "" : path.replaceFirst("^/+", "");
        for (String page : pages) {
            if ("*".equals(page)) {
                return true;
            }
            String pattern = page.replaceFirst("^/+", "");
            String regex = pattern.replace(".", "\\.")
                    .replace("**", "\u0000")
                    .replace("*", ".*")
                    .replace("\u0000", ".*");
            if (normalizedPath.matches(regex)) {
                return true;
            }
        }

        return false;
    }
}
