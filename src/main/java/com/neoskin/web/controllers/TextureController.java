package com.neoskin.web.controllers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.neoskin.config.AppPaths;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.render.GdImage;
import com.neoskin.render.Minecraft;
import com.neoskin.service.Option;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\TextureController} 的移植。
 *
 * <p>已实现：{@code json} / {@code texture} / {@code raw}（字节直出）以及
 * {@code preview*} / {@code avatarBy*} 六个图像端点（经 {@code com.neoskin.render}
 * 渲染器，PNG 输出）。{@code cache.headers} 中间件语义（ETag/304）在 action 内还原。
 * 缓存用进程内过期映射模拟 Laravel {@code Cache::remember}。</p>
 */
public final class TextureController {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    private static final long TTL_MINUTE = 60_000L;
    private static final long TTL_YEAR = 31536000_000L;

    /** webp 编码器不可用时强制 PNG（{@code imagetypes() & IMG_WEBP} 等效判定）。 */
    private static final boolean WEBP_WRITER_AVAILABLE;

    static {
        Iterator<javax.imageio.ImageWriter> writers =
                ImageIO.getImageWritersByFormatName("webp");
        WEBP_WRITER_AVAILABLE = writers.hasNext();
    }

    private final Option option;
    private final Translator translator;
    private final PlayerRepository players;
    private final TextureRepository textures;
    private final UserRepository users;
    private final AppPaths paths;
    private final String timezone;
    private final Minecraft minecraft = new Minecraft();
    private final com.neoskin.service.ImageCache imageCache;

    public TextureController(Option option, Translator translator, PlayerRepository players,
            TextureRepository textures, UserRepository users, AppPaths paths, String timezone,
            com.neoskin.service.ImageCache imageCache) {
        this.option = option;
        this.translator = translator;
        this.players = players;
        this.textures = textures;
        this.users = users;
        this.paths = paths;
        this.timezone = timezone;
        this.imageCache = imageCache;
    }

