package com.neoskin;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.neoskin.config.AppPaths;

/**
 * Spring 容器冒烟测试：证明应用能真正启动（不是只有单元测试能过）。
 *
 * <p>这里踩过的两个启动期坑：</p>
 * <ul>
 *   <li>{@code AppPaths} 有两个构造器，未标注 {@code @Autowired} 时 Spring 会
 *       尝试无参构造并失败；</li>
 *   <li>没有 {@code APP_KEY} 时 {@code MiddlewareChain} 里
 *       {@code VerifyCsrfToken(cookies.encrypter())} 会 NPE（安装向导阶段必现）。</li>
 * </ul>
 *
 * <p>端口固定为 0（随机），监听在 {@code ApplicationReadyEvent} 后启动，
 * 测试结束由 {@code ContextClosedEvent} 关闭。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationContextTest {

    private static final Path DIR = createTempDir();

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("neoskin.database.connection", () -> "sqlite");
        registry.add("neoskin.database.name", () -> DIR.resolve("ctx.sqlite").toString());
        registry.add("neoskin.storage.root", () -> DIR.resolve("storage").toString());
        registry.add("neoskin.http.port", () -> 0);
        registry.add("neoskin.key", () -> ""); // 无 APP_KEY：安装向导场景
    }

    @Test
    void contextLoads() {
        assertNotNull(context.getBean(AppPaths.class));
        assertTrue(Files.isDirectory(DIR));
        assertInstanceOf(com.neoskin.service.cache.FileCache.class,
                context.getBean(com.neoskin.service.cache.CacheStore.class));
        assertNotNull(context.getBean(com.neoskin.service.LoginFailCounter.class));
        assertNotNull(context.getBean(com.neoskin.plugin.JavaPluginManager.class));
    }

    /** 容器里的 CacheStore 会写进静态单例；清掉，避免影响后续测试类。 */
    @AfterAll
    static void clearRuntime() {
        com.neoskin.service.cache.CacheRuntime.set(null);
        com.neoskin.storage.TextureStorageRuntime.set(null);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("neoskin-context-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
