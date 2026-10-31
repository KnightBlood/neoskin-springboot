package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.config.WebConfig;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.web.RouteDefinition;
import com.neoskin.web.Routes;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * 中间件覆盖率对账：{@code routes.json} 里出现的每个中间件 token，
 * 要么在 {@link WebConfig#middlewareRegistry} 里有真实 handler，要么在
 * {@link #INTENTIONAL_NO_OPS} 里（并写明替代实现的位置）。
 *
 * <p>这条测试防止「中间件静默穿透」：{@code MiddlewareChain.handlers} 对查不到的
 * token 是跳过的，历史上有两个真实缺口就是这样被漏掉的（{@code throttle} 别名、
 * {@code verified} 中间件），见 PORT-NOTES §36。</p>
 */
class MiddlewareCoverageTest {

    /**
     * 明确不注册 handler 的 token（由控制器或全局栈等价实现）。
     *
     * <ul>
     *   <li>全局栈里没有等价行为的：Maintenance 无「维护模式」、TrimStrings 在
     *       {@link RequestInput#normalizer()} 内、DetectLanguagePrefer 由
     *       {@code LanguageMiddleware} 全局挂载；</li>
     *   <li>AddQueuedCookiesToResponse：本工程没有 queued cookie API，Cookie 直接写响应；</li>
     *   <li>SubstituteBindings：路由模型绑定由各控制器按 id 加载；</li>
     *   <li>SetCacheHeaders（{@code cache.headers}）：ETag/Cache-Control 在 action 内写
     *       （PORT-NOTES §23、§24）；</li>
     *   <li>{@code Closure}：route 级闭包（所有权/参数校验）在控制器内实现。</li>
     * </ul>
     */
    private static final Set<String> INTENTIONAL_NO_OPS = Set.of(
            "Illuminate\\Foundation\\Http\\Middleware\\TrimStrings",
            "App\\Http\\Middleware\\DetectLanguagePrefer",
            "Illuminate\\Cookie\\Middleware\\AddQueuedCookiesToResponse",
            "Illuminate\\Routing\\Middleware\\SubstituteBindings",
            "Illuminate\\Http\\Middleware\\SetCacheHeaders",
            "Closure");

    @TempDir
    Path dir;

    @Test
    void everyMiddlewareTokenHasHandlerOrDocumentedNoOp() throws Exception {
        Path file = dir.resolve("t.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);

        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            NeoSkinSettings settings = new NeoSkinSettings();
            settings.getDatabase().setConnection("sqlite");
            settings.getDatabase().setName(file.toAbsolutePath().toString());
            settings.getStorage().setRoot(dir.resolve("storage").toString());
            // 有 APP_KEY 才会挂会话/Cookie 相关中间件（与生产一致）。
            settings.setKey("base64:" + java.util.Base64.getEncoder()
                    .encodeToString(new byte[32]));

            SchemaInstaller schema = new SchemaInstaller(ds, settings);
            AppPaths paths = new AppPaths(dir, settings);
            Files.createDirectories(paths.storage());
            Option option = new Option(ds, schema, paths);
            Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
            UserRepository users = new UserRepository(ds);

            Map<String, Handler<RoutingContext>> registry =
                    WebConfig.middlewareRegistry(settings, paths, option, translator, users);

            Set<String> missing = new LinkedHashSet<>();
            List<String> checked = new ArrayList<>();

            for (RouteDefinition route : Routes.load().all()) {
                for (String token : MiddlewareChain.expand(route.middleware())) {
                    String canonical = MiddlewareChain.canonical(token);
                    checked.add(canonical);
                    if (registry.containsKey(canonical)
                            || registry.containsKey(stripParams(canonical))
                            || INTENTIONAL_NO_OPS.contains(stripParams(canonical))) {
                        continue;
                    }
                    missing.add(canonical + "    <- " + route.uri());
                }
            }

            assertTrue(missing.isEmpty(),
                    "以下中间件既没有 handler，也不在 documented no-op 名单里：\n"
                            + String.join("\n", missing));
            // 自检：确实扫到了全部 173 条路由展开出来的 token
            assertTrue(checked.size() > 500, "展开后的中间件条目过少：" + checked.size());
        }
    }

    private static String stripParams(String token) {
        int colon = token.indexOf(':');

        return colon < 0 ? token : token.substring(0, colon);
    }
}
