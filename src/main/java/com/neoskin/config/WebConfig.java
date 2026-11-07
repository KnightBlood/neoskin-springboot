package com.neoskin.config;

import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxHttpServer;
import com.neoskin.web.controllers.AuthController;
import com.neoskin.web.controllers.AdminController;
import com.neoskin.web.controllers.ClosetManagementController;
import com.neoskin.web.controllers.CommunicationsController;
import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.ClosetRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.service.CommunicationRenderer;
import com.neoskin.service.Communications;
import com.neoskin.web.controllers.HomeController;
import com.neoskin.web.controllers.UserController;
import com.neoskin.web.controllers.TextureController;
import com.neoskin.web.controllers.SkinlibController;
import com.neoskin.web.controllers.PlayerController;
import com.neoskin.web.controllers.ClosetController;
import com.neoskin.web.controllers.TranslationsController;
import com.neoskin.web.controllers.UpdateController;
import com.neoskin.web.controllers.UsersManagementController;
import com.neoskin.web.controllers.PlayersManagementController;
import com.neoskin.web.controllers.MarketController;
import com.neoskin.web.controllers.OptionsController;
import com.neoskin.web.controllers.PluginController;
import com.neoskin.web.controllers.SetupController;
import com.neoskin.web.controllers.NotificationsController;
import com.neoskin.web.controllers.IgnitionController;
import com.neoskin.web.controllers.PassportApiController;
import com.neoskin.web.controllers.PassportOAuthController;
import com.neoskin.web.controllers.RedirectController;
import com.neoskin.web.controllers.ReportController;
import com.neoskin.web.controllers.ViewController;
import com.neoskin.web.i18n.DbTranslationLoader;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.LanguageMiddleware;
import com.neoskin.web.middleware.MiddlewareChain;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Web 层装配：把散装的控制器、中间件、模板与 Vert.x 服务器串起来。
 *
 * <p>源项目靠 Laravel 的 ServiceProvider + 路由文件自动装配；本工程没有那套
 * 约定，因此这里显式声明每个 Bean，并把 {@link HandlerResolver} 组合成
 * 「一个 action 字符串 → 一个 handler」的总入口。</p>
 *
 * <h2>启动顺序</h2>
 * <ol>
 *   <li>{@code ApplicationReadyEvent} → {@link VertxHttpServer#startup()}；</li>
 *   <li>{@code ContextClosedEvent} → 优雅停机。</li>
 * </ol>
 *
 * <p>之所以不用 {@code @PostConstruct}：Spring 容器必须先把 DataSource /
 * JPA 初始化完，否则第一批请求会撞上半成品。</p>
 */
@Configuration
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    // ============================================================ 基础服务

    /** {@code codegraph/routes.json}（173 条，唯一权威）。 */
    @Bean
    public Routes routes() {
        return Routes.load();
    }

    /** {@code Blessing\Filter}：插件钩子总线（PF4J 未接时监听者为空）。 */
    @Bean
    public BlessingFilter blessingFilter() {
        return new BlessingFilter();
    }

    /** 登录失败计数（进程内，口径同 6.x 的缓存偏差）。 */
    @Bean
    public LoginFailCounter loginFailCounter(com.neoskin.service.cache.CacheStore cache) {
        return new LoginFailCounter(cache);
    }

    /**
     * 缓存存储（{@code CACHE_DRIVER}）：file / redis / array。
     * 同时登记进程级单例，供 {@code MiddlewareChain} 的限流器使用。
     */
    @Bean
    public com.neoskin.service.cache.CacheStore cacheStore(NeoSkinSettings settings,
            AppPaths paths) {
        com.neoskin.service.cache.CacheStore store =
                com.neoskin.service.cache.CacheFactory.create(settings, paths);
        com.neoskin.service.cache.CacheRuntime.set(store);

        return store;
    }

    // ============================================================ 国际化

    @Bean
    public com.neoskin.db.UserRepository userRepository(DataSource dataSource) {
        return new com.neoskin.db.UserRepository(dataSource);
    }

    @Bean
    public com.neoskin.db.PlayerRepository playerRepository(DataSource dataSource) {
        return new com.neoskin.db.PlayerRepository(dataSource);
    }

    @Bean
    public com.neoskin.db.SchemaInstaller schemaInstaller(DataSource dataSource,
            NeoSkinSettings settings) {
        return new com.neoskin.db.SchemaInstaller(dataSource, settings);
    }

    @Bean
    public CommunicationRepository communicationRepository(DataSource dataSource,
            com.neoskin.db.SchemaInstaller schema, NeoSkinSettings settings) {
        return new CommunicationRepository(dataSource, schema, settings.getTimezone());
    }

    @Bean
    public NotificationRepository notificationRepository(DataSource dataSource,
            com.neoskin.db.SchemaInstaller schema, NeoSkinSettings settings) {
        return new NotificationRepository(dataSource, schema, settings.getTimezone());
    }

    @Bean
    public CommunicationRenderer communicationRenderer() {
        return new CommunicationRenderer();
    }

    @Bean
    public com.neoskin.db.TextureRepository textureRepository(DataSource dataSource,
            NeoSkinSettings settings) {
        return new com.neoskin.db.TextureRepository(dataSource,
                com.neoskin.db.SqlDialect.of(settings.getDatabase().getConnection()));
    }

    @Bean
    public Communications communications(DataSource dataSource,
            CommunicationRepository communications, NotificationRepository notifications,
            CommunicationRenderer renderer, NeoSkinSettings settings) {
        return new Communications(dataSource, communications, notifications, renderer,
                settings.getTimezone());
    }

    @Bean
    public TranslationFileLoader translationFileLoader() {
        return new TranslationFileLoader();
    }

    @Bean
    public DbTranslationLoader dbTranslationLoader(DataSource dataSource, AppPaths paths,
            NeoSkinSettings settings) {
        return new DbTranslationLoader(dataSource, paths, settings.getFallbackLocale());
    }

    /**
     * {@code Translator}：单例 + 动态语言来源。
     *
     * <p>语言是按请求由 {@code DetectLanguagePrefer} 写进 ThreadLocal 的
     * （{@link Option#currentLocale()}），所以这里用
     * {@code dynamic = true} 的构造器，避免并发请求串语言。</p>
     */
    @Bean
    public Translator translator(TranslationFileLoader fileLoader,
            DbTranslationLoader dbLoader, NeoSkinSettings settings) {
        return new Translator(fileLoader, dbLoader,
                com.neoskin.web.i18n.Locales.APP_LOCALE, settings.getFallbackLocale(), true);
    }

    /**
     * {@code JavaScript}：前端语言包编译服务。
     *
     * <p>{@code PluginLangProvider} 传 {@code null} → 视为「没有插件」，
     * 只编译主语言包（偏差 6.1，PF4J 接好后替换）。</p>
     */
    @Bean
    public JavaScript javaScript(AppPaths paths, Translator translator) {
        return new JavaScript(paths, translator, null);
    }

    // ============================================================ 控制器

    /**
     * 总 {@link HandlerResolver}：把各控制器的 resolver 串成一条链。
     *
     * <p>{@code debug} 取自 {@code NeoSkinSettings.isDebug()}，决定模板里的资源
     * 是指向生产哈希还是 {@code //localhost:8080/app/}（前端 dev-server，
     * 见 PORT-NOTES §9.1 / §9.5）。</p>
     */
    @Bean
    public HandlerResolver handlerResolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, LoginFailCounter failures,
            DataSource dataSource, NeoSkinSettings settings, DbTranslationLoader dbLoader,
            com.neoskin.db.UserRepository userRepository,
            com.neoskin.db.PlayerRepository playerRepository,
            com.neoskin.service.cipher.Cipher cipher,
            Communications communications, CommunicationRepository communicationRepository,
            NotificationRepository notificationRepository,
            CommunicationRenderer communicationRenderer,
            com.neoskin.db.TextureRepository textureRepository, AppPaths paths) {

        boolean debug = settings.isDebug();
        String timezone = settings.getTimezone();

        HandlerResolver home = HomeController.resolver(option, translator, javaScript,
                null, filter, debug);
        HandlerResolver auth = AuthController.resolver(
                option, translator, javaScript, filter, failures,
                userRepository, playerRepository, cipher,
                settings.getSecure().getSalt(), debug,
                dataSource, settings);
        HandlerResolver translations = TranslationsController.resolver(
                dataSource, translator, timezone, dbLoader::invalidateGroup, javaScript);

        HandlerResolver communicationsResolver = CommunicationsController.resolver(option, translator,
                javaScript, filter, userRepository, debug, dataSource, communications,
                communicationRepository, notificationRepository, communicationRenderer, timezone);
        HandlerResolver userResolver = UserController.resolver(option, translator, userRepository,
                playerRepository, dataSource, timezone, cipher, settings.getSecure().getSalt(),
                javaScript, filter, debug, communications, settings);
        com.neoskin.service.ImageCache imageCache = new com.neoskin.service.ImageCache();
        HandlerResolver textureResolver = TextureController.resolver(option, translator,
                playerRepository, textureRepository, userRepository, paths, timezone,
                imageCache);
        com.neoskin.db.ClosetRepository closetRepository =
                new com.neoskin.db.ClosetRepository(dataSource);
        HandlerResolver skinlibResolver = SkinlibController.resolver(option, translator,
                textureRepository, userRepository, javaScript, filter, debug, paths,
                closetRepository, timezone, dataSource,
                () -> com.neoskin.support.PhpDate.now(timezone));
        HandlerResolver playerResolver = PlayerController.resolver(option, translator,
                playerRepository, textureRepository, userRepository, dataSource, timezone,
                javaScript, filter, debug);
        HandlerResolver closetResolver = ClosetController.resolver(option, translator,
                closetRepository, textureRepository, userRepository, dataSource, timezone,
                javaScript, filter, debug);
        HandlerResolver adminResolver = AdminController.resolver(option, translator,
                javaScript, filter, userRepository, playerRepository, textureRepository, debug,
                paths, settings, timezone);
        HandlerResolver usersManagementResolver = UsersManagementController.resolver(option,
                translator, userRepository, cipher, settings.getSecure().getSalt(), dataSource);
        HandlerResolver playersManagementResolver = PlayersManagementController.resolver(option,
                translator, playerRepository, userRepository, textureRepository, dataSource,
                timezone);
        HandlerResolver viewResolver = ViewController.resolver(option, translator, javaScript,
                filter, userRepository, debug, settings.getVersion());
        HandlerResolver optionsResolver = OptionsController.resolver(option, translator,
                javaScript, filter, userRepository, debug, settings, paths, imageCache);
        HandlerResolver notificationsResolver = NotificationsController.resolver(option,
                translator, userRepository, communicationRepository, notificationRepository,
                communications, dataSource, timezone);
        com.neoskin.db.ReportRepository reportRepository = new com.neoskin.db.ReportRepository(
                dataSource, userRepository, textureRepository);
        HandlerResolver reportResolver = ReportController.resolver(option, translator,
                userRepository, textureRepository, reportRepository, paths, dataSource,
                javaScript, filter, debug, timezone);
        HandlerResolver closetManagementResolver = ClosetManagementController.resolver(option,
                translator, userRepository, textureRepository, closetRepository);
        HandlerResolver updateResolver = UpdateController.resolver(option, translator,
                userRepository, settings, paths, javaScript, filter, debug);
        com.neoskin.service.PluginManager pluginManager = new com.neoskin.service.PluginManager(
                option, translator, settings, paths);
        registerEventListeners(paths, pluginManager, notificationRepository, timezone);
        HandlerResolver pluginResolver = PluginController.resolver(option, translator,
                pluginManager, paths, javaScript, filter, userRepository, debug);
        HandlerResolver marketResolver = MarketController.resolver(option, translator,
                pluginManager, settings);
        HandlerResolver setupResolver = SetupController.resolver(option, translator,
                userRepository, cipher, settings.getSecure().getSalt(), settings, paths,
                dataSource, timezone);
        byte[] oauthCookieKey = settings.getKey() == null || settings.getKey().isBlank()
                ? new byte[32] : decodeKey(settings.getKey());
        com.neoskin.oauth.OAuthStore oauthStore = new com.neoskin.oauth.OAuthStore(dataSource,
                timezone);
        com.neoskin.oauth.OAuthService oauthService = new com.neoskin.oauth.OAuthService(paths,
                oauthStore, userRepository, cipher, oauthCookieKey, timezone);
        com.neoskin.oauth.OAuthRuntime.set(oauthService);
        HandlerResolver passportOauthResolver = PassportOAuthController.resolver(oauthService,
                translator, cipher);
        HandlerResolver passportApiResolver = PassportApiController.resolver(oauthService,
                translator, cipher);
        com.neoskin.ignition.Ignition.configure(settings.isDebug(), settings.getEnv());

        // 纹理盘（FS_DRIVER=local / s3）：控制器通过静态单例取用
        com.neoskin.storage.TextureStorageRuntime.set(
                com.neoskin.storage.TextureStorageFactory.create(settings, paths));
        HandlerResolver redirectResolver = RedirectController.resolver();
        HandlerResolver ignitionResolver = IgnitionController.resolver(
                IgnitionController.defaultConfigFile(), translator);

        // ViewServiceProvider：shared.notifications 的 composer（未读通知下拉）
        com.neoskin.web.view.PageView.setInboxProvider(user -> {
            java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (com.neoskin.db.NotificationRepository.Row row
                    : notificationRepository.unread(user.uid(), 20)) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", row.id);
                item.put("title", communications.title(row));
                items.add(item);
            }
            return items;
        });
        return new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = home.resolve(action);
                if (handler != null) return handler;
                handler = auth.resolve(action);
                if (handler != null) return handler;
                handler = translations.resolve(action);
                if (handler != null) return handler;
                handler = communicationsResolver.resolve(action);
                if (handler != null) return handler;
                handler = userResolver.resolve(action);
                if (handler != null) return handler;
                handler = textureResolver.resolve(action);
                if (handler != null) return handler;
                handler = skinlibResolver.resolve(action);
                if (handler != null) return handler;
                handler = playerResolver.resolve(action);
                if (handler != null) return handler;
                handler = closetResolver.resolve(action);
                if (handler != null) return handler;
                handler = adminResolver.resolve(action);
                if (handler != null) return handler;
                handler = usersManagementResolver.resolve(action);
                if (handler != null) return handler;
                handler = playersManagementResolver.resolve(action);
                if (handler != null) return handler;
                handler = viewResolver.resolve(action);
                if (handler != null) return handler;
                handler = optionsResolver.resolve(action);
                if (handler != null) return handler;
                handler = notificationsResolver.resolve(action);
                if (handler != null) return handler;
                handler = reportResolver.resolve(action);
                if (handler != null) return handler;
                handler = closetManagementResolver.resolve(action);
                if (handler != null) return handler;
                handler = updateResolver.resolve(action);
                if (handler != null) return handler;
                handler = pluginResolver.resolve(action);
                if (handler != null) return handler;
                handler = marketResolver.resolve(action);
                if (handler != null) return handler;
                handler = setupResolver.resolve(action);
                if (handler != null) return handler;
                handler = passportOauthResolver.resolve(action);
                if (handler != null) return handler;
                handler = passportApiResolver.resolve(action);
                if (handler != null) return handler;
                handler = redirectResolver.resolve(action);
                if (handler != null) return handler;
                return ignitionResolver.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = home.resolve(action, uri);
                if (handler != null) {
                    return handler;
                }
                handler = auth.resolve(action, uri);
                if (handler != null) {
                    return handler;
                }
                handler = translations.resolve(action, uri);
                if (handler != null) return handler;
                handler = communicationsResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = userResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = textureResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = skinlibResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = playerResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = closetResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = adminResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = usersManagementResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = playersManagementResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = viewResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = optionsResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = notificationsResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = reportResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = closetManagementResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = updateResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = pluginResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = marketResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = setupResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = passportOauthResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = passportApiResolver.resolve(action, uri);
                if (handler != null) return handler;
                handler = redirectResolver.resolve(action, uri);
                if (handler != null) return handler;
                return ignitionResolver.resolve(action, uri);
            }
        };
    }

    // ============================================================ HTTP 服务器

    /**
     * 中间件注册表（{@code routes.json} 的 token → handler）。
     *
     * <p>{@code APP_KEY} 用来构造 {@link PhpEncrypter}（会话 cookie 加解密）；
     * 没有 key 时（安装向导阶段）只挂不依赖会话/加密的中间件。
     * 独立成方法是为了让「中间件覆盖率」测试拿到与生产完全一致的注册表。</p>
     */
    public static Map<String, Handler<RoutingContext>> middlewareRegistry(NeoSkinSettings settings,
            AppPaths paths, Option option, Translator translator,
            com.neoskin.db.UserRepository userRepository) {
        return middlewareRegistry(settings, paths, option, translator, userRepository,
                com.neoskin.service.cache.CacheRuntime.store());
    }

    /**
     * 同上，但会话 Redis 后端显式传入（生产装配用：{@code vertxHttpServer} bean
     * 声明对 {@code cacheStore} 的依赖来保证初始化顺序）。
     */
    public static Map<String, Handler<RoutingContext>> middlewareRegistry(NeoSkinSettings settings,
            AppPaths paths, Option option, Translator translator,
            com.neoskin.db.UserRepository userRepository,
            com.neoskin.service.cache.CacheStore cacheStore) {
        return middlewareRegistry(settings, paths, option, translator, userRepository,
                cacheStore, null);
    }

    /**
     * 同上，并接线请求期自动升级（{@code RedirectToSetup} → {@code UpdateRunner}）。
     *
     * <p>之所以在注册表建好后再覆盖 {@code RedirectToSetup} 条目：中间件链是静态
     * 装配的，而 {@code UpdateRunner} 是 Spring Bean；覆盖可以避免让
     * {@code MiddlewareChain} 依赖 Spring 上下文，也不会影响单元测试里的
     * 三参构造（无自动升级）。</p>
     *
     * @param updateTask 执行 {@code update}；{@code null} 时保持「只记录版本较旧」
     */
    public static Map<String, Handler<RoutingContext>> middlewareRegistry(NeoSkinSettings settings,
            AppPaths paths, Option option, Translator translator,
            com.neoskin.db.UserRepository userRepository,
            com.neoskin.service.cache.CacheStore cacheStore,
            java.util.function.BooleanSupplier updateTask) {
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        String key = settings.getKey();

        if (key != null && !key.isBlank()) {
            EncryptCookies cookies = new EncryptCookies(
                    new PhpEncrypter(decodeKey(key), "aes-256-cbc"));
            com.neoskin.service.session.SessionHandler sessionHandle =
                    com.neoskin.service.session.SessionHandlerFactory.create(settings,
                            paths, cacheStore);
            MiddlewareChain.connectValueLayer(registry, cookies, sessionHandle,
                    paths, settings.getVersion(), option, translator, userRepository);
            if (sessionHandle != null) {
                // 覆盖 StartSession：使用 config/session.php 的 cookie 名/生命周期/
                // path/sameSite/secure（SESSION_COOKIE / SESSION_LIFETIME 等）。
                registry.put("Illuminate\\Session\\Middleware\\StartSession",
                        new com.neoskin.web.middleware.StartSession(sessionHandle, cookies,
                                new com.neoskin.service.session.SessionCookieConfig(
                                        settings.getSession().getCookie(),
                                        settings.getSession().getLifetimeMinutes(),
                                        settings.getSession().getPath(),
                                        settings.getSession().getSameSite(),
                                        settings.getSession().isSecure())));
                // XSRF-TOKEN cookie 的生命周期同样跟随 SESSION_LIFETIME
                registry.put("Illuminate\\Foundation\\Http\\Middleware\\PreventRequestForgery",
                        new com.neoskin.web.middleware.VerifyCsrfToken(
                                cookies.encrypter(),
                                settings.getSession().getLifetimeMinutes()));
                // Passport laravel_token 的生命周期跟随 SESSION_LIFETIME
                registry.put("Laravel\\Passport\\Http\\Middleware\\CreateFreshApiToken",
                        new com.neoskin.web.middleware.CreateFreshApiToken(
                                userRepository,
                                settings.getSession().getLifetimeMinutes()));
            }
        } else {
            // 没有 APP_KEY 时不挂加解密层：请求仍可跑，但会话不会持久化。
            // 源项目缺 APP_KEY 会直接抛 MissingAppKeyException，这里降级成告警
            // 以便首次安装向导（那时还没有 key）能启动。
            log.warn("APP_KEY 未配置：会话与 cookie 加密已禁用（安装向导阶段属正常）");
            MiddlewareChain.connectValueLayer(registry, null, (java.nio.file.Path) null,
                    paths, settings.getVersion(), option, translator);
        }

        // 请求期自动升级（源 RedirectToSetup 里的 Artisan::call('update')）。
        if (paths != null && updateTask != null) {
            registry.put("App\\Http\\Middleware\\RedirectToSetup",
                    new com.neoskin.web.middleware.RedirectToSetup(paths,
                            settings.getVersion(), option, updateTask));
        }

        return registry;
    }

    /** 全局事件监听者只注册一次（多个 Spring 上下文/测试类共用 JVM）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean EVENTS_REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 注册需要外部依赖的内置监听者（源 {@code EventServiceProvider} 的映射）：
     * 插件启用/版本变化 → 复制 assets + 清理前端语言包；并注入
     * {@code Hook.sendNotification} 的站内信投递实现。
     */
    private static void registerEventListeners(AppPaths paths,
            com.neoskin.service.PluginManager pluginManager,
            com.neoskin.db.NotificationRepository notifications, String timezone) {
        if (!EVENTS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        com.neoskin.event.listener.CopyPluginAssets copyAssets =
                new com.neoskin.event.listener.CopyPluginAssets();
        com.neoskin.event.listener.CleanUpFrontEndLocaleFiles cleanLocales =
                new com.neoskin.event.listener.CleanUpFrontEndLocaleFiles();

        java.util.function.Consumer<com.neoskin.service.Plugin> onPluginChanged = plugin -> {
            copyAssets.handle(plugin, paths.publicDir());
            cleanLocales.handle(paths.publicDir("lang"));
        };
        com.neoskin.event.EventRuntime.dispatcher().listen(
                com.neoskin.event.Events.PluginWasEnabled.class,
                event -> onPluginChanged.accept(event.plugin()));
        com.neoskin.event.EventRuntime.dispatcher().listen("plugin.versionChanged",
                payload -> onPluginChanged.accept((com.neoskin.service.Plugin) payload[0]));

        com.neoskin.service.Hook.setNotificationSink((users, title, content) -> {
            try {
                String data = com.neoskin.support.PhpJson.encode(
                        java.util.Map.of("title", title, "content", content,
                                "format", "html"), 0);
                String now = com.neoskin.support.PhpDate.now(timezone);
                for (int uid : uidsOf(users)) {
                    notifications.insert(java.util.UUID.randomUUID().toString(), uid, data, now);
                }
            } catch (java.sql.SQLException e) {
                org.slf4j.LoggerFactory.getLogger(WebConfig.class)
                        .warn("sendNotification failed: {}", e.getMessage());
            }
        });

        pluginManager.boot();
    }

    /** {@code Hook.sendNotification($users)} 的用户参数归一化。 */
    private static java.util.List<Integer> uidsOf(Object users) {
        java.util.List<Integer> uids = new java.util.ArrayList<>();
        if (users instanceof com.neoskin.db.UserRepository.UserRow user) {
            uids.add(user.uid());
        } else if (users instanceof Number number) {
            uids.add(number.intValue());
        } else if (users instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                uids.addAll(uidsOf(item));
            }
        } else if (users instanceof Object[] array) {
            for (Object item : array) {
                uids.addAll(uidsOf(item));
            }
        }

        return uids;
    }

    /**
     * Java 插件（PF4J）：启动时加载 {@code plugins-java/} 下的 jar，并把
     * {@code EventExtensionPoint} 注册到事件总线；容器关闭时停止插件。
     */
    @Bean(destroyMethod = "stopPlugins")
    public com.neoskin.plugin.JavaPluginManager javaPluginManager(NeoSkinSettings settings,
            AppPaths paths) {
        return com.neoskin.plugin.JavaPluginManager.boot(settings, paths);
    }

    /**
     * Vert.x 服务器（含静态资源与中间件链）。
     *
     * <p>{@code APP_KEY} 用来构造 {@link PhpEncrypter}（会话 cookie 加解密）。
     * 格式与 Laravel 一致：{@code base64:...} 前缀即 base64 编码的密钥。</p>
     */
    @Bean
    public VertxHttpServer vertxHttpServer(Routes routes, HandlerResolver resolver,
            NeoSkinSettings settings, AppPaths paths, Option option, Translator translator,
            com.neoskin.db.UserRepository userRepository,
            com.neoskin.service.cache.CacheStore cacheStore,
            com.neoskin.service.UpdateRunner updateRunner,
            NotificationRepository notificationRepository,
            com.neoskin.db.TextureRepository textureRepository,
            com.neoskin.db.PlayerRepository playerRepository,
            CommunicationRepository communicationRepository,
            Communications communications,
            com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter,
            javax.sql.DataSource dataSource) {
        Map<String, Handler<RoutingContext>> registry = middlewareRegistry(settings, paths,
                option, translator, userRepository, cacheStore,
                () -> {
                    updateRunner.update();
                    return true;
                });

        // 全局栈最后一个：DetectLanguagePrefer（写 ThreadLocal + 排 locale cookie）。
        java.util.List<Handler<RoutingContext>> global =
                java.util.List.of(new LanguageMiddleware());

        // 异常出口：所有 4xx/5xx 一律走源的错误页体系（HTML / JSON 分流）。
        com.neoskin.web.error.ErrorHandler failure = new com.neoskin.web.error.ErrorHandler(
                new com.neoskin.web.error.ErrorPages(option, translator, settings.isDebug()),
                userRepository, settings.isDebug());

        VertxHttpServer server = new VertxHttpServer(
                routes, resolver, settings.getHttp().getPort())
                .withStaticRoot(paths.publicDir())
                .withSpa(new com.neoskin.web.SpaFrontend(option, userRepository,
                        paths.publicDir(), settings.getVersion(),
                        notificationRepository,
                        new com.neoskin.db.ReportRepository(dataSource, userRepository,
                                textureRepository),
                        textureRepository, playerRepository, communicationRepository,
                        communications,
                        new UpdateController(option, translator, userRepository, settings,
                                paths, javaScript, filter, settings.isDebug()).info(),
                        settings.getTimezone()))
                .withMiddleware(registry)
                .withGlobalMiddleware(global)
                .withFailureHandler(failure);

        if (!Files.isDirectory(paths.publicDir())) {
            log.warn("public/ 目录不存在：/app/*、/lang/* 将全部 404（见 PORT-NOTES §8.5）");
        }
        return server;
    }

    /** Laravel 的 {@code base64:} 前缀 → 原始字节；无前缀按 UTF-8 字节。 */
    static byte[] decodeKey(String key) {
        if (key.startsWith("base64:")) {
            return Base64.getDecoder().decode(key.substring("base64:".length()));
        }
        return key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 容器就绪后再起监听，避免请求打到半初始化的 Bean。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startServer(ApplicationReadyEvent event) {
        // CLI 模式（--neoskin.command=...）只执行命令，不监听端口。
        if (com.neoskin.cli.Commands.isActive()) {
            return;
        }
        VertxHttpServer server = event.getApplicationContext()
                .getBeanProvider(VertxHttpServer.class).getIfAvailable();
        if (server == null) {
            return;
        }
        server.startup().whenComplete((ok, error) -> {
            if (error != null) {
                log.error("Vert.x 服务器启动失败", error);
            } else {
                log.info("Vert.x 服务器已启动");
            }
        });
    }

    /** 关闭时优雅停机。 */
    @EventListener(ContextClosedEvent.class)
    public void stopServer(ContextClosedEvent event) {
        VertxHttpServer server = event.getApplicationContext()
                .getBeanProvider(VertxHttpServer.class).getIfAvailable();
        if (server != null) {
            server.shutdown();
        }
    }
}
