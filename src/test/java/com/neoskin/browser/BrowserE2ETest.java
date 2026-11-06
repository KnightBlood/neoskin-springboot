package com.neoskin.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.neoskin.cli.Commands;

/**
 * 浏览器页面 E2E：Spring 容器内启动真实 Vert.x 服务，安装 SQLite 站点，
 * 再由 Playwright flows 走首页/登录/用户中心/皮肤库/管理端。
 *
 * <p>默认跳过；设置 {@code NEOSKIN_BROWSER_E2E=1} 且存在
 * {@code target/classes/static/app} 后执行。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "NEOSKIN_BROWSER_E2E", matches = "1")
class BrowserE2ETest {

    private static final Path DIR = createTempDir();
    private static final int PORT = freePort();

    @Autowired
    private ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("neoskin.database.connection", () -> "sqlite");
        registry.add("neoskin.database.name", () -> DIR.resolve("browser.sqlite").toString());
        registry.add("neoskin.storage.root", () -> DIR.resolve("storage").toString());
        registry.add("neoskin.http.port", () -> PORT);
        registry.add("neoskin.key",
                () -> "base64:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Test
    void browserFlows() throws Exception {
        if (!Files.isDirectory(Path.of("target", "classes", "static", "app"))) {
            return;
        }
        if (!Files.isDirectory(Path.of("tests", "browser", "node_modules", "playwright-core"))) {
            return;
        }

        int installed = Commands.run(context, "install", new String[] {
                "--email=admin@example.com",
                "--password=secret123",
                "--nickname=Admin",
        });
        assertEquals(0, installed, "install command failed");

        String base = "http://127.0.0.1:" + PORT;
        waitHealthy(base);

        ProcessBuilder builder = new ProcessBuilder("node", "tests/browser/flows.mjs");
        builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("NEOSKIN_BASE_URL", base);
        String chrome = System.getenv("NEOSKIN_CHROME_PATH");
        if (chrome != null && !chrome.isBlank()) {
            env.put("NEOSKIN_CHROME_PATH", chrome);
        }
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String text = new String(output);
        assertTrue(exited, "playwright flows timed out\n" + text);
        assertEquals(0, process.exitValue(), "playwright flows failed\n" + text);
    }

    private static void waitHealthy(String base) throws Exception {
        for (int i = 0; i < 120; i++) {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) java.net.URI.create(base + "/auth/login")
                                .toURL().openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                if (connection.getResponseCode() == 200) {
                    connection.disconnect();
                    return;
                }
                connection.disconnect();
            } catch (IOException ignored) {
                // server still booting
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("server did not become healthy: " + base);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("neoskin-spring-browser-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void cleanup() {
        try {
            workspaceDelete(DIR);
        } catch (IOException ignored) {
            // Windows may keep SQLite handles briefly; temp dir cleanup is best-effort.
        }
    }

    private static void workspaceDelete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
