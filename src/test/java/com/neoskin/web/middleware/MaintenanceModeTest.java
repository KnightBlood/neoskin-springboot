package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * {@link MaintenanceMode} 的端到端测试（{@code storage/framework/down}）。
 */
class MaintenanceModeTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private AppPaths paths;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws Exception {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());

        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(new MaintenanceMode(paths));
        router.route().handler(ctx -> ctx.response().setStatusCode(200).end("ok"));
        port = listen(router);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void passesThroughWithoutDownFile() throws Exception {
        HttpResponse<String> res = get();
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.body());
    }

    @Test
    void serves503WithRetryAfterWhileDown() throws Exception {
        Path down = MaintenanceMode.downFile(paths);
        Files.createDirectories(down.getParent());
        Files.writeString(down, "{\"status\":503,\"retry\":60,\"secret\":null}");

        HttpResponse<String> res = get();
        assertEquals(503, res.statusCode());
        assertEquals("60", res.headers().firstValue("Retry-After").orElse(null));
        assertTrue(res.body().contains("Service Unavailable"));
    }

    @Test
    void honorsCustomStatusAndRenderBody() throws Exception {
        Path down = MaintenanceMode.downFile(paths);
        Files.createDirectories(down.getParent());
        Files.writeString(down,
                "{\"status\":503,\"render\":\"<p>正在升级，请稍后</p>\"}");

        HttpResponse<String> res = get();
        assertEquals(503, res.statusCode());
        assertTrue(res.body().contains("正在升级"));
    }

    // ============================================================ 工具

    @Test
    void secretQueryBypassesAndSetsCookie() throws Exception {
        Path down = MaintenanceMode.downFile(paths);
        Files.createDirectories(down.getParent());
        Files.writeString(down, "{\"status\":503,\"secret\":\"s3cr3t\"}");

        HttpResponse<String> res = get("/?secret=s3cr3t");
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.body());
        String setCookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.startsWith(MaintenanceMode.MAINTENANCE_COOKIE + "=s3cr3t"),
                setCookie);
    }

    @Test
    void maintenanceCookieBypasses() throws Exception {
        Path down = MaintenanceMode.downFile(paths);
        Files.createDirectories(down.getParent());
        Files.writeString(down, "{\"status\":503,\"secret\":\"s3cr3t\"}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/"))
                .header("Cookie", MaintenanceMode.MAINTENANCE_COOKIE + "=s3cr3t")
                .GET().build();
        HttpResponse<String> res = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, res.statusCode());
    }

    @Test
    void wrongSecretStillServes503() throws Exception {
        Path down = MaintenanceMode.downFile(paths);
        Files.createDirectories(down.getParent());
        Files.writeString(down, "{\"status\":503,\"secret\":\"s3cr3t\"}");

        assertEquals(503, get("/?secret=wrong").statusCode());
    }

    private HttpResponse<String> get() throws Exception {
        return get("/");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int listen(Router router) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> future.complete(s.actualPort()))
                .onFailure(future::completeExceptionally);
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
