package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.ignition.Ignition;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RunnableSolutionsEnabled;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code /.well-known/change-password}（RedirectController）与 {@code /_ignition/*}
 * 三个调试端点的端到端测试。
 */
class RedirectIgnitionControllerTest {

    private static final String OVERRIDE = "IGNITION_ENABLE_RUNNABLE_SOLUTIONS";

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private Path configFile;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws Exception {
        configFile = dir.resolve(".ignition.json");
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");

        vertx = Vertx.vertx();
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        registry.put("Spatie\\LaravelIgnition\\Http\\Middleware\\RunnableSolutionsEnabled",
                new RunnableSolutionsEnabled());

        HandlerResolver redirect = RedirectController.resolver();
        HandlerResolver ignition = IgnitionController.resolver(configFile, translator);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = redirect.resolve(action);

                return handler != null ? handler : ignition.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = redirect.resolve(action, uri);

                return handler != null ? handler : ignition.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                Routes.load(), resolver, vertx,
                List.of(com.neoskin.web.middleware.RequestInput.normalizer()), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        Ignition.configure(false, "production");
        System.clearProperty(OVERRIDE);
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------ redirect

    @Test
    void changePasswordRedirectsToProfile() throws Exception {
        HttpResponse<String> res = get("/.well-known/change-password");
        assertEquals(302, res.statusCode(), res.body());
        String location = res.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("http://127.0.0.1:" + port + "/"), location);
        assertTrue(location.endsWith("/user/profile"), location);
    }

    // ----------------------------------------------------- ignition

    @Test
    void ignitionDisabledByDefault() throws Exception {
        assertEquals(404, get("/_ignition/health-check").statusCode());
        assertEquals(404, post("/_ignition/execute-solution", "solution=Foo").statusCode());
        assertEquals(404, post("/_ignition/update-config",
                "theme=dark&editor=vscode&hide_solutions=1").statusCode());
        assertTrue(Files.notExists(configFile), "关闭时不得写配置文件");
    }

    @Test
    void healthCheckWhenEnabled() throws Exception {
        Ignition.configure(true, "local");

        HttpResponse<String> res = get("/_ignition/health-check");
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("{\"can_execute_commands\":false}", res.body());
    }

    @Test
    void environmentVariableOverridesGuard() throws Exception {
        System.setProperty(OVERRIDE, "true");
        assertEquals(200, get("/_ignition/health-check").statusCode());

        System.setProperty(OVERRIDE, "false");
        assertEquals(404, get("/_ignition/health-check").statusCode());
    }

    @Test
    void executeSolutionAlwaysNotFound() throws Exception {
        Ignition.configure(true, "development");

        HttpResponse<String> missing = post("/_ignition/execute-solution", "");
        assertEquals(422, missing.statusCode(), missing.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(missing.body());
        assertNotNull(body.get("message"));
        assertTrue(body.get("errors") instanceof Map<?, ?>);

        HttpResponse<String> unknown = post("/_ignition/execute-solution",
                "solution=" + enc("App\\Solutions\\Nope"));
        assertEquals(404, unknown.statusCode(), unknown.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) PhpJson.decode(unknown.body());
        assertEquals("Solution could not be found", error.get("message"));
    }

    @Test
    void updateConfigValidatesAndPersists() throws Exception {
        Ignition.configure(true, "local");

        HttpResponse<String> invalid = post("/_ignition/update-config",
                "theme=blue&editor=vscode&hide_solutions=1");
        assertEquals(422, invalid.statusCode(), invalid.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(invalid.body());
        assertTrue(body.get("errors") instanceof Map<?, ?> errors
                && errors.containsKey("theme"), invalid.body());

        HttpResponse<String> saved = post("/_ignition/update-config",
                "theme=dark&editor=vscode&hide_solutions=1");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("true", saved.body());
        assertTrue(Files.isRegularFile(configFile));
        String content = Files.readString(configFile);
        assertTrue(content.contains("\"theme\":\"dark\""), content);
        assertTrue(content.contains("\"editor\":\"vscode\""), content);
    }

    // ============================================================ 工具

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int listen(io.vertx.ext.web.Router router) {
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
