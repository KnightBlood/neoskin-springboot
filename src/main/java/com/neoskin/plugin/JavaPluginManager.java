package com.neoskin.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pf4j.DefaultPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.event.Event;
import com.neoskin.event.EventRuntime;

/**
 * Java 插件管理器（PF4J 3.x）。
 *
 * <p>与 PHP 插件管理（{@code service.PluginManager}，只读 {@code package.json}）
 * 完全分开：</p>
 * <ul>
 *   <li>PHP 插件目录：{@code plugins/}（源语义，不执行）；</li>
 *   <li>Java 插件目录：{@code plugins-java/}（本类，真正加载 jar）。</li>
 * </ul>
 *
 * <p>生命周期：{@code loadPlugins() → startPlugins() → 注册 EventExtensionPoint}。
 * 扩展点监听者会在 {@link #stopPlugins()} 时从 {@code EventDispatcher} 移除。</p>
 *
 * <p>插件的 jar 需要：</p>
 * <ol>
 *   <li>{@code META-INF/MANIFEST.MF} 或 {@code plugin.properties} 里声明
 *       {@code Plugin-Class}（PF4J 约定）；</li>
 *   <li>插件类继承 {@link NeoSkinPlugin}；</li>
 *   <li>可选的 {@link EventExtensionPoint} 实现（{@code @Extension} 由 PF4J 发现，
 *       或注册为扩展）。</li>
 * </ol>
 */
public final class JavaPluginManager extends DefaultPluginManager {

    private static final Logger log = LoggerFactory.getLogger(JavaPluginManager.class);

    private final List<Consumer<Event>> registeredListeners = new ArrayList<>();

    public JavaPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    /**
     * 按配置创建并启动。
     *
     * @return 始终返回实例（禁用或无目录时为空管理器），便于 Spring 管理生命周期
     */
    public static JavaPluginManager boot(NeoSkinSettings settings, AppPaths paths) {
        String dir = settings.getPlugins().getJavaDirectory();
        Path root = dir == null || dir.isBlank()
                ? paths.base().resolve("plugins-java")
                : (Path.of(dir).isAbsolute() ? Path.of(dir) : paths.base().resolve(dir).normalize());
        JavaPluginManager manager = new JavaPluginManager(root);
        if (!settings.getPlugins().isJavaEnabled()) {
            log.info("Java plugin system disabled (PLUGINS_JAVA_ENABLED=false)");

            return manager;
        }
        if (!Files.isDirectory(root)) {
            log.info("Java plugin directory not found, skipping: {}", root);

            return manager;
        }
        try {
            manager.loadPlugins();
            manager.startPlugins();
            manager.registerEventExtensions(manager.getExtensions(EventExtensionPoint.class));
            log.info("Java plugins loaded: {}", manager.getPlugins().size());
        } catch (RuntimeException e) {
            // 单个插件失败不拖垮站点（源会记录并继续）
            log.error("Java plugin boot failed: {}", e.getMessage(), e);
        }

        return manager;
    }

    /** 把扩展点注册为「所有事件」的监听者（测试也可直接调用）。 */
    public void registerEventExtensions(List<EventExtensionPoint> extensions) {
        for (EventExtensionPoint extension : extensions) {
            Consumer<Event> listener = extension::onEvent;
            registeredListeners.add(listener);
            EventRuntime.dispatcher().listenAll(listener);
        }
    }

    @Override
    public void stopPlugins() {
        for (Consumer<Event> listener : registeredListeners) {
            EventRuntime.dispatcher().unlistenAll(listener);
        }
        registeredListeners.clear();
        super.stopPlugins();
    }
}
