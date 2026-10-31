package com.neoskin.plugin;

import com.neoskin.event.Event;

/**
 * PF4J 扩展点：感兴趣的 Java 插件实现此接口，就会收到全部 {@link Event}
 *（与源里 {@code Event::listen} 的插件用法等价，但只覆盖类事件；
 * 字符串事件仍通过 {@code EventRuntime.fire("name", ...)} 提供）。
 */
public interface EventExtensionPoint {

    /** 事件到达（同步调用；抛异常会让请求失败，与源一致）。 */
    void onEvent(Event event);
}
