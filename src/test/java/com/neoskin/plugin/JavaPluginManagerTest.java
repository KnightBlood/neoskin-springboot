package com.neoskin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.event.Event;
import com.neoskin.event.EventDispatcher;
import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;

/**
 * PF4J Java 插件 SPI：启动、事件扩展点注册/注销、禁用与空目录。
 */
class JavaPluginManagerTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        EventRuntime.set(null);
    }

    @Test
    void bootsIntoEmptyDirectoryWithoutFailing() throws Exception {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getPlugins().setJavaDirectory(dir.resolve("plugins-java").toString());
        Files.createDirectories(dir.resolve("plugins-java"));
        AppPaths paths = new AppPaths(dir, settings);

        JavaPluginManager manager = JavaPluginManager.boot(settings, paths);
        assertNotNull(manager);
        assertTrue(manager.getPlugins().isEmpty());

        manager.stopPlugins();
    }

    @Test
    void disabledPluginSystemDoesNotLoad() {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getPlugins().setJavaEnabled(false);
        AppPaths paths = new AppPaths(dir, settings);

        JavaPluginManager manager = JavaPluginManager.boot(settings, paths);

        assertTrue(manager.getPlugins().isEmpty());
        manager.stopPlugins();
    }

    @Test
    void eventExtensionReceivesAllEventsUntilStopped() {
        EventRuntime.set(new EventDispatcher());
        JavaPluginManager manager = new JavaPluginManager(dir.resolve("plugins-java"));

        List<Event> received = new ArrayList<>();
        manager.registerEventExtensions(List.of(received::add));

        EventRuntime.fire(new Events.UserLoggedIn(user()));
        EventRuntime.fire(new Events.PluginWasEnabled(null));
        assertEquals(2, received.size());

        manager.stopPlugins();
        EventRuntime.fire(new Events.UserRegistered(user()));
        assertEquals(2, received.size(), "停止后不应再收到事件");
    }

    private static com.neoskin.db.UserRepository.UserRow user() {
        return new com.neoskin.db.UserRepository.UserRow(1, "a@b.c", "nick", "x",
                0, 0, 0, true, false, "");
    }
}
