package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.MiddlewareChain;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link TextureController} 图像端点的端到端测试（真实 Vert.x + SQLite + 磁盘纹理）。
 *
 * <p>锁定的源行为：</p>
 * <ol>
 *   <li>{@code preview/{tid}} / {@code preview/hash/{hash}} 默认输出 WebP（{@code ?png} 输出 PNG），
 *       带 ETag / Last-Modified / Cache-Control: public；条件请求回 304；</li>
 *   <li>{@code avatar/{tid}} 默认 100x100，{@code size} + {@code 3d} 生效；</li>
 *   <li>缺失纹理 → 内置回退头像（200）；披风纹理做头像 → 422；</li>
 *   <li>{@code raw/{tid}} 受 {@code allow_downloading_texture} 门控；</li>
 *   <li>{@code textures/{hash}} 原样输出磁盘字节。</li>
 * </ol>
 */
class TextureControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private AppPaths paths;
    private Option option;
    private TextureRepository textures;

    private final Routes routes = Routes.load();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        schema.install();

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createDirectories(paths.textures());
        Files.createFile(paths.installLock());

        option = new Option(ds, schema, paths);
        textures = new TextureRepository(ds);
        UserRepository users = new UserRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"));

        HandlerResolver resolver = TextureController.resolver(option, translator, players,
                textures, users, paths, "Asia/Shanghai",
                new com.neoskin.service.ImageCache());

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- preview

    @Test
    void previewByTidReturnsPngWithValidatorsAndSupports304() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> res = sendBytes("GET", "/preview/" + texture.tid() + "?png", null);
        assertEquals(200, res.statusCode());
        assertEquals("image/png", res.headers().firstValue("Content-Type").orElse(null));
        assertNotNull(res.headers().firstValue("ETag").orElse(null), "ETag 必须存在");
        assertNotNull(res.headers().firstValue("Last-Modified").orElse(null));
        assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("public"));
        BufferedImage image = decodeImage(res.body());
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);

        // 条件请求：If-None-Match 命中 → 304，无 Content-Type
        String etag = res.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> notModified = sendBytes("GET", "/preview/" + texture.tid() + "?png",
                Map.of("If-None-Match", etag));
        assertEquals(304, notModified.statusCode());
        assertNull(notModified.headers().firstValue("Content-Type").orElse(null));
        assertEquals(0, notModified.body().length);
        assertEquals(etag, notModified.headers().firstValue("ETag").orElse(null));
    }

    @Test
    void previewDefaultsToWebp() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> res = sendBytes("GET", "/preview/" + texture.tid(), null);
        assertEquals(200, res.statusCode());
        // 源：imagetypes() 含 IMG_WEBP 时默认 webp（?png 才 PNG）。
        // macOS arm64 没有 webp-imageio 原生库时回退 PNG。
        byte[] body = res.body();
        if (TextureController.webpWriterAvailable()) {
            assertEquals("image/webp", res.headers().firstValue("Content-Type").orElse(null));
            assertTrue(body.length > 12);
            assertEquals("RIFF", new String(body, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("WEBP", new String(body, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        } else {
            assertEquals("image/png", res.headers().firstValue("Content-Type").orElse(null));
            assertEquals((byte) 0x89, body[0]);
        }

        // ?png 分支仍是 PNG
        HttpResponse<byte[]> png = sendBytes("GET", "/preview/" + texture.tid() + "?png", null);
        assertEquals("image/png", png.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void previewByHashWorks() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> res = sendBytes("GET", "/preview/hash/" + texture.hash(), null);
        assertEquals(200, res.statusCode());
        decodeImage(res.body());
    }

    @Test
    void previewRejectsUnknownAndMissingFiles() throws Exception {
        assertEquals(404, sendBytes("GET", "/preview/999999", null).statusCode());

        // DB 有记录但磁盘文件不存在 → abort(404)
        long tid = textures.insert("ghost", "steve", "deadbeef" + System.nanoTime(), 10, 0,
                true, "2024-01-01 00:00:00");
        assertEquals(404, sendBytes("GET", "/preview/" + tid, null).statusCode());
    }

    // -------------------------------------------------------------- avatar

    @Test
    void avatarByTextureRendersRequestedSizeAndMode() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> twoD = sendBytes("GET", "/avatar/" + texture.tid(), null);
        assertEquals(200, twoD.statusCode());
        BufferedImage image = decodeImage(twoD.body());
        assertEquals(100, image.getWidth());
        assertEquals(100, image.getHeight());
        assertNotNull(twoD.headers().firstValue("Last-Modified").orElse(null));

        HttpResponse<byte[]> threeD = sendBytes("GET",
                "/avatar/" + texture.tid() + "?size=48&3d", null);
        assertEquals(200, threeD.statusCode());
        BufferedImage image3d = decodeImage(threeD.body());
        assertEquals(48, image3d.getWidth());
        assertEquals(48, image3d.getHeight());
    }

    @Test
    void avatarOfUnknownTextureFallsBackToBundledImage() throws Exception {
        HttpResponse<byte[]> res = sendBytes("GET", "/avatar/999999", null);
        assertEquals(200, res.statusCode());
        BufferedImage image = decodeImage(res.body());
        assertEquals(100, image.getWidth());
        assertEquals(100, image.getHeight());
        assertNull(res.headers().firstValue("Last-Modified").orElse(null),
                "回退头像没有源文件 mtime");
    }

    @Test
    void avatarOfMissingFileFallsBackToBundledImage() throws Exception {
        long tid = textures.insert("ghost", "alex", "cafebabe" + System.nanoTime(), 10, 0,
                true, "2024-01-01 00:00:00");

        HttpResponse<byte[]> res = sendBytes("GET", "/avatar/" + tid, null);
        assertEquals(200, res.statusCode());
        decodeImage(res.body());
    }

    @Test
    void capeCannotBeUsedAsAvatar() throws Exception {
        TextureRepository.Row cape = insertTexture("cape", true);

        assertEquals(422, sendBytes("GET", "/avatar/" + cape.tid(), null).statusCode());
    }

    @Test
    void avatarByHashAndByUser() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> byHash =
                sendBytes("GET", "/avatar/hash/" + texture.hash(), null);
        assertEquals(200, byHash.statusCode());
        decodeImage(byHash.body());

        // 未注册用户 → 回退头像（200）
        assertEquals(200, sendBytes("GET", "/avatar/user/99999", null).statusCode());
        // 不存在的玩家 → firstOrFail 404
        assertEquals(404, sendBytes("GET", "/avatar/player/nobody", null).statusCode());
    }

    // ---------------------------------------------------------- raw/texture

    @Test
    void rawRequiresDownloadOption() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        option.set("allow_downloading_texture", "false");
        assertEquals(403, sendBytes("GET", "/raw/" + texture.tid(), null).statusCode());

        option.set("allow_downloading_texture", "true");
        HttpResponse<byte[]> res = sendBytes("GET", "/raw/" + texture.tid(), null);
        assertEquals(200, res.statusCode());
        assertArrayEquals(Files.readAllBytes(paths.textures().resolve(texture.hash())),
                res.body());
    }

    @Test
    void textureServesOriginalBytes() throws Exception {
        TextureRepository.Row texture = insertTexture("steve", true);

        HttpResponse<byte[]> res = sendBytes("GET", "/textures/" + texture.hash(), null);
        assertEquals(200, res.statusCode());
        assertEquals("image/png", res.headers().firstValue("Content-Type").orElse(null));
        assertArrayEquals(Files.readAllBytes(paths.textures().resolve(texture.hash())),
                res.body());
    }

    // ================================================================ 工具

    /** 写入 steve.png 并按 hash 落盘，插入 textures 行。 */
    private TextureRepository.Row insertTexture(String type, boolean withFile) throws Exception {
        byte[] png = resource("/misc/textures/steve.png");
        String hash = sha256(png) + "-" + type + "-" + System.nanoTime();
        if (withFile) {
            Files.write(paths.textures().resolve(hash), png);
        }
        long tid = textures.insert("texture", type, hash, png.length, 0, true,
                "2024-01-01 00:00:00");

        return textures.findByTid(tid);
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = TextureControllerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);

            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private static BufferedImage decodeImage(byte[] bytes) throws Exception {
        assertNotNull(bytes);
        assertTrue(bytes.length > 12, "empty image body");
        boolean png = bytes[0] == (byte) 0x89 && bytes[1] == (byte) 'P';
        boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F';
        assertTrue(png || webp, "既不是 PNG 也不是 WebP");

        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private HttpResponse<byte[]> sendBytes(String method, String path,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());

        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
