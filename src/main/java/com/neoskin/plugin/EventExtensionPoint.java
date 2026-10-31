package com.neoskin.plugin;

import com.neoskin.event.Event;

/**
 * PF4J 扩展点：感兴趣的 Java 插件实现此接口，就会收到全部 {@link Event}
 *（与源里 {@code Event::listen} 的插件用法等价，但只覆盖类事件；
 * 字符串事件仍通过 {@code EventRuntime.fire("name", ...)} 提供）。
 *
 * <p>必须继承 {@link org.pf4j.ExtensionPoint}：PF4J 的注解处理器（{@code @Extension}）
 * 会把「不实现 ExtensionPoint」当作编译错误，而 {@code getExtensions()} 也按
 * 扩展点类型过滤。</p>
 */
public interface EventExtensionPoint extends org.pf4j.ExtensionPoint {

    /** 事件到达（同步调用；抛异常会让请求失败，与源一致）。 */
    void onEvent(Event event);
}
