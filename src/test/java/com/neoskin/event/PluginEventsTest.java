package com.neoskin.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.Option;
import com.neoskin.service.PluginManager;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 插件生命周期事件：enable/disable/delete 分别派发
 * {@code PluginWasEnabled/Disabled/Deleted}（源 {@code PluginManager}）。
 */
class PluginEventsTest {

    @TempDir
    Path dir;

    private HikariDataSource dataSource;

    @AfterEach
    void reset() {
        if (dataSource != null) {
            dataSource.close();
        }
        EventRuntime.set(null);
    }

    @Test
    void enableDisableDeleteDispatchLifecycleEvents() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dir.resolve("t.sqlite").toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        Files.createDirectories(dir.resolve("storage"));
        AppPaths paths = new AppPaths(dir, settings);
        SchemaInstaller schema = new SchemaInstaller(dataSource, settings);
        schema.install();
        Option option = new Option(dataSource, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");

        Path pluginsDir = dir.resolve("plugins");
        Files.createDirectories(pluginsDir.resolve("demo"));
        Files.writeString(pluginsDir.resolve("demo/package.json"),
                "{\"name\":\"demo\",\"title\":\"Demo\",\"version\":\"1.0.0\"}");
        settings.getPlugins().setDirectory(pluginsDir.toString());

        PluginManager manager = new PluginManager(option, translator, settings, paths);
        List<String> fired = new ArrayList<>();
        EventRuntime.dispatcher().listen(Events.PluginWasEnabled.class,
                event -> fired.add("enabled:" + event.plugin().name()));
        EventRuntime.dispatcher().listen(Events.PluginWasDisabled.class,
                event -> fired.add("disabled:" + event.plugin().name()));
        EventRuntime.dispatcher().listen(Events.PluginWasDeleted.class,
                event -> fired.add("deleted:" + event.plugin().name()));

        assertNotNull(manager.get("demo"));
        assertNull(manager.enable("demo"));
        manager.disable("demo");
        manager.delete("demo");

        assertEquals(List.of("enabled:demo", "disabled:demo", "deleted:demo"), fired);
    }
}
