package com.neoskin.event.listener;

import java.util.List;

import com.neoskin.event.Events;
import com.neoskin.service.Plugin;

/**
 * {@code PluginBootFailed} 监听者：给管理员页面弹一条失败提示
 * （源 {@code App\Listeners\NotifyFailedPlugin}，通过
 * {@code RenderingHeader::addContent()} 注入 script）。
 *
 * <p>本工程不执行 PHP 插件，因此正常路径不会触发；仍保留监听者供
 * Java 插件 SPI（PF4J）接入后使用。</p>
 */
public final class NotifyFailedPlugin {

    /** 把 toast 脚本追加到页面头部内容里。 */
    public void handle(Plugin plugin, List<String> headerContents, String message) {
        String escaped = message.replace("'", "\\'");
        headerContents.add("<script>blessing.notify.toast.error('" + escaped + "')</script>");
    }

    /** 便捷重载：直接挂到 {@link Events.RenderingHeader} 上。 */
    public void handle(Plugin plugin, Events.RenderingHeader event, String message) {
        handle(plugin, event.contents(), message);
    }
}
