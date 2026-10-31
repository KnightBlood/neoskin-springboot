package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionCookieConfig;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * XSRF-TOKEN cookie 的 Max-Age 跟随 {@code SESSION_LIFETIME}（源
 * {@code config('session.lifetime')}）。
 */
class XsrfCookieLifetimeTest {

    @TempDir
    Path dir;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void xsrfCookieUsesConfiguredLifetime() throws Exception {
        byte[] key = new byte[32];
        PhpEncrypter encrypter = new PhpEncrypter(key, "aes-256-cbc");

        Router router = Router.router(vertx);
        router.route().handler(new StartSession(
                new FileSessionHandler(dir.resolve("sessions"), 30), null,
                new SessionCookieConfig("MY_SESSION", 30, "/", "lax", false)));
        router.route().handler(new VerifyCsrfToken(encrypter, 30));
        router.route().handler(ctx -> ctx.response().setStatusCode(200).end("ok"));
        int port = listen(router);

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        List<String> cookies = res.headers().allValues("Set-Cookie");
        String xsrf = cookies.stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElse("");
        assertTrue(xsrf.contains("Max-Age=1800"), xsrf);
        assertEquals(1, cookies.stream().filter(c -> c.startsWith("MY_SESSION=")).count());
    }

    private int listen(Router router) throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> port.complete(s.actualPort()))
                .onFailure(port::completeExceptionally);

        return port.get(5, TimeUnit.SECONDS);
    }
}