    /** {@code GET {player}.json} / {@code GET csl/{player}.json}。 */
    public Handler<RoutingContext> json() {
        return ctx -> {
            try {
                String name = ctx.pathParam("player");
                PlayerRepository.PlayerDetail player = players.findDetailByName(name);
                if (player == null) {
                    ctx.fail(404);
                    return;
                }
                if (player.permission() == UserRepository.BANNED) {
                    ctx.fail(403);
                    return;
                }
                TextureRepository.Row skin = player.tidSkin() > 0
                        ? textures.findByTid(player.tidSkin()) : null;
                TextureRepository.Row cape = player.tidCape() > 0
                        ? textures.findByTid(player.tidCape()) : null;

                Map<String, Object> skins = new LinkedHashMap<>();
                skins.put(skin == null ? "default" : skin.model(), skin == null ? null : skin.hash());

                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("username", player.name());
                profile.put("skins", skins);
                profile.put("cape", cape == null ? null : cape.hash());

                String payload = PhpJson.encode(profile, PhpJson.JSON_UNESCAPED_UNICODE);
                var response = ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .putHeader("Cache-Control", cacheControl());
                if (player.lastModified() != null) {
                    response.putHeader("Last-Modified", httpDate(player.lastModified()));
                }
                response.end(Buffer.buffer(payload, "UTF-8"));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code GET textures/{hash}} / {@code GET csl/textures/{hash}}。 */
    public Handler<RoutingContext> texture() {
        return ctx -> serveTexture(ctx, ctx.pathParam("hash"));
    }

    /** {@code GET raw/{tid}}。 */
    public Handler<RoutingContext> raw() {
        return ctx -> {
            if (!toBool(option.get("allow_downloading_texture"))) {
                ctx.fail(403);
                return;
            }
            try {
                TextureRepository.Row texture = textures.findByTid(toLong(ctx.pathParam("tid")));
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                serveTexture(ctx, texture.hash());
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private void serveTexture(RoutingContext ctx, String hash) {
        try {
            com.neoskin.storage.TextureStorage storage =
                    com.neoskin.storage.TextureStorageRuntime.get(paths.textures());
            if (!storage.exists(hash)) {
                ctx.fail(404);
                return;
            }
            byte[] bytes = storage.read(hash);
            Instant mtime = Instant.ofEpochMilli(storage.lastModifiedMillis(hash));
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "image/png")
                    .putHeader("Content-Length", String.valueOf(bytes.length))
                    .putHeader("Cache-Control", cacheControl())
                    .putHeader("Last-Modified", HTTP_DATE.format(mtime.atZone(ZoneOffset.UTC)))
                    .end(Buffer.buffer(bytes));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    /** {@code previewByHash(Minecraft $minecraft, Request $request, $hash)}。 */
    public Handler<RoutingContext> previewByHash() {
        return ctx -> {
            try {
                TextureRepository.Row texture = textures.findByHash(ctx.pathParam("hash"));
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                preview(ctx, texture);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code preview(Minecraft $minecraft, Request $request, Texture $texture)}（tid 路由绑定）。 */
    public Handler<RoutingContext> preview() {
        return ctx -> {
            try {
                TextureRepository.Row texture =
                        textures.findByTid(toLong(ctx.pathParam("texture")));
                if (texture == null) {
                    ctx.fail(404); // SubstituteBindings + firstOrFail
                    return;
                }
                preview(ctx, texture);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private void preview(RoutingContext ctx, TextureRepository.Row texture) throws Exception {
        String hash = texture.hash();
        com.neoskin.storage.TextureStorage storage =
                com.neoskin.storage.TextureStorageRuntime.get(paths.textures());
        if (!storage.exists(hash)) {
            ctx.fail(404); // abort_if($disk->missing($hash), 404)
            return;
        }

        boolean usePng = requestHas(ctx, "png") || !WEBP_WRITER_AVAILABLE;
        String format = usePng ? "png" : "webp";
        int height = toIntPhp(ctx.request().getParam("height"), 200);

        long ttl = toBool(option.get("enable_preview_cache")) ? TTL_YEAR : TTL_MINUTE;
        com.neoskin.service.ImageCache.Entry cached = remember("preview-t" + texture.tid() + "-" + format, ttl, () -> {
            byte[] bytes = storage.read(hash);
            GdImage image;
            if ("cape".equals(texture.type())) {
                image = minecraft.renderCape(bytes, height);
            } else {
                image = minecraft.renderSkin(bytes, 12, "alex".equals(texture.type()));
            }
            byte[] body = toFormat(image, usePng);
            String lastModified = httpDate(
                    Instant.ofEpochMilli(storage.lastModifiedMillis(hash)));

            return new com.neoskin.service.ImageCache.Entry(body, contentType(usePng), lastModified, ttlOf(ttl));
        });

        imageResponse(ctx, cached);
    }

    /** {@code avatarByPlayer}：{@code Player::where('name',..)->firstOrFail()}。 */
    public Handler<RoutingContext> avatarByPlayer() {
        return ctx -> {
            try {
                PlayerRepository.PlayerDetail player =
                        players.findDetailByName(ctx.pathParam("name"));
                if (player == null) {
                    ctx.fail(404);
                    return;
                }
                TextureRepository.Row skin = player.tidSkin() > 0
                        ? textures.findByTid(player.tidSkin()) : null;
                avatar(ctx, skin);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code avatarByUser}：{@code Texture::find(optional(User::find($uid))->avatar)}。 */
    public Handler<RoutingContext> avatarByUser() {
        return ctx -> {
            try {
                UserRepository.UserRow user =
                        users.findById((int) toLong(ctx.pathParam("uid")));
                TextureRepository.Row texture = user != null && user.avatar() > 0
                        ? textures.findByTid(user.avatar()) : null;
                avatar(ctx, texture);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code avatarByHash}：{@code Texture::where('hash',..)->first()}。 */
    public Handler<RoutingContext> avatarByHash() {
        return ctx -> {
            try {
                TextureRepository.Row texture = textures.findByHash(ctx.pathParam("hash"));
                avatar(ctx, texture);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code avatarByTexture}：{@code Texture::find($tid)}。 */
    public Handler<RoutingContext> avatarByTexture() {
        return ctx -> {
            try {
                TextureRepository.Row texture =
                        textures.findByTid(toLong(ctx.pathParam("tid")));
                avatar(ctx, texture);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code avatar}：422 / 回退图 / 渲染 + resize + 缓存。 */
    private void avatar(RoutingContext ctx, TextureRepository.Row texture) throws Exception {
        if (texture != null && !"steve".equals(texture.type())
                && !"alex".equals(texture.type())) {
            ctx.fail(422);
            return;
        }

        boolean usePng = requestHas(ctx, "png") || !WEBP_WRITER_AVAILABLE;
        String format = usePng ? "png" : "webp";
        int size = toIntPhp(ctx.request().getParam("size"), 100);
        boolean mode3d = requestHas(ctx, "3d");
        String mode = mode3d ? "3d" : "2d";
        long ttl = toBool(option.get("enable_avatar_cache")) ? TTL_YEAR : TTL_MINUTE;
        com.neoskin.storage.TextureStorage storage =
                com.neoskin.storage.TextureStorageRuntime.get(paths.textures());

        if (texture == null || !storage.exists(texture.hash())) {
            // 回退头像：misc/textures/avatar{mode}.png → resize(size, size)
            byte[] fallbackBytes = resourceBytes("/misc/textures/avatar" + mode + ".png");
            GdImage image = GdImage.make(fallbackBytes);
            byte[] body = toFormat(image == null ? null : image.resize(size, size), usePng);

            imageResponse(ctx, new com.neoskin.service.ImageCache.Entry(body, contentType(usePng), null,
                    System.currentTimeMillis() + ttl));
            return;
        }

        com.neoskin.service.ImageCache.Entry cached = remember("avatar-" + mode + "-t" + texture.tid()
                + "-s" + size + "-" + format, ttl, () -> {
            byte[] bytes = storage.read(texture.hash());
            GdImage image = mode3d ? minecraft.render3dAvatar(bytes, 25)
                    : minecraft.render2dAvatar(bytes, 25);
            byte[] body = toFormat(image == null ? null : image.resize(size, size), usePng);
            String lastModified = httpDate(
                    Instant.ofEpochMilli(storage.lastModifiedMillis(texture.hash())));

            return new com.neoskin.service.ImageCache.Entry(body, contentType(usePng), lastModified, ttlOf(ttl));
        });

        imageResponse(ctx, cached);
    }

    // ---------------------------------------------------------- 渲染/响应支持

    private static byte[] resourceBytes(String path) {
        try (java.io.InputStream in = TextureController.class.getResourceAsStream(path)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }

    private String contentType(boolean usePng) {
        return usePng ? "image/png" : "image/webp";
    }

    private static long ttlOf(long ttl) {
        return System.currentTimeMillis() + ttl;
    }

    /** {@code Cache::remember}：进程内过期缓存（偏差：非共享存储）。 */
    private com.neoskin.service.ImageCache.Entry remember(String key, long ttl,
            CachedLoader loader) throws Exception {
        com.neoskin.service.ImageCache.Entry stale = imageCache.get(key);
        if (stale != null) {
            return stale;
        }

        com.neoskin.service.ImageCache.Entry fresh = loader.load();
        imageCache.put(key, new com.neoskin.service.ImageCache.Entry(fresh.bytes(),
                fresh.contentType(), fresh.lastModified(), ttlOf(ttl)));

        return fresh;
    }

    @FunctionalInterface
    private interface CachedLoader {
        com.neoskin.service.ImageCache.Entry load() throws Exception;
    }

    /**
     * {@code GdImage} → PNG / WebP 字节。
     *
     * <p>源 {@code $usePNG ? 'png' : 'webp'}：webp 编码器可用时默认输出 webp
     * （quality=100），否则回退 PNG。编码失败也回退 PNG（不让图片端点 500）。</p>
     */
    private static byte[] toFormat(GdImage image, boolean usePng) {
        if (image == null) {
            return new byte[0];
        }
        byte[] png = image.toPng();
        if (usePng || !WEBP_WRITER_AVAILABLE) {
            return png;
        }
        try {
            java.awt.image.BufferedImage buffered =
                    ImageIO.read(new ByteArrayInputStream(png));
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
            if (buffered == null || !writers.hasNext()) {
                return png;
            }
            ImageWriter writer = writers.next();
            try {
                ImageWriteParam params = writer.getDefaultWriteParam();
                if (params.canWriteCompressed()) {
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    // 源 imagewebp 是 lossy（quality=100）；writer 需先选压缩类型
                    String[] types = params.getCompressionTypes();
                    if (types != null && types.length > 0) {
                        String type = java.util.Arrays.asList(types).contains("Lossy")
                                ? "Lossy" : types[0];
                        params.setCompressionType(type);
                    }
                    // gotson/webp-imageio 的 quality 是 0..1（源 imagewebp 的 100 ⇒ 1.0）
                    params.setCompressionQuality(1.0f);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(stream);
                    writer.write(null, new IIOImage(buffered, null, null), params);
                }
                return out.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (Exception e) {
            return png;
        }
    }

    private static boolean requestHas(RoutingContext ctx, String name) {
        for (Map.Entry<String, String> entry : ctx.request().params()) {
            if (name.equals(entry.getKey())) {
                return true;
            }
        }

        return false;
    }

    /** PHP {@code (int)} 语义：取前导数字部分。 */
    private static int toIntPhp(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*[-+]?\\d+").matcher(s);
        if (!m.find()) {
            return 0; // PHP (int)"abc" → 0，但 query 缺省时走 defaultValue
        }

        try {
            return Integer.parseInt(m.group().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code cache.headers:etag;public;max_age=<cache_expire_time>} 中间件的等效：
     * 写 Cache-Control / ETag，并对条件请求回 304
     * （{@code Response::isNotModified} 的 Java 还原）。
     */
    private void imageResponse(RoutingContext ctx, com.neoskin.service.ImageCache.Entry cached) {
        long maxAge = toLong(option.get("cache_expire_time"));
        String etag = etagOf(cached.bytes());

        io.vertx.core.MultiMap headers = ctx.request().headers();
        boolean notModified = false;
        String ifNoneMatch = headers.get("If-None-Match");
        if (etag != null && ifNoneMatch != null) {
            String etagCore = etag.startsWith("\"W/") || etag.startsWith("W/")
                    ? etag.substring(2) : etag;
            for (String given : ifNoneMatch.split(",")) {
                String candidate = given.trim();
                if (candidate.startsWith("W/")) {
                    candidate = candidate.substring(2);
                }
                if (candidate.equals(etagCore) || "*".equals(candidate)) {
                    notModified = true;
                    break;
                }
            }
        } else if (etag == null && cached.lastModified() != null
                && headers.get("If-Modified-Since") != null) {
            notModified = parseHttpDate(headers.get("If-Modified-Since"))
                    >= parseHttpDate(cached.lastModified());
        }

        var response = ctx.response().setStatusCode(notModified ? 304 : 200)
                .putHeader("Cache-Control", "max-age=" + maxAge + ", public");
        if (etag != null) {
            response.putHeader("ETag", etag);
        }
        if (notModified) {
            // Symfony setNotModified：去掉 Content-Type / Content-Length / Last-Modified
            response.end();
            return;
        }
        response.putHeader("Content-Type", cached.contentType());
        if (cached.lastModified() != null) {
            response.putHeader("Last-Modified", cached.lastModified());
        }
        response.putHeader("Content-Length", String.valueOf(cached.bytes().length))
                .end(Buffer.buffer(cached.bytes()));
    }

    /** 自洽 ETag（偏差：PHP 侧为 xxh128，本工程用 SHA-256，HTTP 语义等价）。 */
    private static String etagOf(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }

            return "\"" + sb + "\""; // Symfony setEtag 会给摘要加引号
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseHttpDate(String value) {
        try {
            return java.time.ZonedDateTime
                    .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private String cacheControl() {
        return "max-age=" + toLong(option.get("cache_expire_time")) + ", public";
    }

    /** 文件 mtime → HTTP 日期（UTC）。 */
    private String httpDate(Instant instant) {
        return HTTP_DATE.format(instant.atZone(ZoneOffset.UTC));
    }

    private String httpDate(String value) {
        String s = value.length() > 19 ? value.substring(0, 19) : value;
        try {
            var ldt = java.time.LocalDateTime.parse(s,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return HTTP_DATE.format(ldt.atZone(java.time.ZoneId.of(timezone))
                    .withZoneSameInstant(ZoneOffset.UTC));
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v);
        return !(s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false"));
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            PlayerRepository players, TextureRepository textures, UserRepository users,
            AppPaths paths, String timezone, com.neoskin.service.ImageCache imageCache) {
        TextureController c = new TextureController(option, translator, players, textures, users,
                paths, timezone, imageCache);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\TextureController@json" -> c.json();
            case "App\\Http\\Controllers\\TextureController@texture" -> c.texture();
            case "App\\Http\\Controllers\\TextureController@raw" -> c.raw();
            case "App\\Http\\Controllers\\TextureController@previewByHash" -> c.previewByHash();
            case "App\\Http\\Controllers\\TextureController@preview" -> c.preview();
            case "App\\Http\\Controllers\\TextureController@avatarByPlayer" -> c.avatarByPlayer();
            case "App\\Http\\Controllers\\TextureController@avatarByUser" -> c.avatarByUser();
            case "App\\Http\\Controllers\\TextureController@avatarByHash" -> c.avatarByHash();
            case "App\\Http\\Controllers\\TextureController@avatarByTexture" -> c.avatarByTexture();
            default -> null;
        };
    }
}