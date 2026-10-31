package com.neoskin.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * Java 插件基类（PF4J）。
 *
 * <p>插件在 {@link #start()} 里通过静态门面注册能力：</p>
 *
 * <pre>
 * public final class DemoPlugin extends NeoSkinPlugin {
 *     public DemoPlugin(PluginWrapper wrapper) { super(wrapper); }
 *
 *     &#64;Override
 *     public void start() {
 *         Hook.addMenuItem("user", 2, Map.of("title", "Demo", "link", "demo"));
 *     }
 * }
 * </pre>
 *
 * <p>PHP 插件仍然只做安装/启用/禁用（无 PHP 运行时，见 PORT-NOTES §32.2）；
 * Java 插件是另一条独立的执行路径：由 {@link JavaPluginManager} 用 PF4J
 * 加载 {@code plugins-java/} 下的 jar。</p>
 */
public abstract class NeoSkinPlugin extends Plugin {

    protected NeoSkinPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}
