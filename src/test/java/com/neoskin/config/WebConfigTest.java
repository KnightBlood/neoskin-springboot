package com.neoskin.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.i18n.DbTranslationLoader;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link WebConfig} 的装配测试：确认 {@link HandlerResolver} 真的把三个控制器
 * 串起来了（而不是全部落到 501 兜底），以及 {@code APP_KEY} 的解析。
 */
class WebConfigTest {

    @TempDir
    Path dir;

    @Test
    void resolverCoversEveryPortedController() throws Exception {
        HikariDataSource ds = sqlite();
        try {
            NeoSkinSettings settings = settings();
            AppPaths paths = new AppPaths(dir, settings);
            SchemaInstaller schema = new SchemaInstaller(ds, settings);

            WebConfig config = new WebConfig();
            Option option = new Option(ds, schema, paths);
            DbTranslationLoader db = config.dbTranslationLoader(ds, paths, settings);
            Translator translator = config.translator(
                    config.translationFileLoader(), db, settings);
            JavaScript javaScript = config.javaScript(paths, translator);
            HandlerResolver resolver = config.handlerResolver(
                    option, translator, javaScript, new BlessingFilter(),
                    new LoginFailCounter(), ds, settings, db,
                    config.userRepository(ds), config.playerRepository(ds),
                    new com.neoskin.service.cipher.BcryptCipher(10),
                    config.communications(ds, config.communicationRepository(ds,
                            new SchemaInstaller(ds, settings), settings),
                        config.notificationRepository(ds, new SchemaInstaller(ds, settings),
                            settings),
                        config.communicationRenderer(), settings),
                    config.communicationRepository(ds, new SchemaInstaller(ds, settings), settings),
                    config.notificationRepository(ds, new SchemaInstaller(ds, settings), settings),
                    config.communicationRenderer(), config.textureRepository(ds), paths);

            // 已移植的动作必须解析出 handler
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\HomeController@index"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\HomeController@apiRoot"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\AuthController@login"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TranslationsController@list"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TranslationsController@create"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TranslationsController@update"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TranslationsController@delete"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@index"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@store"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@update"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@withdraw"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@importSource"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@preview"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@inbox"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\CommunicationsController@message"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@user"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@index"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@toggleDarkMode"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@sendVerificationEmail"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@profile"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@handleProfile"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@setAvatar"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@scoreInfo"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UserController@sign"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@json"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@texture"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@raw"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@previewByHash"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@preview"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@avatarByPlayer"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@avatarByUser"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@avatarByHash"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\TextureController@avatarByTexture"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SkinlibController@library"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SkinlibController@info"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SkinlibController@show"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SkinlibController@handleUpload"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@list"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@add"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@delete"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@rename"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@setTexture"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayerController@clearTexture"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@index"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@getClosetData"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@allIds"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@add"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@rename"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetController@remove"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\AdminController@index"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\AdminController@chartData"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\AdminController@status"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@list"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@email"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@verification"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@nickname"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@password"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@score"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@permission"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UsersManagementController@delete"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayersManagementController@list"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayersManagementController@name"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayersManagementController@owner"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayersManagementController@texture"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PlayersManagementController@delete"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\OptionsController@customize"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\OptionsController@options"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\OptionsController@score"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\OptionsController@resource"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\NotificationsController@send"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\NotificationsController@all"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\NotificationsController@read"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ReportController@submit"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ReportController@track"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ReportController@manage"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ReportController@review"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetManagementController@list"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetManagementController@add"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\ClosetManagementController@remove"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UpdateController@showUpdatePage"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UpdateController@download"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UpdateController@upload"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\UpdateController@install"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@config"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@readme"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@manage"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@getPluginData"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@upload"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\PluginController@wget"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\MarketController@marketData"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\MarketController@download"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SetupController@database"));
            assertNotNull(resolver.resolve("App\\Http\\Controllers\\SetupController@finish"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\AccessTokenController@issueToken"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\AuthorizationController@authorize"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ApproveAuthorizationController@approve"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\DenyAuthorizationController@deny"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ClientController@forUser"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ClientController@store"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ClientController@update"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ClientController@destroy"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@forUser"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@store"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@destroy"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\AuthorizedAccessTokenController@forUser"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\AuthorizedAccessTokenController@destroy"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\ScopeController@all"));
            assertNotNull(resolver.resolve("Laravel\\Passport\\Http\\Controllers\\TransientTokenController@refresh"));
            assertNotNull(resolver.resolve("Illuminate\\Routing\\RedirectController"));
            assertNotNull(resolver.resolve("Spatie\\LaravelIgnition\\Http\\Controllers\\HealthCheckController"));
            assertNotNull(resolver.resolve("Spatie\\LaravelIgnition\\Http\\Controllers\\ExecuteSolutionController"));
            assertNotNull(resolver.resolve("Spatie\\LaravelIgnition\\Http\\Controllers\\UpdateConfigController"));

            // 未知动作 → null（路由层据此回 501）
            assertNull(resolver.resolve("App\\Http\\Controllers\\DoesNotExistController@missing"));

            // routes.json 的 174 条路由，action 全部都能解析出 handler
            for (com.neoskin.web.RouteDefinition route : Routes.load().all()) {
                assertTrue(resolver.resolve(route.action(), route.uri()) != null
                                || resolver.resolve(route.action()) != null,
                        route.uri() + " [" + route.action() + "]");
            }

            // 路由表仍是 174 条
            assertNotNull(config.routes());
            org.junit.jupiter.api.Assertions.assertEquals(174, config.routes().count());
        } finally {
            com.neoskin.web.view.PageView.setInboxProvider(null);
            com.neoskin.oauth.OAuthRuntime.set(null);
            ds.close();
        }
    }

    @Test
    void decodesLaravelAppKey() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        String encoded = "base64:" + Base64.getEncoder().encodeToString(raw);

        assertArrayEquals(raw, WebConfig.decodeKey(encoded));
        assertArrayEquals("plain".getBytes(StandardCharsets.UTF_8),
                WebConfig.decodeKey("plain"));
    }

    @Test
    void fileNameOfTranslatorBeanIsStable() {
        // Translator 走 dynamic 构造器：locale 来自 Option.currentLocale()
        Option.clearCurrentLocale();
        Translator translator = new Translator(new TranslationFileLoader(), null,
                "zh_CN", "en", true);
        Option.setCurrentLocale("en");
        try {
            org.junit.jupiter.api.Assertions.assertEquals("en", translator.getLocale());
        } finally {
            Option.clearCurrentLocale();
        }
    }

    // ================================================================ 工具

    private NeoSkinSettings settings() {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(dir.resolve("t.sqlite").toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        return settings;
    }

    private HikariDataSource sqlite() throws Exception {
        Path file = dir.resolve("t.sqlite");
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }
}
