package com.neoskin.service.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.service.cache.ArrayCache;
import com.neoskin.service.cache.CacheStore;

/**
 * {@link SessionHandlerFactory} 按 {@code SESSION_DRIVER} 选择实现。
 */
class SessionHandlerFactoryTest {

    @TempDir
    Path dir;

    @Test
    void defaultDriverIsFile() throws Exception {
        NeoSkinSettings settings = settings("file");

        assertInstanceOf(FileSessionHandler.class,
                SessionHandlerFactory.create(settings, paths(settings)));
    }

    @Test
    void arrayDriverUsesCacheHandler() throws Exception {
        NeoSkinSettings settings = settings("array");

        assertInstanceOf(CacheBasedSessionHandler.class,
                SessionHandlerFactory.create(settings, paths(settings),
                        new ArrayCache()));
    }

    @Test
    void unsupportedDriverFailsFast() throws Exception {
        NeoSkinSettings settings = settings("database");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SessionHandlerFactory.create(settings, paths(settings)));
    }

    @Test
    void redisDriverWithoutPathsGivesUpGracefully() {
        NeoSkinSettings settings = settings("redis");

        assertNull(SessionHandlerFactory.create(settings, null));
    }

    private NeoSkinSettings settings(String driver) {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(dir.resolve("t.sqlite").toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        settings.getSession().setDriver(driver);

        return settings;
    }

    private AppPaths paths(NeoSkinSettings settings) throws Exception {
        Files.createDirectories(dir.resolve("storage"));

        return new AppPaths(dir, settings);
    }
}
