package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.neoskin.db.PlayerRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\UserController} 的移植。
 *
 * <p>包含 JSON 类 action（{@code user} / {@code scoreInfo} / {@code sign} /
 * {@code toggleDarkMode} / {@code handleProfile} / {@code setAvatar} /
 * {@code sendVerificationEmail}）与 {@code index} / {@code profile} 两个视图页。</p>
 */
public final class UserController {

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final PlayerRepository players;
    private final DataSource dataSource;
    private final com.neoskin.service.cipher.Cipher cipher;
    private final String salt;
    private final String timezone;
    private final com.neoskin.web.i18n.JavaScript javaScript;
    private final com.neoskin.service.BlessingFilter filter;
    private final boolean debug;
    private final com.neoskin.service.Communications service;
    private final com.neoskin.config.NeoSkinSettings settings;

    public UserController(Option option, Translator translator, UserRepository users,
            PlayerRepository players, DataSource dataSource, String timezone,
            com.neoskin.service.cipher.Cipher cipher, String salt,
            com.neoskin.web.i18n.JavaScript javaScript, com.neoskin.service.BlessingFilter filter,
            boolean debug, com.neoskin.service.Communications service,
            com.neoskin.config.NeoSkinSettings settings) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.players = players;
        this.dataSource = dataSource;
        this.timezone = timezone;
        this.cipher = cipher;
        this.salt = salt;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
        this.service = service;
        this.settings = settings;
    }

    // ============================================================ API user

    public Handler<RoutingContext> user() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("uid", user.uid());
                body.put("email", user.email());
                body.put("nickname", user.nickname());
                body.put("avatar", user.avatar());
                body.put("score", user.score());
                body.put("permission", user.permission());
                body.put("verified", user.verified());
                body.put("is_dark_mode", user.darkMode());
                body.put("locale", user.locale());
                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ scoreInfo

    public Handler<RoutingContext> scoreInfo() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("score", user.score());
                u.put("lastSignAt", users.lastSignAt(user.uid()));

                Map<String, Object> rate = new LinkedHashMap<>();
                rate.put("storage", toInt(option.get("score_per_storage")));
                rate.put("players", toInt(option.get("score_per_player")));

                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("players", players.countByUser(user.uid()));
                usage.put("storage", textureSize(user.uid()));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("user", u);
                body.put("rate", rate);
                body.put("usage", usage);
                body.put("signAfterZero", toBool(option.get("sign_after_zero")));
                body.put("signGapTime", toInt(option.get("sign_gap_time")));
                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ sign

    public Handler<RoutingContext> sign() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String lastSign = users.lastSignAt(user.uid());
                long remaining = remainingSeconds(lastSign);
                if (remaining > 0) {
                    json(ctx, 200, message(1, ""));
                    return;
                }
                String[] range = String.valueOf(option.get("sign_score", "0,0")).split(",");
                int min = toInt(range.length > 0 ? range[0] : "0");
                int max = toInt(range.length > 1 ? range[1] : "0");
                int acquired = ThreadLocalRandom.current().nextInt(min, Math.max(min, max) + 1);

                int newScore = user.score() + acquired;
                String now = com.neoskin.support.PhpDate.now(timezone);
                users.updateScoreAndSignAt(user.uid(), newScore, now);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("score", newScore);
                json(ctx, 200, message(0,
                        translator.trans("user.sign-success",
                                Map.of("score", String.valueOf(acquired)), translator.getLocale()),
                        data));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ toggleDarkMode

    public Handler<RoutingContext> toggleDarkMode() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                users.updateDarkMode(user.uid(), !user.darkMode());
                ctx.response().setStatusCode(204)
                        .putHeader("Cache-Control", "no-cache, private")
                        .end();
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ handleProfile

    public Handler<RoutingContext> handleProfile() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String action = String.valueOf(RequestInput.input(ctx, "action"));

                switch (action) {
                    case "nickname" -> {
                        if (!validate(ctx, Map.of("new_nickname", List.of("required")))) {
                            return;
                        }
                        String nickname = string(RequestInput.input(ctx, "new_nickname"));
                        users.updateNickname(user.uid(), nickname);
                        com.neoskin.event.EventRuntime.fire(
                                new com.neoskin.event.Events.UserProfileUpdated("nickname",
                                        user));
                        json(ctx, 200, message(0, translator.trans("user.profile.nickname.success",
                                Map.of("nickname", nickname), translator.getLocale())));
                    }
                    case "password" -> {
                        if (!validate(ctx, Map.of(
                                "current_password", List.of("required", "min:6", "max:32"),
                                "new_password", List.of("required", "min:8", "max:32")))) {
                            return;
                        }
                        String current = string(RequestInput.input(ctx, "current_password"));
                        if (!verifyPassword(current, user.password())) {
                            json(ctx, 200, message(1,
                                    translator.trans("user.profile.password.wrong-password",
                                            Map.of(), translator.getLocale())));
                            return;
                        }
                        String newPassword = string(RequestInput.input(ctx, "new_password"));
                        users.changePassword(user.uid(),
                                cipher.hash(newPassword, salt));
                        com.neoskin.event.EventRuntime.fire(
                                new com.neoskin.event.Events.UserProfileUpdated("password",
                                        user));
                        logout(ctx, user.uid());
                        json(ctx, 200, message(0,
                                translator.trans("user.profile.password.success",
                                        Map.of(), translator.getLocale())));
                    }
                    case "email" -> {
                        if (!validate(ctx, Map.of(
                                "email", List.of("required", "email"),
                                "password", List.of("required", "min:6", "max:32")))) {
                            return;
                        }
                        String email = string(RequestInput.input(ctx, "email"));
                        String password = string(RequestInput.input(ctx, "password"));
                        if (users.countByEmail(email) > 0) {
                            json(ctx, 200, message(1,
                                    translator.trans("user.profile.email.existed",
                                            Map.of(), translator.getLocale())));
                            return;
                        }
                        if (!verifyPassword(password, user.password())) {
                            json(ctx, 200, message(1,
                                    translator.trans("user.profile.email.wrong-password",
                                            Map.of(), translator.getLocale())));
                            return;
                        }
                        users.updateEmailAndUnverify(user.uid(), email);
                        com.neoskin.event.EventRuntime.fire(
                                new com.neoskin.event.Events.UserProfileUpdated("email", user));
                        logout(ctx, user.uid());
                        json(ctx, 200, message(0,
                                translator.trans("user.profile.email.success",
                                        Map.of(), translator.getLocale())));
                    }
                    case "delete" -> {
                        if (!validate(ctx, Map.of(
                                "password", List.of("required", "min:6", "max:32")))) {
                            return;
                        }
                        if (user.isAdmin()) {
                            json(ctx, 200, message(1,
                                    translator.trans("user.profile.delete.admin",
                                            Map.of(), translator.getLocale())));
                            return;
                        }
                        String password = string(RequestInput.input(ctx, "password"));
                        if (!verifyPassword(password, user.password())) {
                            json(ctx, 200, message(1,
                                    translator.trans("user.profile.delete.wrong-password",
                                            Map.of(), translator.getLocale())));
                            return;
                        }
                        logout(ctx, user.uid());
                        users.delete(user.uid());
                        SessionStore session = ctx.get(StartSession.CTX_KEY);
                        if (session != null) {
                            session.forget("_token");
                        }
                        json(ctx, 200, message(0,
                                translator.trans("user.profile.delete.success",
                                        Map.of(), translator.getLocale())));
                    }
                    default -> json(ctx, 200, message(1,
                            translator.trans("general.illegal-parameters",
                                    Map.of(), translator.getLocale())));
                }
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ setAvatar

    public Handler<RoutingContext> setAvatar() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                if (!validate(ctx, Map.of("tid", List.of("required", "integer")))) {
                    return;
                }
                int tid = toInt(RequestInput.input(ctx, "tid"));
                if (tid == 0) {
                    users.updateAvatar(user.uid(), 0);
                    json(ctx, 200, message(0,
                            translator.trans("user.profile.avatar.success",
                                    Map.of(), translator.getLocale())));
                    return;
                }
                Map<String, Object> texture = findTexture(tid);
                if (texture == null) {
                    json(ctx, 200, message(1,
                            translator.trans("skinlib.non-existent",
                                    Map.of(), translator.getLocale())));
                    return;
                }
                if ("cape".equals(texture.get("type"))) {
                    json(ctx, 200, message(1,
                            translator.trans("user.profile.avatar.wrong-type",
                                    Map.of(), translator.getLocale())));
                    return;
                }
                boolean isPublic = toBool(texture.get("public"));
                int uploader = toInt(texture.get("uploader"));
                if (!isPublic && user.uid() != uploader && !user.isAdmin()) {
                    json(ctx, 200, message(1,
                            translator.trans("skinlib.show.private",
                                    Map.of(), translator.getLocale())));
                    return;
                }
                users.updateAvatar(user.uid(), tid);
                json(ctx, 200, message(0,
                        translator.trans("user.profile.avatar.success",
                                Map.of(), translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ views（待模板）

    public Handler<RoutingContext> index() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                String locale = translator.getLocale();
                String[] range = String.valueOf(option.get("sign_score", "0,0")).split(",");
                String min = range.length > 0 ? range[0] : "0";
                String max = range.length > 1 ? range[1] : "0";
                String scoreIntro = translator.trans("user.score-intro.introduction", Map.of(
                        "initial_score", String.valueOf(option.get("user_initial_score")),
                        "score-from", min,
                        "score-to", max,
                        "return-score", toBool(option.get("return_score"))
                                ? translator.trans("user.score-intro.will-return-score", Map.of(), locale)
                                : translator.trans("user.score-intro.no-return-score", Map.of(), locale)),
                        locale);

                Map<String, Object> rates = new LinkedHashMap<>();
                rates.put("storage", option.get("score_per_storage"));
                rates.put("player", option.get("score_per_player"));
                rates.put("closet", option.get("score_per_closet_item"));

                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("unverified", toBool(option.get("require_verification"))
                        && user != null && !user.verified());

                Map<String, Object> vars = pageVars(ctx, user,
                        translator.trans("general.dashboard", Map.of(), locale), locale);
                vars.put("score_intro", scoreIntro);
                vars.put("score_intro_html", scoreIntro.replace("\n", "<br />\n"));
                vars.put("rates", rates);
                vars.put("announcement", service == null ? "" : service.announcements(locale));
                vars.put("gridRows", gridRows());
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "user/index", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> profile() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String locale = translator.getLocale();
                Map<String, Object> vars = pageVars(ctx, user,
                        translator.trans("general.profile", Map.of(), locale), locale);
                vars.put("user_nickname", user.nickname());
                vars.put("user_email", user.email());
                vars.put("user_is_admin", user.isAdmin());
                vars.put("gridRows", profileGridRows());
                render(ctx, "user/profile", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> sendVerificationEmail() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }

                if (!option.getBoolean("require_verification", false)) {
                    json(ctx, 200, message(1,
                            translator.trans("user.verification.disabled", Map.of(), locale)));
                    return;
                }

                long now = System.currentTimeMillis() / 1000;
                long lastMail = session == null ? 0 : toLong(session.get("last_mail_time"));
                if (60 + lastMail - now > 0) {
                    json(ctx, 200, message(1,
                            translator.trans("user.verification.frequent-mail", Map.of(),
                                    locale)));
                    return;
                }

                if (user.verified()) {
                    json(ctx, 200, message(1,
                            translator.trans("user.verification.verified", Map.of(), locale)));
                    return;
                }

                String host = hostOf(ctx);
                String url = com.neoskin.service.VerificationMail.url(
                        com.neoskin.web.RouteUrlGenerator.forHost(host),
                        settings == null ? "" : settings.getKey(), host, user.uid());
                try {
                    new com.neoskin.service.Mailer(settings).send(user.email(),
                            com.neoskin.service.VerificationMail.subject(option, translator,
                                    locale),
                            com.neoskin.service.VerificationMail.body(option, translator, locale,
                                    url));
                } catch (Exception e) {
                    json(ctx, 200, message(2, translator.trans("user.verification.failed",
                            Map.of("msg", String.valueOf(e.getMessage())), locale)));
                    return;
                }

                if (session != null) {
                    session.put("last_mail_time", now);
                }
                json(ctx, 200, message(0,
                        translator.trans("user.verification.success", Map.of(), locale)));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ helpers

    private Map<String, Object> pageVars(RoutingContext ctx, UserRepository.UserRow user,
            String title, String locale) throws Exception {
        String host = hostOf(ctx);
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        com.neoskin.web.view.ViewContext vc = new com.neoskin.web.view.ViewContext(
                translator, option, new com.neoskin.web.PhpUrlGenerator(urlRoot, "http"),
                com.neoskin.web.RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = com.neoskin.web.view.PageView.variables(option, translator,
                javaScript, filter, user, locale, urlRoot, ctx.request().path(), debug, "user", vc);
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

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    /** {@code user.index} 的 widget 网格。 */
    private static List<List<Map<String, Object>>> gridRows() {
        return List.of(
                List.of(
                        col("md-7", "user/widgets/email-verification",
                                "user/widgets/dashboard/usage"),
                        col("md-5", "user/widgets/dashboard/announcement")));
    }

    /** {@code user.profile} 的 widget 网格。 */
    private static List<List<Map<String, Object>>> profileGridRows() {
        return List.of(
                List.of(
                        col("md-6", "user/widgets/profile/avatar",
                                "user/widgets/profile/password"),
                        col("md-6", "user/widgets/profile/nickname",
                                "user/widgets/profile/email",
                                "user/widgets/profile/delete-account")));
    }

    private static Map<String, Object> col(String css, String... widgets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", css);
        m.put("widgets", List.of(widgets));
        return m;
    }


    private UserRepository.UserRow currentUser(RoutingContext ctx) throws SQLException {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            return null;
        }
        return new com.neoskin.web.auth.SessionGuard(session, users).user();
    }

    private void logout(RoutingContext ctx, int uid) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            new com.neoskin.web.auth.SessionGuard(session, users).logout();
        }
    }

    private boolean validate(RoutingContext ctx, Map<String, List<String>> rules) {
        Map<String, Object> input = inputMap(ctx);
        Validator v = new Validator(input, translator, translator.getLocale(), option, null);
        for (Map.Entry<String, List<String>> e : rules.entrySet()) {
            for (String rule : e.getValue()) {
                v.addRule(e.getKey(), rule);
            }
        }
        if (v.passes()) {
            return true;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(v.errors()));
        body.put("errors", v.errors());
        json(ctx, 422, body);
        return false;
    }

    @SuppressWarnings("unchecked")
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

    private Map<String, Object> findTexture(int tid) throws SQLException {
        // SQL Server 的 PUBLIC 是保留字；列名必须按方言引用。
        String publicColumn = com.neoskin.db.SqlDialect
                .of(settings.getDatabase().getConnection()).quote("public");
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT tid, uid AS uploader, type, " + publicColumn
                             + " FROM textures WHERE tid = ?")) {
            stmt.setInt(1, tid);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tid", rs.getInt("tid"));
                m.put("uploader", rs.getInt("uploader"));
                m.put("type", rs.getString("type"));
                m.put("public", rs.getBoolean("public"));
                return m;
            }
        }
    }

    private long textureSize(int uid) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(size), 0) FROM textures WHERE uploader = ?")) {
            stmt.setInt(1, uid);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private boolean verifyPassword(String plain, String hash) {
        return cipher == null || cipher.verify(plain, hash, salt);
    }

    private long remainingSeconds(String lastSignAt) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
        LocalDateTime last = parse(lastSignAt);
        if (last == null) {
            return -1;
        }
        ZonedDateTime lastZoned = last.atZone(ZoneId.of(timezone));
        ZonedDateTime target;
        if (toBool(option.get("sign_after_zero"))) {
            LocalDateTime todayStart = LocalDate.now(ZoneId.of(timezone)).atStartOfDay();
            target = last.isAfter(todayStart)
                    ? todayStart.plusDays(1).atZone(ZoneId.of(timezone))
                    : lastZoned;
        } else {
            target = lastZoned.plusHours(toInt(option.get("sign_gap_time", 24)));
        }
        return Duration.between(now, target).getSeconds();
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String s = value.length() > 19 ? value.substring(0, 19) : value;
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> message(int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", msg);
        return body;
    }

    private static Map<String, Object> message(int code, String msg, Map<String, Object> data) {
        Map<String, Object> body = message(code, msg);
        body.put("data", data);
        return body;
    }

    private static String string(Object v) {
        return v == null ? "" : PhpString.cast(v);
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

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v);
        return !(s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false"));
    }

    // ============================================================ resolver

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, PlayerRepository players, DataSource dataSource,
            String timezone, com.neoskin.service.cipher.Cipher cipher, String salt,
            com.neoskin.web.i18n.JavaScript javaScript, com.neoskin.service.BlessingFilter filter,
            boolean debug, com.neoskin.service.Communications service,
            com.neoskin.config.NeoSkinSettings settings) {
        UserController c = new UserController(option, translator, users, players, dataSource,
                timezone, cipher, salt, javaScript, filter, debug, service, settings);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\UserController@user" -> c.user();
            case "App\\Http\\Controllers\\UserController@index" -> c.index();
            case "App\\Http\\Controllers\\UserController@toggleDarkMode" -> c.toggleDarkMode();
            case "App\\Http\\Controllers\\UserController@sendVerificationEmail" -> c.sendVerificationEmail();
            case "App\\Http\\Controllers\\UserController@profile" -> c.profile();
            case "App\\Http\\Controllers\\UserController@handleProfile" -> c.handleProfile();
            case "App\\Http\\Controllers\\UserController@setAvatar" -> c.setAvatar();
            case "App\\Http\\Controllers\\UserController@scoreInfo" -> c.scoreInfo();
            case "App\\Http\\Controllers\\UserController@sign" -> c.sign();
            default -> null;
        };
    }
}