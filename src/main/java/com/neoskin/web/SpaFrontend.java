package com.neoskin.web;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.ReportRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Communications;
import com.neoskin.service.Option;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.i18n.Locales;
import com.neoskin.web.middleware.StartSession;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * SPA 壳与契约 v1 bootstrap（见 NeoSkinFrontend/docs/BACKEND-CONTRACT.md）。
 *
 * <p>挂载在 PHP 路由表之前，复用 web 组中间件（EncryptCookies + StartSession），
 * 因此可以直接读会话、CSRF token 与登录态。</p>
 */
public final class SpaFrontend implements Handler<RoutingContext> {

    private static final int CONTRACT_VERSION = 1;

    private final Option option;

    private final UserRepository users;

    private final Path publicDir;

    private final String version;

    private final NotificationRepository notifications;

    private final CommunicationRepository communicationsRepo;

    private final ReportRepository reports;

    private final TextureRepository textures;

    private final PlayerRepository players;

    private final Communications communications;

    private final Handler<RoutingContext> updateInfoHandler;

    private final String timezone;

    /** {@code FRONTEND_MODE=legacy|next}。 */
    public static boolean nextMode() {
        return "next".equalsIgnoreCase(System.getenv("FRONTEND_MODE"));
    }

    public SpaFrontend(Option option, UserRepository users, Path publicDir, String version) {
        this(option, users, publicDir, version, null, null, null, null, null, null, null,
                "Asia/Shanghai");
    }

    public SpaFrontend(Option option, UserRepository users, Path publicDir, String version,
            NotificationRepository notifications, ReportRepository reports,
            TextureRepository textures, PlayerRepository players,
            CommunicationRepository communicationRepository, Communications communications,
            Handler<RoutingContext> updateInfoHandler, String timezone) {
        this.option = option;
        this.users = users;
        this.publicDir = publicDir;
        this.version = version;
        this.notifications = notifications;
        this.communicationsRepo = communicationRepository;
        this.reports = reports;
        this.textures = textures;
        this.players = players;
        this.communications = communications;
        this.updateInfoHandler = updateInfoHandler;
        this.timezone = timezone;
    }

