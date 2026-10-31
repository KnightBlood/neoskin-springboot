package com.neoskin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.event.EventDispatcher;
import com.neoskin.event.EventRuntime;

/**
 * 真实 jar 的 PF4J 加载：现场编译一个 {@link NeoSkinPlugin} 子类并打包成 jar，
 * 验证 {@code plugins-java/} 下的插件会被启动（{@code start()} 执行）。
 */
class JavaPluginLoadingTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("neoskin.test.plugin.started");
        System.clearProperty("neoskin.test.plugin.event");
        EventRuntime.set(null);
    }

    @Test
    void loadsAndStartsPluginJar() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "需要 JDK（java.compiler）");

        Path src = dir.resolve("src/demo");
        Path classes = dir.resolve("classes");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.writeString(src.resolve("DemoPlugin.java"), """
                package demo;

                import org.pf4j.PluginWrapper;
                import com.neoskin.plugin.NeoSkinPlugin;

                public class DemoPlugin extends NeoSkinPlugin {
                    public DemoPlugin(PluginWrapper wrapper) {
                        super(wrapper);
                    }

                    @Override
                    public void start() {
                        System.setProperty("neoskin.test.plugin.started", "yes");
                    }
                }
                """);
        Files.writeString(src.resolve("DemoExtension.java"), """
                package demo;

                import org.pf4j.Extension;
                import com.neoskin.event.Event;
                import com.neoskin.plugin.EventExtensionPoint;

                @Extension
                public class DemoExtension implements EventExtensionPoint {
                    @Override
                    public void onEvent(Event event) {
                        System.setProperty("neoskin.test.plugin.event",
                                event.getClass().getSimpleName());
                    }
                }
                """);

        String classpath = System.getProperty("java.class.path");
        int result = compiler.run(null, null, null,
                "-classpath", classpath, "-d", classes.toString(),
                src.resolve("DemoPlugin.java").toString(),
                src.resolve("DemoExtension.java").toString());
        assertEquals(0, result, "插件类编译失败");

        Path pluginRoot = dir.resolve("plugins-java");
        Files.createDirectories(pluginRoot);
        String properties = """
                plugin.id=demo
                plugin.class=demo.DemoPlugin
                plugin.version=1.0.0
                plugin.provider=test
                """;
        // 单 jar 插件：plugin.properties 与类都在 jar 内
        Path jar = pluginRoot.resolve("demo-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("demo/DemoPlugin.class"));
            out.write(Files.readAllBytes(classes.resolve("demo/DemoPlugin.class")));
            out.closeEntry();

            out.putNextEntry(new JarEntry("demo/DemoExtension.class"));
            out.write(Files.readAllBytes(classes.resolve("demo/DemoExtension.class")));
            out.closeEntry();

            // PF4J 的扩展索引（注解处理器生成的文件，手写等价）
            out.putNextEntry(new JarEntry("META-INF/extensions.idx"));
            out.write("demo.DemoExtension\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new JarEntry("plugin.properties"));
            out.write(properties.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getPlugins().setJavaDirectory(pluginRoot.toString());
        AppPaths paths = new AppPaths(dir, settings);
        EventRuntime.set(new EventDispatcher());

        JavaPluginManager manager = JavaPluginManager.boot(settings, paths);
        try {
            if (manager.getPlugins().isEmpty()) {
                manager.loadPlugins();
                manager.startPlugins();
            }
            assertEquals(1, manager.getPlugins().size());
            assertEquals("yes", System.getProperty("neoskin.test.plugin.started"));

            EventRuntime.fire(new com.neoskin.event.Events.UserLoggedIn(
                    new com.neoskin.db.UserRepository.UserRow(1, "a@b.c", "n", "x",
                            0, 0, 0, true, false, "")));
            assertEquals("UserLoggedIn", System.getProperty("neoskin.test.plugin.event"),
                    "PF4J 扩展点应收到事件");
        } finally {
            manager.stopPlugins();
            try {
                // 关闭插件类加载器（JarFile），否则 Windows 上 TempDir 删不掉 jar
                manager.unloadPlugins();
            } catch (RuntimeException ignored) {
                // 已停止/未加载时忽略
            }
        }
    }
}
