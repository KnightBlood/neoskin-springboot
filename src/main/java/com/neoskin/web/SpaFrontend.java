package com.neoskin.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
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

    /** {@code FRONTEND_MODE=legacy|next}。 */
    public static boolean nextMode() {
        return "next".equalsIgnoreCase(System.getenv("FRONTEND_MODE"));
    }

    public SpaFrontend(Option option, UserRepository users, Path publicDir, String version) {
        this.option = option;
        this.users = users;
        this.publicDir = publicDir;
        this.version = version;
    }

    @Override
    public void handle(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        if (path.equals("/api/bootstrap")) {
            bootstrap(ctx);
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
