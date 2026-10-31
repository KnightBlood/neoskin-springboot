package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.TextureSideEffects;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\SkinlibController} 的移植（第一批：列表 + 详情 JSON）。
 */
public final class SkinlibController {

    private final Option option;
    private final Translator translator;
    private final TextureRepository textures;
    private final UserRepository users;
    private final com.neoskin.web.i18n.JavaScript javaScript;
    private final com.neoskin.service.BlessingFilter filter;
    private final boolean debug;
    private final com.neoskin.config.AppPaths paths;
    private final com.neoskin.db.ClosetRepository closet;
    private final String timezone;
    private final javax.sql.DataSource dataSource;
    private final java.util.function.Supplier<String> clock;

    public SkinlibController(Option option, Translator translator, TextureRepository textures,
            UserRepository users, com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter, boolean debug,
            com.neoskin.config.AppPaths paths, com.neoskin.db.ClosetRepository closet,
            String timezone, javax.sql.DataSource dataSource,
            java.util.function.Supplier<String> clock) {
        this.option = option;
        this.translator = translator;
        this.textures = textures;
        this.users = users;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
        this.paths = paths;
        this.closet = closet;
        this.timezone = timezone;
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** {@code GET skinlib/list}：返回 Laravel {@code LengthAwarePaginator} 的 JSON。 */
    public Handler<RoutingContext> library() {
        return ctx -> {
            try {
                String type = string(RequestInput.input(ctx, "filter"), "skin");
                String keyword = nullableString(RequestInput.input(ctx, "keyword"));
                Integer uploader = integerOrNull(RequestInput.input(ctx, "uploader"));
                String sort = string(RequestInput.input(ctx, "sort"), "time");
                int page = Math.max(1, toInt(RequestInput.input(ctx, "page")));
                int perPage = 20;

                UserRepository.UserRow viewer = currentUser(ctx);
                Integer viewerUid = viewer == null ? null : viewer.uid();
                boolean admin = viewer != null && viewer.isAdmin();

                long total = textures.count(type, keyword, uploader, viewerUid, admin);
                long last = Math.max(1, (total + perPage - 1) / perPage);
                long offset = (long) (page - 1) * perPage;
                List<Map<String, Object>> rows = textures.page(type, keyword, uploader,
                        viewerUid, admin, sort, perPage, (int) offset);

                String path = "http://" + hostOf(ctx) + "/skinlib/list";
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("current_page", page);
                body.put("data", rows);
                body.put("first_page_url", pageUrl(path, 1));
                body.put("from", rows.isEmpty() ? null : offset + 1);
                body.put("last_page", last);
                body.put("last_page_url", pageUrl(path, (int) last));
                body.put("links", com.neoskin.web.pagination.LengthAwarePaginator
                        .of(rows, total, perPage, page, java.util.Map.of(), path).links());
                body.put("next_page_url", page < last ? pageUrl(path, page + 1) : null);
                body.put("path", path);
                body.put("per_page", perPage);
                body.put("prev_page_url", page > 1 ? pageUrl(path, page - 1) : null);
                body.put("to", rows.isEmpty() ? null : offset + rows.size());
                body.put("total", total);

                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code GET skinlib/info/{texture}} / {@code GET texture/{texture}}。 */
    public Handler<RoutingContext> info() {
        return ctx -> {
            try {
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                UserRepository.UserRow viewer = currentUser(ctx);
                if (!texture.isPublic()) {
                    boolean allowed = viewer != null
                            && (viewer.uid() == texture.uploader() || viewer.isAdmin());
                    if (!allowed) {
                        int status = toInt(option.get("status_code_for_private", 404));
                        ctx.fail(status == 404 ? 404 : 403);
                        return;
                    }
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("tid", texture.tid());
                body.put("name", texture.name());
                body.put("type", texture.type());
                body.put("hash", texture.hash());
                body.put("size", texture.size());
                body.put("uploader", texture.uploader());
                body.put("public", texture.isPublic());
                body.put("upload_at", texture.uploadAt());
                body.put("likes", texture.likes());
                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // show / upload / handleUpload / delete / privacy / rename / type：待模板与图片处理
    public Handler<RoutingContext> show() {
        return ctx -> {
            try {
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                UserRepository.UserRow viewer = currentUser(ctx);
                if (!texture.isPublic()) {
                    boolean allowed = viewer != null
                            && (viewer.uid() == texture.uploader() || viewer.isAdmin());
                    if (!allowed) {
                        ctx.fail(toInt(option.get("status_code_for_private", 404)) == 404 ? 404 : 403);
                        return;
                    }
                }
                com.neoskin.storage.TextureStorage storage =
                        com.neoskin.storage.TextureStorageRuntime.get(paths.textures());
                if (!storage.exists(texture.hash())) {
                    if (toBool(option.get("auto_del_invalid_texture"))) {
                        textures.deleteByTid(texture.tid());
                    }
                    ctx.fail(404);
                    return;
                }

                UserRepository.UserRow uploader = users.findById(texture.uploader());
                List<Map<String, Object>> badges = new ArrayList<>();
                if (uploader != null && uploader.isAdmin()) {
                    Map<String, Object> badge = new LinkedHashMap<>();
                    badge.put("text", "STAFF");
                    badge.put("color", "primary");
                    badges.add(badge);
                }

                String locale = translator.getLocale();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("download", toBool(option.get("allow_downloading_texture")));
                extra.put("currentUid", viewer == null ? 0 : viewer.uid());
                extra.put("admin", viewer != null && viewer.isAdmin());
                extra.put("inCloset", viewer != null && users.inCloset(viewer.uid(),
                        (int) texture.tid()));
                extra.put("uploaderExists", uploader != null);
                extra.put("nickname", uploader == null
                        ? translator.trans("general.unexistent-user", Map.of(), locale)
                        : uploader.nickname());
                extra.put("report", toInt(option.get("reporter_score_modification", 0)));
                extra.put("badges", badges);

                Map<String, Object> vars = pageVars(ctx, viewer, texture.name(), locale, "skinlib");
                vars.put("gridRows", List.of(
                        List.of(
                                col("md-8", "shared/previewer"),
                                col("md-4", "skinlib/widgets/show/side"))));
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "skinlib/show", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code GET skinlib/upload}。 */
    public Handler<RoutingContext> upload() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String locale = translator.getLocale();
                Object regexpObj = option.get("texture_name_regexp");
                String regexp = regexpObj == null ? "" : String.valueOf(regexpObj);
                String rule = regexp.isEmpty()
                        ? translator.trans("skinlib.upload.name-rule", Map.of(), locale)
                        : translator.trans("skinlib.upload.name-rule-regexp",
                                Map.of("regexp", regexp), locale);
                Object contentPolicy = option.getLocalized("content_policy");

                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("rule", rule);
                extra.put("privacyNotice", translator.trans("skinlib.upload.private-score-notice",
                        Map.of("score", String.valueOf(option.get("private_score_per_storage"))),
                        locale));
                extra.put("score", user.score());
                extra.put("scorePublic", toInt(option.get("score_per_storage")));
                extra.put("scorePrivate", toInt(option.get("private_score_per_storage")));
                extra.put("closetItemCost", toInt(option.get("score_per_closet_item")));
                extra.put("award", toInt(option.get("score_award_per_texture", 0)));
                extra.put("contentPolicy", com.neoskin.service.MarkdownLite.render(
                        contentPolicy == null ? "" : String.valueOf(contentPolicy)));

                Map<String, Object> vars = pageVars(ctx, user,
                        translator.trans("skinlib.upload.title", Map.of(), locale), locale,
                        "skinlib");
                vars.put("gridRows", List.of(
                        List.of(
                                col("md-6", "skinlib/widgets/upload/input"),
                                col("md-6", "shared/previewer"))));
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "skinlib/upload", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code POST texture}。 */
    public Handler<RoutingContext> handleUpload() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                com.neoskin.web.http.UploadedFile file = RequestInput.file(ctx, "file");

                Validator v = validator(ctx);
                Object regexpObj = option.get("texture_name_regexp");
                String regexp = regexpObj == null ? "" : String.valueOf(regexpObj);
                v.addRule("name", "required");
                if (regexp.isEmpty()) {
                    v.addRule("name", "string");
                } else {
                    v.addRule("name", "regex:" + regexp);
                }
                v.addRule("file", new FileRequiredRule(file, translator));
                v.addRule("file", new MimesPngRule(file, translator));
                v.addRule("file", new FileMaxSizeRule(file,
                        toInt(option.get("max_upload_file_size")), translator));
                v.addRule("type", "required");
                v.addRule("type", "in:steve,alex,cape");
                v.addRule("public", "required");
                v.addRule("public", "boolean");
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                Map<String, Object> data = v.validated();
                String name = String.valueOf(data.get("name"));
                String type = String.valueOf(data.get("type"));
                Object publicRaw = data.get("public");
                boolean isPublic = publicRaw instanceof Boolean b ? b
                        : "1".equals(String.valueOf(publicRaw));

                if (file == null || !file.isValid()) {
                    json(ctx, 200, message(1, genericInvalid(ctx)));
                    return;
                }

                // getimagesize 尺寸检查
                com.neoskin.service.PngSanitizer.Size sizeObj =
                        com.neoskin.service.PngSanitizer.size(file.bytes());
                if (sizeObj == null) {
                    sizeObj = new com.neoskin.service.PngSanitizer.Size(0, 0);
                }
                int width = sizeObj.width();
                int height = sizeObj.height();
                int maxWidth = toInt(option.get("max_texture_width", 8192));
                if (width > maxWidth) {
                    json(ctx, 200, message(1, translator.trans("skinlib.upload.too-wide",
                            Map.of("width", String.valueOf(width),
                                    "maxWidth", String.valueOf(maxWidth)),
                            translator.getLocale())));
                    return;
                }
                if (width % 64 != 0 || height % 32 != 0) {
                    json(ctx, 200, message(1, invalidSizeMessage(ctx, type, width, height)));
                    return;
                }
                double ratio = (double) width / (double) height;
                if ("steve".equals(type) || "alex".equals(type)) {
                    if ((ratio != 2 && ratio != 1) || ("alex".equals(type) && ratio == 2)) {
                        json(ctx, 200, message(1, invalidSizeMessage(ctx, "skin", width, height)));
                        return;
                    }
                } else if ("cape".equals(type)) {
                    if (ratio != 2) {
                        json(ctx, 200, message(1, invalidSizeMessage(ctx, "cape", width, height)));
                        return;
                    }
                }

                byte[] sanitized = com.neoskin.service.PngSanitizer.sanitize(file.bytes());
                if (sanitized == null) {
                    json(ctx, 200, message(1, invalidSizeMessage(ctx, type, width, height)));
                    return;
                }
                String hash = sha256Hex(sanitized);

                TextureRepository.Row duplicated = textures.findPublicOrMine(hash, user.uid());
                if (duplicated != null) {
                    Map<String, Object> body = message(2,
                            translator.trans("skinlib.upload.repeated", Map.of(),
                                    translator.getLocale()));
                    Map<String, Object> dup = new LinkedHashMap<>();
                    dup.put("tid", duplicated.tid());
                    body.put("data", dup);
                    json(ctx, 200, body);
                    return;
                }

                long fileSize = java.math.BigDecimal.valueOf(sanitized.length)
                        .divide(java.math.BigDecimal.valueOf(1024), 0,
                                java.math.RoundingMode.CEILING)
                        .longValueExact();
                int cost = (int) fileSize
                        * (isPublic
                                ? toInt(option.get("score_per_storage"))
                                : toInt(option.get("private_score_per_storage")));
                cost += toInt(option.get("score_per_closet_item"));
                cost -= toInt(option.get("score_award_per_texture", 0));
                if (user.score() < cost) {
                    json(ctx, 200, message(1, translator.trans("skinlib.upload.lack-score",
                            Map.of(), translator.getLocale())));
                    return;
                }

                long newTid = textures.insert(name, type, hash, fileSize, user.uid(), isPublic,
                        com.neoskin.support.PhpDate.now(timezone));

                com.neoskin.storage.TextureStorage storage =
                        com.neoskin.storage.TextureStorageRuntime.get(paths.textures());
                if (!storage.exists(hash)) {
                    storage.write(hash, sanitized);
                }
                users.updateScore(user.uid(), user.score() - cost);
                closet.attach(user.uid(), newTid, name);

                Map<String, Object> body = message(0,
                        translator.trans("skinlib.upload.success", Map.of("name", name),
                                translator.getLocale()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tid", newTid);
                body.put("data", payload);
                json(ctx, 200, body);
            } catch (SQLException | java.io.IOException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code DELETE texture/{texture}}。 */
    public Handler<RoutingContext> delete() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                if (!ownedBy(user, texture)) {
                    int status = permissionDenied(ctx);
                    json(ctx, status, message(1,
                            translator.trans("skinlib.no-permission", Map.of(),
                                    translator.getLocale())));
                    return;
                }

                if (textures.countByHash(texture.hash()) == 1) {
                    com.neoskin.storage.TextureStorageRuntime.get(paths.textures())
                            .delete(texture.hash());
                }
                textures.deleteByTid(tid);
                // texture.deleted → UpdateScoreForDeletedTexture + ResetPlayers + CleanUpCloset
                TextureSideEffects.onDeleted(dataSource, option, texture);

                json(ctx, 200, message(0,
                        translator.trans("skinlib.delete.success", Map.of(),
                                translator.getLocale())));
            } catch (SQLException | java.io.IOException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT texture/{texture}/privacy}。 */
    public Handler<RoutingContext> privacy() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                if (!ownedBy(user, texture)) {
                    int status = permissionDenied(ctx);
                    json(ctx, status, message(1,
                            translator.trans("skinlib.no-permission", Map.of(),
                                    translator.getLocale())));
                    return;
                }

                UserRepository.UserRow uploader = users.findById(texture.uploader());
                if (uploader == null) {
                    ctx.fail(500);
                    return;
                }
                int publicScore = toInt(option.get("score_per_storage"));
                int privateScore = toInt(option.get("private_score_per_storage"));
                int scoreDiff = texture.size() == 0 ? 0
                        : (int) texture.size()
                                * (privateScore - publicScore) * (texture.isPublic() ? -1 : 1);
                if (texture.isPublic()
                        && toBool(option.get("take_back_scores_after_deletion", true))) {
                    scoreDiff -= toInt(option.get("score_award_per_texture", 0));
                }
                if (uploader.score() + scoreDiff < 0) {
                    json(ctx, 200, message(1, translator.trans("skinlib.upload.lack-score",
                            Map.of(), translator.getLocale())));
                    return;
                }
                if (!texture.isPublic()) {
                    TextureRepository.Row dupPublic = textures.findPublicByHash(texture.hash());
                    if (dupPublic != null) {
                        Map<String, Object> body = message(2,
                                translator.trans("skinlib.upload.repeated", Map.of(),
                                        translator.getLocale()));
                        Map<String, Object> dup = new LinkedHashMap<>();
                        dup.put("tid", dupPublic.tid());
                        body.put("data", dup);
                        json(ctx, 200, body);
                        return;
                    }
                }

                users.updateScore(uploader.uid(), uploader.score() + scoreDiff);
                textures.updatePublic(tid, !texture.isPublic());
                TextureRepository.Row updated = textures.findByTid(tid);
                if (updated != null) {
                    // texture.privacy.updated → ResetPlayers + CleanUpCloset（仅转为私有时生效）
                    TextureSideEffects.onPrivacyUpdated(dataSource, option, updated);
                }

                json(ctx, 200, message(0,
                        translator.trans("skinlib.privacy.success",
                                Map.of("privacy", translator.trans(
                                        updated != null && updated.isPublic()
                                                ? "general.public" : "general.private",
                                        Map.of(), translator.getLocale())),
                                translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT texture/{texture}/name}。 */
    public Handler<RoutingContext> rename() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                if (!ownedBy(user, texture)) {
                    int status = permissionDenied(ctx);
                    json(ctx, status, message(1,
                            translator.trans("skinlib.no-permission", Map.of(),
                                    translator.getLocale())));
                    return;
                }

                Validator v = validator(ctx);
                Object regexpObj = option.get("texture_name_regexp");
                String regexp = regexpObj == null ? "" : String.valueOf(regexpObj);
                v.addRule("name", "required");
                if (regexp.isEmpty()) {
                    v.addRule("name", "string");
                } else {
                    v.addRule("name", "regex:" + regexp);
                }
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                String name = String.valueOf(v.validated().get("name"));
                textures.updateName(tid, name);

                json(ctx, 200, message(0,
                        translator.trans("skinlib.rename.success", Map.of("name", name),
                                translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT texture/{texture}/type}。 */
    public Handler<RoutingContext> type() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long tid = toLong(ctx.pathParam("texture"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    ctx.fail(404);
                    return;
                }
                if (!ownedBy(user, texture)) {
                    int status = permissionDenied(ctx);
                    json(ctx, status, message(1,
                            translator.trans("skinlib.no-permission", Map.of(),
                                    translator.getLocale())));
                    return;
                }

                Validator v = validator(ctx);
                v.addRule("type", "required");
                v.addRule("type", "in:steve,alex,cape");
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                String type = String.valueOf(v.validated().get("type"));
                textures.updateType(tid, type);

                json(ctx, 200, message(0,
                        translator.trans("skinlib.model.success", Map.of("model", type),
                                translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }


    // ============================================================ helpers

    /** 422 校验失败（与其他 JSON 控制器一致的响应形状）。 */
    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", com.neoskin.web.validation.Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
    }

    private Validator validator(RoutingContext ctx) {
        return new Validator(inputMap(ctx), translator, translator.getLocale(), option,
                new com.neoskin.web.validation.JdbcPresenceVerifier(dataSource));
    }

    private Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object query = ctx.get(RequestInput.QUERY_KEY);
        if (query instanceof Map<?, ?> q) {
            q.forEach((k, val) -> merged.put(String.valueOf(k), val));
        }
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> b) {
            b.forEach((k, val) -> merged.put(String.valueOf(k), val));
        }
        return merged;
    }

    private static Map<String, Object> message(int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", msg);
        return body;
    }

    /** {@code uploader != user && !admin} → 403（构造函数闭包）。 */
    private static boolean ownedBy(UserRepository.UserRow user, TextureRepository.Row texture) {
        return texture.uploader() == user.uid() || user.isAdmin();
    }

    /** 中间件拒绝时的状态码（源只返回 403）。 */
    private static int permissionDenied(RoutingContext ctx) {
        return 403;
    }

    /** {@code getimagesize} 无法解析时的兜底（源里是 PHP 由 false[0] 产生的 500）。 */
    private String genericInvalid(RoutingContext ctx) {
        return translator.trans("skinlib.upload.invalid-size",
                Map.of("type", translator.trans("general.skin", Map.of(),
                        translator.getLocale()),
                        "width", "0", "height", "0"),
                translator.getLocale());
    }

    private String invalidSizeMessage(RoutingContext ctx, String type, int width, int height) {
        String typeName = "cape".equals(type)
                ? translator.trans("general.cape", Map.of(), translator.getLocale())
                : translator.trans("general.skin", Map.of(), translator.getLocale());
        return translator.trans("skinlib.upload.invalid-size",
                Map.of("type", typeName, "width", String.valueOf(width),
                        "height", String.valueOf(height)),
                translator.getLocale());
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code 'file => required'}（上传文件存在性）。 */
    private record FileRequiredRule(com.neoskin.web.http.UploadedFile file,
            Translator translator) implements com.neoskin.web.validation.Rule {

        @Override
        public boolean passes(String attribute, Object value, Validator validator) {
            return file != null && file.isValid() && file.size() >= 0;
        }

        @Override
        public String message() {
            return translator.trans("validation.required",
                    Map.of("attribute", "file"), translator.getLocale());
        }
    }

    /** {@code mimes:png}（按文件内容嗅探，与 Laravel 的 MimeTypeGuasser 等效）。 */
    private record MimesPngRule(com.neoskin.web.http.UploadedFile file,
            Translator translator) implements com.neoskin.web.validation.Rule {

        @Override
        public boolean passes(String attribute, Object value, Validator validator) {
            return file != null
                    && com.neoskin.service.PngSanitizer.isPng(file.bytes() == null
                            ? new byte[0] : file.bytes());
        }

        @Override
        public String message() {
            return translator.trans("validation.mimes",
                    Map.of("attribute", "file", "values", "png"), translator.getLocale());
        }
    }

    /** {@code max:<KB>}（对文件按 KB 比较，Laravel UploadedFile 语义）。 */
    private record FileMaxSizeRule(com.neoskin.web.http.UploadedFile file, int maxKb,
            Translator translator) implements com.neoskin.web.validation.Rule {

        @Override
        public boolean passes(String attribute, Object value, Validator validator) {
            long kb = (long) Math.ceil(file.size() / 1024.0);
            return kb <= maxKb;
        }

        @Override
        public String message() {
            return translator.trans("validation.max.file",
                    Map.of("attribute", "file", "max", String.valueOf(maxKb)),
                    translator.getLocale());
        }
    }


    private Map<String, Object> pageVars(RoutingContext ctx, UserRepository.UserRow user,
            String title, String locale, String scope) throws Exception {
        String host = hostOf(ctx);
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        com.neoskin.web.view.ViewContext vc = new com.neoskin.web.view.ViewContext(
                translator, option, new com.neoskin.web.PhpUrlGenerator(urlRoot, "http"),
                com.neoskin.web.RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = com.neoskin.web.view.PageView.variables(option, translator,
                javaScript, filter, user, locale, urlRoot, ctx.request().path(), debug, scope, vc);
        vars.put("pageTitle", title);
        vars.put("errors", new AuthController.ErrorsView(
                com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                        session == null ? null : session.get("errors"))));
        return vars;
    }

    private void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = com.neoskin.web.view.ViewEngine.render(template, java.util.Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    private static Map<String, Object> col(String css, String... widgets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", css);
        m.put("widgets", List.of(widgets));
        return m;
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v);
        return !(s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false"));
    }

    private UserRepository.UserRow currentUser(RoutingContext ctx) throws SQLException {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            return null;
        }
        return new com.neoskin.web.auth.SessionGuard(session, users).user();
    }

    private static List<Map<String, Object>> links(String path, int current, int last) {
        List<Map<String, Object>> links = new ArrayList<>();
        links.add(link(current > 1 ? pageUrl(path, current - 1) : null,
                "&laquo; Previous", current > 1 ? current - 1 : null, false));
        for (int p = 1; p <= last; p++) {
            links.add(link(pageUrl(path, p), String.valueOf(p), p, p == current));
        }
        links.add(link(current < last ? pageUrl(path, current + 1) : null,
                "Next &raquo;", current < last ? current + 1 : null, false));
        return links;
    }

    private static Map<String, Object> link(String url, String label, Integer page,
            boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("label", label);
        m.put("page", page);
        m.put("active", active);
        return m;
    }

    private static String pageUrl(String path, int page) {
        return path + "?page=" + page;
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    private static String string(Object v, String fallback) {
        return v == null || String.valueOf(v).isEmpty() ? fallback : String.valueOf(v);
    }

    private static String nullableString(Object v) {
        return v == null || String.valueOf(v).isEmpty() ? null : String.valueOf(v);
    }

    private static Integer integerOrNull(Object v) {
        if (v == null || String.valueOf(v).isEmpty()) {
            return null;
        }
        return toInt(v);
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
            TextureRepository textures, UserRepository users,
            com.neoskin.web.i18n.JavaScript javaScript, com.neoskin.service.BlessingFilter filter,
            boolean debug, com.neoskin.config.AppPaths paths,
            com.neoskin.db.ClosetRepository closet, String timezone,
            javax.sql.DataSource dataSource, java.util.function.Supplier<String> clock) {
        SkinlibController c = new SkinlibController(option, translator, textures, users,
                javaScript, filter, debug, paths, closet, timezone, dataSource, clock);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\SkinlibController@library" -> c.library();
            case "App\\Http\\Controllers\\SkinlibController@info" -> c.info();
            case "App\\Http\\Controllers\\SkinlibController@show" -> c.show();
            case "App\\Http\\Controllers\\SkinlibController@upload" -> c.upload();
            case "App\\Http\\Controllers\\SkinlibController@handleUpload" -> c.handleUpload();
            case "App\\Http\\Controllers\\SkinlibController@delete" -> c.delete();
            case "App\\Http\\Controllers\\SkinlibController@privacy" -> c.privacy();
            case "App\\Http\\Controllers\\SkinlibController@rename" -> c.rename();
            case "App\\Http\\Controllers\\SkinlibController@type" -> c.type();
            default -> null;
        };
    }
}