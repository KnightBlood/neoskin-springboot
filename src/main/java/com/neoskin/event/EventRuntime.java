package com.neoskin.event;

/**
 * 进程级事件调度器单例（与 {@code CacheRuntime} / {@code OAuthRuntime} 同模式）。
 *
 * <p>控制器/服务层拿不到 Spring 注入，用静态入口派发事件；默认注册内置监听者
 * （{@code EventRuntime} 的静态初始化），测试与生产行为一致。需要自定义时
 * 可 {@link #set(EventDispatcher)}。</p>
 */
public final class EventRuntime {

    private static volatile EventDispatcher dispatcher = bootstrap();

    private EventRuntime() {
    }

    private static EventDispatcher bootstrap() {
        EventDispatcher created = new EventDispatcher();
        BuiltInListeners.register(created);

        return created;
    }

    public static EventDispatcher dispatcher() {
        return dispatcher;
    }

    /** 替换调度器（测试隔离/自定义）。传 {@code null} 表示重置为内置默认。 */
    public static void set(EventDispatcher value) {
        dispatcher = value == null ? bootstrap() : value;
    }

    /** 便捷派发：类事件。 */
    public static <E extends Event> E fire(E event) {
        return dispatcher().dispatch(event);
    }

    /** 便捷派发：字符串事件。 */
    public static void fire(String name, Object... payload) {
        dispatcher().dispatch(name, payload);
    }
}