    @Override
    public void handle(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        if (path.equals("/api/bootstrap")) {
            bootstrap(ctx);
            return;
        }
        if (isContractPath(path)) {
            contractApi(ctx, path);
            return;
        }
        if (!path.startsWith("/app-next/")) {
            ctx.next();
            return;
        }

        String[] segments = path.substring("/app-next/".length()).split("/", -1);
        String mode = segments.length > 0 ? segments[0] : "";
        if (!mode.equals("site") && !mode.equals("admin")) {
            ctx.response().setStatusCode(404).end("Not Found");
            return;
        }

        StringBuilder relative = new StringBuilder();
        for (int i = 1; i < segments.length; i++) {
            if (i > 1) {
                relative.append('/');
            }
            relative.append(segments[i]);
        }
        String target = relative.toString();
        if (target.isEmpty() || target.endsWith("/")) {
            target += "index.html";
        }

        Path base = publicDir.resolve("app-next").resolve(mode).normalize();
        Path file = base.resolve(target).normalize();
        if (!file.startsWith(base)) {
            ctx.response().setStatusCode(404).end("Not Found");
            return;
        }

        if (Files.isRegularFile(file)) {
            if (target.endsWith(".html")) {
                shell(ctx, mode, file);
                return;
            }
            ctx.response().putHeader("Cache-Control", "public, max-age=31536000, immutable");
            ctx.response().sendFile(file.toAbsolutePath().toString());
            return;
        }

        int dot = target.lastIndexOf('.');
        int slash = target.lastIndexOf('/');
        if (dot > slash) {
            ctx.response().setStatusCode(404).end("Not Found");
            return;
        }

        Path index = base.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            ctx.response().setStatusCode(404)
                    .end("app-next 前端产物缺失：请先运行 pnpm -r build 并执行 scripts/publish.ps1");
            return;
        }
        shell(ctx, mode, index);
    }

    private static boolean isContractPath(String path) {
        return path.equals("/api/user/inbox")
                || path.startsWith("/api/user/inbox/")
                || path.equals("/api/user/reports")
                || path.equals("/api/admin/dashboard")
                || path.equals("/api/admin/update/info")
                || path.startsWith("/api/admin/options/")
                || path.equals("/api/admin/communications")
                || path.startsWith("/api/admin/communications/");
    }

    private void contractApi(RoutingContext ctx, String path) {
        if (!"GET".equalsIgnoreCase(ctx.request().method().name())) {
            ctx.next();
            return;
        }
        UserRepository.UserRow user = currentUser(ctx);
        if (user == null) {
            writeJson(ctx, 401, new PhpJson.Array().with("message", "Unauthenticated."));
            return;
        }

        try {
            if (path.equals("/api/admin/update/info")) {
                if (!user.isAdmin()) {
                    writeJson(ctx, 403, new PhpJson.Array().with("message", "Forbidden"));
                    return;
                }
                updateInfoHandler.handle(ctx);
                return;
            }
            if (path.startsWith("/api/admin/options/")) {
                writeOptionsGroup(ctx, path, user);
                return;
            }
            if (path.equals("/api/admin/communications")
                    || path.startsWith("/api/admin/communications/")) {
                writeCommunications(ctx, path, user);
                return;
            }
            if (path.equals("/api/admin/dashboard")) {
                writeDashboard(ctx, user);
                return;
            }
            if (path.equals("/api/user/reports")) {
                writeReports(ctx, user);
                return;
            }
            if (path.equals("/api/user/inbox")) {
                writeInbox(ctx, user);
                return;
            }
            String id = URLDecoder.decode(path.substring("/api/user/inbox/".length()),
                    StandardCharsets.UTF_8);
            if (id.isEmpty() || id.contains("/")) {
                notFound(ctx);
                return;
            }
            writeMessage(ctx, user, id);
        } catch (java.sql.SQLException e) {
            ctx.response().setStatusCode(500).end("Internal Server Error");
        }
    }

    private static final Map<String, List<String>> OPTION_GROUPS = Map.of(
            "options", List.of(
                    "site_name", "site_description", "site_url", "register_with_player_name",
                    "require_verification", "regs_per_ip", "max_upload_file_size",
                    "max_texture_width", "player_name_rule", "custom_player_name_regexp",
                    "player_name_length_min", "player_name_length_max",
                    "auto_del_invalid_texture", "allow_downloading_texture",
                    "status_code_for_private", "texture_name_regexp", "content_policy",
                    "meta_keywords", "meta_description", "meta_extras", "recaptcha_sitekey",
                    "recaptcha_secretkey", "recaptcha_invisible", "update_source",
                    "plugin_market_source"),
            "customize", List.of(
                    "home_pic_url", "favicon_url", "transparent_navbar", "hide_intro",
                    "fixed_bg", "copyright_prefer", "copyright_text", "custom_css",
                    "custom_js", "navbar_color", "sidebar_color"),
            "score", List.of(
                    "score_per_storage", "private_score_per_storage", "score_per_closet_item",
                    "return_score", "score_per_player", "user_initial_score",
                    "reporter_score_modification", "reporter_reward_score", "sign_gap_time",
                    "sign_after_zero", "score_award_per_texture",
                    "take_back_scores_after_deletion", "score_award_per_like"),
            "resource", List.of(
                    "force_ssl", "auto_detect_asset_url", "cdn_address", "cache_expire_time",
                    "enable_avatar_cache", "enable_preview_cache",
                    "allow_downloading_texture"));

    private static final List<String> BOOL_OPTIONS = List.of(
            "transparent_navbar", "hide_intro", "fixed_bg", "register_with_player_name",
            "require_verification", "auto_del_invalid_texture", "allow_downloading_texture",
            "recaptcha_invisible", "return_score", "sign_after_zero",
            "take_back_scores_after_deletion", "enable_avatar_cache", "enable_preview_cache",
            "force_ssl", "auto_detect_asset_url");

    private void writeCommunications(RoutingContext ctx, String path,
            UserRepository.UserRow user) throws java.sql.SQLException {
        if (!user.isAdmin()) {
            writeJson(ctx, 403, new PhpJson.Array().with("message", "Forbidden"));
            return;
        }

        if (path.equals("/api/admin/communications")) {
            int page = positive(ctx.request().getParam("page"), 1);
            int perPage = 15;
            long total = communicationsRepo.countMessages();
            List<Object> items = new ArrayList<>();
            for (CommunicationRepository.Row row : communicationsRepo.messagePage(
                    perPage, (long) (page - 1) * perPage)) {
                items.add(new PhpJson.Array()
                        .with("id", row.id)
                        .with("title", row.title)
                        .with("kind", row.kind)
                        .with("status", row.status)
                        .with("read_count", notifications.countRead(row.id))
                        .with("delivery_count", notifications.countDelivery(row.id))
                        .with("updated_at", row.updatedAt));
            }
            writeJson(ctx, 200, new PhpJson.Array()
                    .with("data", items)
                    .with("current_page", page)
                    .with("last_page", lastPage(total, perPage))
                    .with("per_page", perPage)
                    .with("total", total));
            return;
        }

        long id;
        try {
            id = Long.parseLong(path.substring("/api/admin/communications/".length()));
        } catch (NumberFormatException e) {
            notFound(ctx);
            return;
        }
        CommunicationRepository.Row row = communicationsRepo.find(id);
        if (row == null) {
            notFound(ctx);
            return;
        }

        Object blocks = row.blocks == null ? new ArrayList<>() : row.blocks;
        Map<String, Object> audience = new java.util.LinkedHashMap<>();
        audience.put("receiver", "all");
        if (row.audience instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                audience.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        writeJson(ctx, 200, new PhpJson.Array()
                .with("id", row.id)
                .with("title", row.title)
                .with("kind", row.kind)
                .with("status", row.status)
                .with("locale", row.locale)
                .with("editor", row.editor == null || row.editor.isEmpty() ? "blocks" : row.editor)
                .with("source", row.source == null ? "" : row.source)
                .with("blocks", blocks)
                .with("audience", audience));
    }

    private void writeOptionsGroup(RoutingContext ctx, String path, UserRepository.UserRow user)
            throws java.sql.SQLException {
        if (!user.isAdmin()) {
            writeJson(ctx, 403, new PhpJson.Array().with("message", "Forbidden"));
            return;
        }

        String group = path.substring("/api/admin/options/".length());
        List<String> keys = OPTION_GROUPS.get(group);
        if (keys == null) {
            notFound(ctx);
            return;
        }

        String method = ctx.request().method().name();
        if (!"GET".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            ctx.response().setStatusCode(405).end();
            return;
        }

        String locale = Option.currentLocale();
        if (locale == null || locale.isEmpty()) {
            locale = Locales.APP_LOCALE;
        }

        if ("GET".equalsIgnoreCase(method)) {
            PhpJson.Array values = new PhpJson.Array();
            for (String key : keys) {
                boolean localized = group.equals("options")
                        && (key.equals("site_name") || key.equals("site_description")
                        || key.equals("content_policy"));
                Object value = localized
                        ? option.getLocalized(key, "")
                        : option.getString(key, "");
                values.with(key, value == null ? "" : value);
            }
            if (group.equals("score")) {
                String[] parts = option.getString("sign_score", "").split(",");
                values.with("sign_score_from", parts.length > 0 ? parts[0].trim() : "");
                values.with("sign_score_to", parts.length > 1 ? parts[1].trim() : "");
            }
            writeJson(ctx, 200, values);
            return;
        }

        io.vertx.core.json.JsonObject body = ctx.body() == null ? null : ctx.body().asJsonObject();
        if (body == null) {
            body = new io.vertx.core.json.JsonObject();
        }
        if (group.equals("options")) {
            for (String key : List.of("site_name", "site_description", "content_policy")) {
                String value = body.getString(key, "");
                if (!value.isEmpty()) {
                    option.set(key + "_" + locale, value);
                }
            }
        }
        if (group.equals("score")
                && (body.containsKey("sign_score_from") || body.containsKey("sign_score_to"))) {
            option.set("sign_score", body.getString("sign_score_from", "")
                    + "," + body.getString("sign_score_to", ""));
        }

        for (String key : keys) {
            if (group.equals("options")
                    && (key.equals("site_name") || key.equals("site_description")
                    || key.equals("content_policy"))) {
                continue;
            }
            if (BOOL_OPTIONS.contains(key)) {
                option.set(key, truthy(body.getValue(key)) ? "true" : "false");
                continue;
            }
            if (!body.containsKey(key)) {
                continue;
            }
            Object raw = body.getValue(key);
            String value = raw == null ? "" : String.valueOf(raw).trim();
            if (key.equals("site_url")) {
                while (value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (value.endsWith("/index.php")) {
                    value = value.substring(0, value.length() - 10);
                }
            } else if (key.equals("cdn_address")) {
                while (value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
            }
            option.set(key, value);
        }

        writeJson(ctx, 200, new PhpJson.Array().with("code", 0).with("message", "保存成功"));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return text.equals("1") || text.equals("true") || text.equals("on") || text.equals("yes");
    }

    private void writeDashboard(RoutingContext ctx, UserRepository.UserRow user)
            throws java.sql.SQLException {
        if (!user.isAdmin()) {
            writeJson(ctx, 403, new PhpJson.Array().with("message", "Forbidden"));
            return;
        }
        long storage = textures.sumSize();
        writeJson(ctx, 200, new PhpJson.Array()
                .with("users", users.countAll())
                .with("players", players.countAll())
                .with("textures", textures.countAll())
                .with("storage", storage)
                .with("storageHuman", storageDisplay(storage)));
    }

    private static String storageDisplay(long storage) {
        return storage > 1024
                ? String.format(java.util.Locale.ROOT, "%.1fMB", storage / 1024.0)
                : storage + "KB";
    }

    private void writeInbox(RoutingContext ctx, UserRepository.UserRow user) throws java.sql.SQLException {
        int page = positive(ctx.request().getParam("page"), 1);
        int perPage = Math.min(100, Math.max(1, positive(ctx.request().getParam("per_page"), 10)));
        long total = notifications.countInbox(user.uid());
        long unread = notifications.countUnread(user.uid());

        List<Object> items = new ArrayList<>();
        for (NotificationRepository.Row row : notifications.inboxPage(user.uid(), perPage,
                (long) (page - 1) * perPage)) {
            if (!communications.visible(row)) {
                continue;
            }
            Map<String, Object> data = row.data();
            items.add(new PhpJson.Array()
                    .with("id", row.id)
                    .with("title", communications.title(row))
                    .with("preview", preview(communications.content(row), 120))
                    .with("format", data.get("format") == null ? "html" : data.get("format"))
                    .with("read_at", emptyToNull(row.readAt))
                    .with("created_at", row.createdAt)
                    .with("communication_id", communicationId(data)));
        }

        writeJson(ctx, 200, new PhpJson.Array()
                .with("data", items)
                .with("current_page", page)
                .with("last_page", lastPage(total, perPage))
                .with("per_page", perPage)
                .with("total", total)
                .with("unread", unread));
    }

    private void writeMessage(RoutingContext ctx, UserRepository.UserRow user, String id)
            throws java.sql.SQLException {
        NotificationRepository.Row row = notifications.findByNotifiableAndId(user.uid(), id);
        if (row == null || !communications.visible(row)) {
            notFound(ctx);
            return;
        }

        String readAt = row.readAt;
        if (readAt == null || readAt.isEmpty()) {
            readAt = PhpDate.now(timezone);
            notifications.markAsRead(id, readAt);
        }
        Map<String, Object> data = row.data();

        writeJson(ctx, 200, new PhpJson.Array()
                .with("code", 0)
                .with("message", "")
                .with("data", new PhpJson.Array()
                        .with("id", row.id)
                        .with("title", communications.title(row))
                        .with("content", communications.content(row))
                        .with("format", data.get("format") == null ? "html" : data.get("format"))
                        .with("read_at", readAt)
                        .with("created_at", row.createdAt)
                        .with("communication_id", communicationId(data))));
    }

    private void writeReports(RoutingContext ctx, UserRepository.UserRow user) throws java.sql.SQLException {
        int page = positive(ctx.request().getParam("page"), 1);
        int perPage = Math.min(100, Math.max(1, positive(ctx.request().getParam("per_page"), 10)));
        ReportRepository.Page result = reports.pageByReporter((int) user.uid(), page, perPage);

        List<Object> items = new ArrayList<>();
        for (ReportRepository.Row row : result.rows()) {
            TextureRepository.Row texture = textures.findByTid(row.tid());
            items.add(new PhpJson.Array()
                    .with("id", row.id())
                    .with("tid", row.tid())
                    .with("texture_name", texture == null ? "" : texture.name())
                    .with("reason", row.reason())
                    .with("status", row.status())
                    .with("report_at", row.reportAt()));
        }

        writeJson(ctx, 200, new PhpJson.Array()
                .with("data", items)
                .with("current_page", result.currentPage())
                .with("last_page", lastPage(result.total(), perPage))
                .with("per_page", perPage)
                .with("total", result.total()));
    }

    private static int positive(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int lastPage(long total, int perPage) {
        return total <= 0 ? 1 : (int) ((total + perPage - 1) / perPage);
    }

    private static Long communicationId(Map<String, Object> data) {
        Object raw = data.get("communication_id");
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(raw));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String preview(String content, int limit) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String plain = content.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        plain = plain.replaceAll("\\s+", " ").trim();
        if (plain.codePointCount(0, plain.length()) <= limit) {
            return plain;
        }
        int end = plain.offsetByCodePoints(0, limit);
        return plain.substring(0, end);
    }

    private static void notFound(RoutingContext ctx) {
        ctx.response().setStatusCode(404)
                .putHeader("Content-Type", "application/json")
                .end("{\"message\":\"Not Found\"}");
    }

    private static void writeJson(RoutingContext ctx, int status, Object body) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(PhpJson.encode(body));
    }

    private void bootstrap(RoutingContext ctx) {
        String requested = ctx.request().getParam("mode");
        String mode = "admin".equals(requested) ? "admin" : "site";
        PhpJson.Array data = payload(ctx, mode);
        PhpJson.Array envelope = new PhpJson.Array()
                .with("code", 0)
                .with("message", "ok")
                .with("data", data);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .putHeader("Cache-Control", "no-cache, private")
                .end(PhpJson.encode(envelope));
    }

    private void shell(RoutingContext ctx, String mode, Path index) {
        try {
            String html = Files.readString(index, StandardCharsets.UTF_8);
            String payload = PhpJson.encode(payload(ctx, mode));
            String csrf = csrfToken(ctx);
            String injection = "<meta name=\"csrf-token\" content=\"" + htmlEscape(csrf) + "\">\n"
                    + "<meta name=\"neoskin-mode\" content=\"" + mode + "\">\n"
                    + "<script id=\"neoskin-bootstrap\" type=\"application/json\">"
                    + escapeScriptJson(payload) + "</script>\n";
            ctx.response()
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache")
                    .end(html.replace("</head>", injection + "</head>"));
        } catch (IOException e) {
            ctx.response().setStatusCode(500).end("Internal Server Error");
        }
    }

    private PhpJson.Array payload(RoutingContext ctx, String mode) {
        String locale = Option.currentLocale();
        if (locale == null || locale.isEmpty()) {
            locale = Locales.APP_LOCALE;
        }
        String siteName = String.valueOf(
                option.getLocalized("site_name", "Blessing Skin"));
        UserRepository.UserRow user = currentUser(ctx);

        PhpJson.Array userJson = null;
        if (user != null) {
            userJson = new PhpJson.Array()
                    .with("uid", user.uid())
                    .with("email", user.email())
                    .with("nickname", user.nickname() == null ? "" : user.nickname())
                    .with("avatar", "/avatar/" + user.avatar())
                    .with("score", user.score())
                    .with("permission", user.permission())
                    .with("verified", user.verified())
                    .with("isAdmin", user.permission() >= 1)
                    .with("isBanned", user.permission() < 0)
                    .with("darkMode", user.darkMode());
        }

        PhpJson.Array options = new PhpJson.Array()
                .with("site_name", siteName)
                .with("site_description", option.getLocalized("site_description", ""))
                .with("user_can_register", true)
                .with("register_with_player_name",
                        option.getBoolean("register_with_player_name", true))
                .with("captcha", false)
                .with("recaptcha", !option.getString("recaptcha_sitekey", "").isEmpty())
                .with("recaptcha_site_key", option.getString("recaptcha_sitekey", ""))
                .with("max_upload_file_size", option.getInt("max_upload_file_size", 1024))
                .with("max_texture_width", option.getInt("max_texture_width", 8192))
                .with("score_per_storage", option.getInt("score_per_storage", 1))
                .with("private_score_per_storage",
                        option.getInt("private_score_per_storage", 10))
                .with("score_award_per_texture", option.getInt("score_award_per_texture", 0))
                .with("score_per_player", option.getInt("score_per_player", 100))
                .with("score_per_closet_item", option.getInt("score_per_closet_item", 0))
                .with("sign_score", option.getString("sign_score", "10,100"))
                .with("player_name_length_min", option.getInt("player_name_length_min", 3))
                .with("player_name_length_max", option.getInt("player_name_length_max", 16))
                .with("user_initial_score", option.getInt("user_initial_score", 1000))
                .with("allow_downloading_texture",
                        option.getBoolean("allow_downloading_texture", true))
                .with("require_verification",
                        option.getBoolean("require_verification", false))
                .with("reporter_score_modification",
                        option.getInt("reporter_score_modification", 0))
                .with("reporter_reward_score", option.getInt("reporter_reward_score", 0))
                .with("return_score", option.getBoolean("return_score", true))
                .with("sign_after_zero", option.getBoolean("sign_after_zero", false))
                .with("sign_gap_time", option.getInt("sign_gap_time", 24))
                .with("take_back_scores_after_deletion",
                        option.getBoolean("take_back_scores_after_deletion", true))
                .with("player_name_rule", option.getString("player_name_rule", "official"))
                .with("custom_player_name_regexp",
                        option.getString("custom_player_name_regexp", ""))
                .with("navbar_color", option.getString("navbar_color", "cyan"))
                .with("sidebar_color", option.getString("sidebar_color", "dark-maroon"))
                .with("copyright_prefer", option.getString("copyright_prefer", "0"))
                .with("copyright_text", option.getString("copyright_text", ""))
                .with("update_source", option.getString("update_source", ""))
                .with("plugin_market_source", option.getString("plugin_market_source", ""));

        PhpJson.Array features = new PhpJson.Array()
                .with("registration", true)
                .with("emailVerification", option.getBoolean("require_verification", false))
                .with("passwordReset", false)
                .with("oauth", true)
                .with("reports", true)
                .with("plugins", true);

        PhpJson.Array plugins = new PhpJson.Array()
                .with("menus", new PhpJson.Array())
                .with("badges", new PhpJson.Array())
                .with("styles", new PhpJson.Array())
                .with("scripts", new PhpJson.Array());

        List<Object> locales = new ArrayList<>(Locales.ids());

        return new PhpJson.Array()
                .with("contractVersion", CONTRACT_VERSION)
                .with("apiBase", "")
                .with("locale", locale)
                .with("localeFallback", Locales.FALLBACK_LOCALE)
                .with("siteName", siteName)
                .with("version", version)
                .with("basePath", "/app-next/" + mode + "/")
                .with("mode", mode)
                .with("csrfToken", csrfToken(ctx))
                .with("locales", locales)
                .with("user", userJson)
                .with("options", options)
                .with("features", features)
                .with("plugins", plugins);
    }

    private UserRepository.UserRow currentUser(RoutingContext ctx) {
        Object raw = ctx.get(StartSession.CTX_KEY);
        if (!(raw instanceof com.neoskin.service.session.SessionStore session)) {
            return null;
        }
        try {
            return new com.neoskin.web.auth.SessionGuard(session, users).user();
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private String csrfToken(RoutingContext ctx) {
        Object raw = ctx.get(StartSession.CTX_KEY);
        if (!(raw instanceof com.neoskin.service.session.SessionStore session)) {
            return "";
        }
        Object token = session.token();
        return token == null ? "" : String.valueOf(token);
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeScriptJson(String value) {
        return value.replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }
}
