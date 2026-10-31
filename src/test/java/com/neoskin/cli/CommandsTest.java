package com.neoskin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link Commands} 的命令级测试（不启动 Spring Boot / HTTP）。
 *
 * <p>用 {@link AnnotationConfigApplicationContext} 手工注册同一批 Bean，
 * 覆盖 {@code install} / 幂等 / {@code update} / {@code options:cache}。</p>
 */
class CommandsTest {

    @TempDir
    Path dir;

    private AnnotationConfigApplicationContext context;
    private HikariDataSource ds;
    private NeoSkinSettings settings;
    private AppPaths paths;
    private Option option;
    private UserRepository users;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("cli.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);

        settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        settings.setKey("base64:NeoSkinTestKeyNeoSkinTestKey=");

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        option = new Option(ds, schema, paths);
        users = new UserRepository(ds);
        Cipher cipher = new BcryptCipher(10);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");

        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("settings", settings);
        context.getBeanFactory().registerSingleton("paths", paths);
        context.getBeanFactory().registerSingleton("schema", schema);
        context.getBeanFactory().registerSingleton("option", option);
        context.getBeanFactory().registerSingleton("users", users);
        context.getBeanFactory().registerSingleton("cipher", cipher);
        context.getBeanFactory().registerSingleton("translator", translator);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
        ds.close();
    }

    @Test
    void installCreatesSchemaAdminAndLockThenIsIdempotent() throws Exception {
        String[] args = {"--email=admin@example.com", "--password=secret123",
                "--nickname=Admin"};

        assertEquals(0, Commands.run(context, "install", args));
        assertTrue(option.isInstalled());
        UserRepository.UserRow admin = users.findByEmail("admin@example.com");
        assertNotNull(admin);
        assertEquals(UserRepository.SUPER_ADMIN, admin.permission());
        assertEquals("http://localhost", option.get("site_url"));

        // 第二次直接返回，不重复建号
        assertEquals(0, Commands.run(context, "install", args));
        assertEquals(1, users.countByIp("127.0.0.1"));
    }

    @Test
    void installRequiresArguments() {
        assertEquals(1, Commands.run(context, "install", new String[] {"--email=a@b.com"}));
        assertTrue(Files.notExists(paths.installLock()));
    }

    @Test
    void updateWritesVersionAndLock() {
        assertEquals(0, Commands.run(context, "update", new String[] {}));
        assertEquals(settings.getVersion(), option.get("version"));
        assertTrue(Files.isRegularFile(paths.installLock()));
    }

    @Test
    void optionsCacheWritesPhpCacheFile() throws Exception {
        option.set("site_name", "NeoSkin");

        assertEquals(0, Commands.run(context, "options:cache", new String[] {}));
        Path cache = paths.storage("options.php");
        assertTrue(Files.isRegularFile(cache));
        assertTrue(Files.readString(cache).contains("'site_name'"));
    }
}
