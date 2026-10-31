package com.neoskin.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 同步事件调度器（Laravel {@code Dispatcher} 的最小实现）。
 *
 * <p>支持两种监听形式：</p>
 * <ul>
 *   <li>类事件：{@link #listen(Class, Consumer)} + {@link #dispatch(Event)}；</li>
 *   <li>字符串事件：{@link #listen(String, Consumer)} + {@link #dispatch(String, Object...)}
 *       —— 对应源里 {@code texture.deleted} / {@code auth.registration.completed}
 *       这类字符串事件。</li>
 * </ul>
 *
 * <p>监听者按注册顺序同步执行（Laravel 默认也是同步）。监听者异常直接向上抛，
 * 与源一致。</p>
 */
public final class EventDispatcher {

    private final Map<Class<? extends Event>, List<Consumer<Event>>> classListeners =
            new ConcurrentHashMap<>();

    private final Map<String, List<Consumer<Object[]>>> stringListeners =
            new ConcurrentHashMap<>();

    /** 「任意事件」监听者（PF4J 插件扩展点用）。 */
    private final List<Consumer<Event>> allListeners = new CopyOnWriteArrayList<>();

    /** 注册类事件监听者。 */
    public <E extends Event> void listen(Class<E> type, Consumer<E> listener) {
        classListeners.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>())
                .add(event -> listener.accept(type.cast(event)));
    }

    /** 注册字符串事件监听者（参数按派发顺序传入）。 */
    public void listen(String name, Consumer<Object[]> listener) {
        stringListeners.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    /** 派发类事件，返回原事件（监听者可修改事件里的可变集合）。 */
    public <E extends Event> E dispatch(E event) {
        List<Consumer<Event>> registered = classListeners.get(event.getClass());
        if (registered != null) {
            for (Consumer<Event> listener : registered) {
                listener.accept(event);
            }
        }
        for (Consumer<Event> listener : allListeners) {
            listener.accept(event);
        }

        return event;
    }

    /** 监听所有类事件（PF4J 插件扩展点）。 */
    public void listenAll(Consumer<Event> listener) {
        allListeners.add(listener);
    }

    /** 取消「任意事件」监听者。 */
    public void unlistenAll(Consumer<Event> listener) {
        allListeners.remove(listener);
    }

    /** 派发字符串事件。 */
    public void dispatch(String name, Object... payload) {
        List<Consumer<Object[]>> registered = stringListeners.get(name);
        if (registered != null) {
            for (Consumer<Object[]> listener : registered) {
                listener.accept(payload);
            }
        }
    }

    /** 某个事件（类或字符串）的监听者数量（测试/诊断用）。 */
    public int count(Object key) {
        if (key instanceof Class<?>) {
            List<Consumer<Event>> registered = classListeners.get(key);

            return registered == null ? 0 : registered.size();
        }
        List<Consumer<Object[]>> registered = stringListeners.get(String.valueOf(key));

        return registered == null ? 0 : registered.size();
    }

    /** 清空全部监听者（测试隔离用）。 */
    public void clear() {
        classListeners.clear();
        stringListeners.clear();
        allListeners.clear();
    }
}
