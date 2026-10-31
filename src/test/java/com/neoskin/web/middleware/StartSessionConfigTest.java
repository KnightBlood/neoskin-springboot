package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.session.SessionCookieConfig;
import com.neoskin.service.session.SessionHandler;
import com.neoskin.service.session.FileSessionHandler;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * 会话 cookie 配置（源 {@code SESSION_COOKIE} / {@code SESSION_LIFETIME} /
 * path / same_site / secure）真实生效。
 */
class StartSessionConfigTest {

    @TempDir
    Path dir;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void usesConfiguredCookieNameAndAttributes() throws Exception {
        SessionHandler handler = new FileSessionHandler(dir.resolve("sessions"), 30);
        SessionCookieConfig config = new SessionCookieConfig(
                "MY_SESSION", 30, "/sub", "strict", true);

        Router router = Router.router(vertx);
        router.route().handler(new StartSession(handler, null, config));
        router.route().handler(ctx -> ctx.response().setStatusCode(200).end("ok"));
        int port = listen(router);

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        String cookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith("MY_SESSION="), cookie);
        assertTrue(cookie.contains("Max-Age=1800"), cookie);
        assertTrue(cookie.contains("Path=/sub"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
    }

    private int listen(Router router) throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> port.complete(s.actualPort()))
                .onFailure(port::completeExceptionally);

        return port.get(5, TimeUnit.SECONDS);
    }
}
