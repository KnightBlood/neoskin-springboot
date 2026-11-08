package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Mailer;
import com.neoskin.service.Option;
import com.neoskin.service.VerificationMail;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.SignedUrl;
import com.neoskin.web.auth.SessionGuard;
import com.neoskin.web.error.ErrorHandler;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.AuthView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;
import com.neoskin.web.error.WebException;
import com.neoskin.web.validation.CaptchaRule;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.PlayerNameRule;
import com.neoskin.web.validation.RfcEmailValidator;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code App\Http\Controllers\AuthController} 的移植。
 *
 * <p>已移植：{@code login} / {@code handleLogin} / {@code logout} /
 * {@code register} / {@code handleRegister} / {@code forgot} / {@code handleForgot} /
 * {@code reset} / {@code handleReset} / {@code captcha} / {@code fillEmail} /
 * {@code bindView} / {@code verify} / {@code handleVerify}。</p>
 *
 * <h2>handleLogin 的源流程（逐条对齐）</h2>
 * <ol>
 *   <li>{@code validate: identification required, password required|min:6|max:32}；</li>
 *   <li>{@code can_login} 钩子可拒绝（返回 {@code Rejection}）；</li>
 *   <li>识别类型：{@code filter_var($id, FILTER_VALIDATE_EMAIL)} → email，否则按角色名；</li>
 *   <li>按 email 查 {@code users}，或按 {@code players.name} 查玩家再取 {@code uid}；</li>
 *   <li>失败次数 &gt; 3 时强制验证码（{@code captcha} 规则，先于「用户不存在」判断）；</li>
 *   <li>用户不存在 → {@code json(trans('auth.validation.user'), 2)}；</li>
 *   <li>密码校验失败 → 计数 +1，{@code json(trans('auth.validation.password'), 1,
 *       {login_fails: n})}；</li>
 *   <li>成功 → 清 {@code login_fails}（会话 + 缓存）、{@code Auth::login()}、
 *       {@code json(trans('auth.login.success'), 0,
 *       {redirectTo: session.pull('last_requested_path') ?? url('/user')})}。</li>
 * </ol>
 */
public final class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 源里 {@code last_requested_path} 的会话键（匿名访问受限页时由 Authenticate 写入）。 */
    private static final String LAST_REQUESTED_PATH = "last_requested_path";

    /** 密码最短/最长（{@code min:6|max:32}）。 */
    private static final int PASSWORD_MIN = 6;

    private static final int PASSWORD_MAX = 32;

    private final Option option;

    private final Translator translator;

    private final JavaScript javaScript;

    private final BlessingFilter filter;

    private final LoginFailCounter failures;

    private final UserRepository users;

    private final PlayerRepository players;

    private final Cipher cipher;

    /** {@code config('secure.salt')}。 */
    private final String salt;

    private final boolean debug;

    /** {@link #resolver} 传入的 {@code DataSource}；{@code unique:users} 校验用。 */
    private final DataSource dataSource;

    /** {@code config('app.key')} 等运行时配置（签名 URL、邮件 mailer）。 */
    private final NeoSkinSettings settings;

    public AuthController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, LoginFailCounter failures,
            UserRepository users, PlayerRepository players, Cipher cipher, String salt,
            boolean debug) {
        this(option, translator, javaScript, filter, failures, users, players,
                cipher, salt, debug, null, null);
    }

    public AuthController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, LoginFailCounter failures,
            UserRepository users, PlayerRepository players, Cipher cipher, String salt,
            boolean debug, DataSource dataSource, NeoSkinSettings settings) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.filter = filter;
        this.failures = failures;
        this.users = users;
        this.players = players;
        this.cipher = cipher;
        this.salt = salt == null ? "" : salt;
        this.debug = debug;
        this.dataSource = dataSource;
        this.settings = settings;
    }

    // ================================================================ 视图

    /** {@code login()}：渲染登录页。 */
    public Handler<RoutingContext> login() {
        return ctx -> {
            String locale = translator.getLocale();
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);

            String clientIp = clientIp(ctx);
            Map<String, Object> vars;
            try {
                vars = AuthView.loginVariables(option, translator, javaScript,
                        filter, failures, clientIp, locale, urlRoot, requestPath, debug);
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }

            ViewContext viewContext = new ViewContext(
                    translator, option,
                    new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host),
                    locale, session == null ? "" : String.valueOf(session.token()),
                    null, session);
            vars.put("view", viewContext);

            String html = ViewEngine.render("auth/login", java.util.Locale.ROOT, vars);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    // ================================================================ 登录

    /** {@code handleLogin()}。 */
    public Handler<RoutingContext> handleLogin() {
        return ctx -> {
            String locale = translator.getLocale();
            SessionStore session = ctx.get(StartSession.CTX_KEY);

            String identification = RequestInput.inputString(ctx, "identification");
            String password = RequestInput.inputString(ctx, "password");

            Map<String, List<String>> errors = validateCredentials(identification, password);
            if (!errors.isEmpty()) {
                validationFailed(ctx, errors);
                return;
            }

            // can_login 钩子：源里返回 Rejection 时直接拒绝
            Object rejected = filter == null ? null
                    : filter.apply("can_login", null, List.of(identification, password));
            if (rejected instanceof String reason && !reason.isEmpty()) {
                json(ctx, 200, message(1, reason));
                return;
            }

            String clientIp = clientIp(ctx);
            String failKey = LoginFailCounter.keyForIp(clientIp);
            int loginFails = failures.get(failKey);

            UserRepository.UserRow user;
            try {
                user = findUser(identification);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }

            // 源：event(new Events\UserTryToLogin($identification, $authType))
            String authType = looksLikeEmail(identification) ? "email" : "username";
            com.neoskin.event.EventRuntime.fire(
                    new com.neoskin.event.Events.UserTryToLogin(identification, authType));

            // 失败次数 > 3 时强制验证码（源先校验 captcha，再判断用户是否存在）
            if (loginFails > 3) {
                Map<String, Object> captchaInput = new LinkedHashMap<>();
                captchaInput.put("captcha", RequestInput.inputString(ctx, "captcha"));
                com.neoskin.web.validation.Validator captchaValidator =
                        new com.neoskin.web.validation.Validator(
                                captchaInput, translator, locale, null, null);
                captchaValidator.addRule("captcha", "required");
                captchaValidator.addRule("captcha",
                        new CaptchaRule(option, session, translator, locale));
                if (!captchaValidator.passes()) {
                    validationFailed(ctx, captchaValidator.errors());
                    return;
                }
            }

            if (user == null) {
                json(ctx, 200, message(2, trans("auth.validation.user", locale)));
                return;
            }

            if (!verifyPassword(user, password)) {
                int next = failures.increment(failKey);
                if (session != null) {
                    session.put(SessionGuard.LOGIN_FAILS_KEY, next);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("login_fails", next);
                json(ctx, 200, message(1, trans("auth.validation.password", locale), data));
                return;
            }

            // 成功
            if (session != null) {
                session.forget(SessionGuard.LOGIN_FAILS_KEY);
            }
            failures.forget(failKey);

            if (session == null) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            new SessionGuard(session, users).login(user.uid());
            com.neoskin.event.EventRuntime.fire(new com.neoskin.event.Events.UserLoggedIn(user));

            String redirectTo = session.get(LAST_REQUESTED_PATH) == null
                    ? urlFor(ctx, "user")
                    : String.valueOf(pull(session, LAST_REQUESTED_PATH));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("redirectTo", redirectTo);
            json(ctx, 200, message(0, trans("auth.login.success", locale), data));
        };
    }

    /** {@code logout()}。 */
    public Handler<RoutingContext> logout() {
        return ctx -> {
            String locale = translator.getLocale();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            if (session != null) {
                new SessionGuard(session, users).logout();
            }
            json(ctx, 200, message(0, trans("auth.logout.success", locale)));
        };
    }

    // ================================================================ 内部

    /** 按 email 或角色名定位用户。 */
    private UserRepository.UserRow findUser(String identification) throws SQLException {
        if (looksLikeEmail(identification)) {
            return users.findByEmail(identification);
        }
        PlayerRepository.PlayerRow player = players.findByName(identification);
        return player == null ? null : users.findById(player.uid());
    }

    /** {@code filter_var($id, FILTER_VALIDATE_EMAIL)} 的近似实现。 */
    static boolean looksLikeEmail(String value) {
        if (value == null) {
            return false;
        }
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@')) {
            return false;
        }
        int dot = value.indexOf('.', at);
        return dot > at + 1 && dot < value.length() - 1;
    }

    private boolean verifyPassword(UserRepository.UserRow user, String raw) {
        if (cipher == null) {
            return false;
        }
        boolean passed = cipher.verify(raw, user.password(), salt);
        if (filter != null) {
            Object filtered = filter.apply("verify_password", passed, List.of(raw, user));
            if (filtered instanceof Boolean b) {
                return b;
            }
        }
        return passed;
    }

    /** {@code required|min:6|max:32}。 */
    private static Map<String, List<String>> validateCredentials(String identification,
            String password) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (identification == null || identification.isEmpty()) {
            errors.put("identification", List.of("The identification field is required."));
        }
        if (password == null || password.isEmpty()) {
            errors.put("password", List.of("The password field is required."));
        } else if (password.length() < PASSWORD_MIN) {
            errors.put("password", List.of(
                    "The password must be at least " + PASSWORD_MIN + " characters."));
        } else if (password.length() > PASSWORD_MAX) {
            errors.put("password", List.of(
                    "The password may not be greater than " + PASSWORD_MAX + " characters."));
        }
        return errors;
    }

    private String trans(String key, String locale) {
        return translator.trans(key, Map.of(), locale);
    }

    /** {@code url('user')} 的绝对形态（与源 {@code url('/user')} 一致）。 */
    private String urlFor(RoutingContext ctx, String path) {
        return "http://" + hostOf(ctx) + "/" + path;
    }

    private static Object pull(SessionStore session, String key) {
        Object value = session.get(key);
        session.forget(key);
        return value;
    }

    /**
     * 客户端 IP：源用 {@code Vectorface\Whip}（考虑可信代理头）再过
     * {@code client_ip} 钩子。本工程先取 {@code X-Forwarded-For} 首段，
     * 回退 TCP 对端地址 —— 尚未做可信代理白名单，见 PORT-NOTES。
     */
    private String clientIp(RoutingContext ctx) {
        String forwarded = ctx.request().getHeader("X-Forwarded-For");
        String ip;
        if (forwarded != null && !forwarded.isBlank()) {
            ip = forwarded.split(",")[0].trim();
        } else {
            io.vertx.core.net.SocketAddress remote = ctx.request().remoteAddress();
            ip = remote == null ? "" : remote.hostAddress();
        }
        if (filter == null) {
            return ip;
        }
        Object filtered = filter.apply("client_ip", ip);
        return filtered == null ? ip : String.valueOf(filtered);
    }

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    /** {@code ValidationException}：前端按 422 处理。 */
    private static void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "The given data was invalid.");
        body.put("errors", errors);
        json(ctx, 422, body);
    }

    /** {@code response()->json($data)}。 */
    private static void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code json($message, $code)}。 */
    private static Map<String, Object> message(int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", msg);
        return body;
    }

    /** {@code json($message, $code, $data)}。 */
    private static Map<String, Object> message(int code, String msg, Map<String, Object> data) {
        Map<String, Object> body = message(code, msg);
        body.put("data", data);
        return body;
    }

    // ================================================================ 注册

    /** {@code register()}：渲染注册页。 */
    public Handler<RoutingContext> register() {
        return ctx -> {
            String locale = translator.getLocale();
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            String clientIp = clientIp(ctx);
            Map<String, Object> vars;
            try {
                vars = AuthView.registerVariables(option, translator, javaScript,
                        filter, failures, clientIp, locale, urlRoot, requestPath, debug);
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("errors", errorsView(ctx, session));
            ViewContext viewContext = new ViewContext(
                    translator, option, new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), null, session);
            vars.put("view", viewContext);
            String html = ViewEngine.render("auth/register", java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    /** {@code handleRegister()}。 */
    public Handler<RoutingContext> handleRegister() {
        return ctx -> {
            String locale = translator.getLocale();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            String clientIp = clientIp(ctx);

            // can_register 钩子
            Object can = filter == null ? null : filter.apply("can_register", null);
            if (can instanceof String reason && !reason.isEmpty()) {
                json(ctx, 200, message(1, reason));
                return;
            }

            // 验证
            Map<String, Object> input = new LinkedHashMap<>();
            String email = RequestInput.inputString(ctx, "email");
            String password = RequestInput.inputString(ctx, "password");
            String captcha = RequestInput.inputString(ctx, "captcha");
            String playerName = RequestInput.inputString(ctx, "player_name");
            String nickname = RequestInput.inputString(ctx, "nickname");
            input.put("email", email);
            input.put("password", password);
            input.put("captcha", captcha);
            boolean withPlayer = option.getBoolean("register_with_player_name", false);
            if (withPlayer) input.put("player_name", playerName);
            else input.put("nickname", nickname);

            Validator v = new Validator(input, translator, locale, option,
                    new JdbcPresenceVerifier(dataSource()));
            v.addRule("email", "required");
            v.addRule("email", "email");
            v.addRule("email", "unique:users");
            v.addRule("password", "required");
            v.addRule("password", "min:8");
            v.addRule("password", "max:32");
            v.addRule("captcha", "required");
            v.addRule("captcha", new CaptchaRule(option, session, translator, locale));
            if (withPlayer) {
                v.addRule("player_name", "required");
                v.addRule("player_name", new PlayerNameRule(option, translator, locale));
                int min = toInt(option.get("player_name_length_min", 3));
                int max = toInt(option.get("player_name_length_max", 16));
                v.addRule("player_name", "min:" + min);
                v.addRule("player_name", "max:" + max);
            } else {
                v.addRule("nickname", "required");
                v.addRule("nickname", "max:255");
            }

            if (!v.passes()) {
                validationFailed(ctx, v.errors(), locale);
                return;
            }

            // player name repeated check
            int nameCount = 0;
            try {
                nameCount = withPlayer ? players.countByName(playerName) : 0;
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            if (nameCount > 0) {
                json(ctx, 200, message(1, translator.trans("user.player.add.repeated", Map.of(), locale)));
                return;
            }

            // regs_per_ip
            int regsPerIp = toInt(option.get("regs_per_ip", 3));
            int registered;
            try {
                registered = users.countByIp(clientIp);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            if (registered >= regsPerIp) {
                json(ctx, 200, message(1, translator.trans("auth.register.max",
                        Map.of("regs", regsPerIp), locale)));
                return;
            }

            // create user
            String hashed = cipher == null ? password
                    : cipher.hash(password, salt);
            hashed = applyUserPasswordFilter(hashed);
            int initialScore = toInt(option.get("user_initial_score", 1000));
            String nicknameVal = withPlayer ? playerName : nickname;
            int uid;
            try {
                uid = users.insert(email, nicknameVal, initialScore, hashed, clientIp,
                        settings == null ? "Asia/Shanghai" : settings.getTimezone());
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }

            // create player
            if (withPlayer) {
                try {
                    int pid = players.insert(uid, playerName);
                    PlayerRepository.PlayerRow createdPlayer = players.findByPid(pid);
                    if (createdPlayer != null) {
                        com.neoskin.event.EventRuntime.fire(
                                new com.neoskin.event.Events.PlayerWasAdded(createdPlayer));
                    }
                } catch (SQLException ignored) {
                }
            }
            // login
            if (session != null) {
                new SessionGuard(session, users).login(uid);
            }

            // auth.registration.completed → SendEmailVerification（发信失败只记日志）
            sendVerificationEmailIfRequired(ctx, uid, email, locale);
            // 源：event(new Events\UserRegistered($user))
            try {
                UserRepository.UserRow created = users.findById(uid);
                if (created != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.UserRegistered(created));
                }
            } catch (java.sql.SQLException ignored) {
                // 事件观察者不影响注册结果
            }

            json(ctx, 200, message(0, translator.trans("auth.register.success", Map.of(), locale)));
        };
    }

    // ================================================================ 忘记密码

    /** {@code forgot()}。 */
    public Handler<RoutingContext> forgot() {
        return ctx -> {
            String locale = translator.getLocale();
            // config('mail.default')：config/mail.php 里是 env('MAIL_MAILER', 'smtp')。
            // 源 forgot() 只在它为空时抛 PrettyPageException（auth.forgot.disabled, 8）。
            String mailDefault = settings == null || settings.getMail() == null
                    ? "smtp" : settings.getMail().getMailer();
            if (mailDefault == null || mailDefault.isEmpty()) {
                ctx.fail(WebException.pretty(
                        translator.trans("auth.forgot.disabled", Map.of(), locale), 8));
                return;
            }
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            Map<String, Object> vars;
            try {
                vars = AuthView.forgotVariables(option, translator, javaScript,
                        locale, urlRoot, requestPath, debug);
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("errors", errorsView(ctx, session));
            ViewContext viewContext = new ViewContext(
                    translator, option, new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), null, session);
            vars.put("view", viewContext);
            String html = ViewEngine.render("auth/forgot", java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    /** {@code handleForgot()}。 */
    public Handler<RoutingContext> handleForgot() {
        return ctx -> {
            String locale = translator.getLocale();
            SessionStore session = ctx.get(StartSession.CTX_KEY);

            // if (!config('mail.default')) return json(trans('auth.forgot.disabled'), 1);
            String mailDefault = settings == null || settings.getMail() == null
                    ? "smtp" : settings.getMail().getMailer();
            if (mailDefault == null || mailDefault.isEmpty()) {
                json(ctx, 200, message(1, translator.trans("auth.forgot.disabled", Map.of(), locale)));
                return;
            }

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("email", RequestInput.inputString(ctx, "email"));
            input.put("captcha", RequestInput.inputString(ctx, "captcha"));

            Validator v = new Validator(input, translator, locale, option,
                    new JdbcPresenceVerifier(dataSource()));
            v.addRule("email", "required");
            v.addRule("email", "email");
            v.addRule("captcha", "required");
            v.addRule("captcha", new CaptchaRule(option, session, translator, locale));

            if (!v.passes()) {
                validationFailed(ctx, v.errors(), locale);
                return;
            }

            String email = RequestInput.inputString(ctx, "email");

            // rate limit
            String clientIp = clientIp(ctx);
            String lastMailKey = sha1Hex("last_mail_" + clientIp);
            // 用 LoginFailCounter 风格的缓存？用 ConcurrentHashMap 临时
            long now = System.currentTimeMillis() / 1000L;
            // 简化：用 session 存储 last_mail_<ip> 时间戳
            Object last = session == null ? null : session.get(lastMailKey);
            long lastTime = 0;
            if (last instanceof Number n) lastTime = n.longValue();
            long remain = 180 + lastTime - now;
            if (remain > 0) {
                json(ctx, 200, message(2, translator.trans("auth.forgot.frequent-mail", Map.of(), locale)));
                return;
            }

            UserRepository.UserRow user;
            try {
                user = users.findByEmail(email);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            if (user == null) {
                json(ctx, 200, message(1, translator.trans("auth.forgot.unregistered", Map.of(), locale)));
                return;
            }

            // signed route
            long expiry = now + 3600;
            String url = SignedUrl.temporarySignedRoute("auth.reset",
                    expiry, Map.of("uid", user.uid()), false,
                    RouteUrlGenerator.forHost(hostOf(ctx)), settingsKey());
            String fullUrl = "http://" + hostOf(ctx) + url;

            // send mail
            try {
                sendMail(email, translator.trans("auth.forgot.mail.title",
                        Map.of("sitename", option.getLocalized("site_name", "NeoSkin")), locale),
                        buildMailBody(fullUrl, option, translator, locale));
            } catch (Exception e) {
                json(ctx, 200, message(2, translator.trans("auth.forgot.failed",
                        Map.of("msg", e.getMessage()), locale)));
                return;
            }

            if (session != null) session.put(lastMailKey, now);
            json(ctx, 200, message(0, translator.trans("auth.forgot.success", Map.of(), locale)));
        };
    }

    // ================================================================ 重置密码

    /** {@code reset($uid)}。 */
    public Handler<RoutingContext> reset() {
        return ctx -> {
            String uid = ctx.pathParam("uid");
            String locale = translator.getLocale();
            // hasValidSignature(false)
            String path = ctx.request().path();
            Map<String, String> qp = queryParams(ctx);
            if (!SignedUrl.hasValidSignature(path, qp, System.currentTimeMillis() / 1000L,
                    settingsKey(), null)) {
                ctx.fail(WebException.http(403, translator.trans("auth.reset.invalid", Map.of(), locale)));
                return;
            }
            // render
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            int uidInt = 0;
            try { uidInt = Integer.parseInt(uid); } catch (NumberFormatException ignored) {}
            UserRepository.UserRow user = null;
            try { user = users.findById(uidInt); } catch (SQLException ignored) {}
            Map<String, Object> vars;
            try {
                vars = AuthView.simpleVariables(option, translator, javaScript,
                        locale, urlRoot, requestPath, debug, "auth.reset.title");
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("user", user);
            vars.put("uid", uid);
            vars.put("errors", errorsView(ctx, session));
            ViewContext viewContext = new ViewContext(
                    translator, option, new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), null, session);
            vars.put("view", viewContext);
            String html = ViewEngine.render("auth/reset", java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    /** {@code handleReset($uid)}。 */
    public Handler<RoutingContext> handleReset() {
        return ctx -> {
            String uid = ctx.pathParam("uid");
            String path = ctx.request().path();
            Map<String, String> qp = queryParams(ctx);
            String locale = translator.getLocale();
            if (!SignedUrl.hasValidSignature(path, qp, System.currentTimeMillis() / 1000L,
                    settingsKey(), null)) {
                ctx.fail(WebException.http(403, translator.trans("auth.reset.invalid", Map.of(), locale)));
                return;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("password", RequestInput.inputString(ctx, "password"));
            Validator v = new Validator(input, translator, locale, option, null);
            v.addRule("password", "required");
            v.addRule("password", "min:8");
            v.addRule("password", "max:32");
            if (!v.passes()) {
                validationFailed(ctx, v.errors(), locale);
                return;
            }
            String password = RequestInput.inputString(ctx, "password");
            int uidInt = 0;
            try { uidInt = Integer.parseInt(uid); } catch (NumberFormatException ignored) {}
            String hashed = cipher == null ? password : cipher.hash(password, salt);
            hashed = applyUserPasswordFilter(hashed);
            try {
                users.changePassword(uidInt, hashed);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            json(ctx, 200, message(0, translator.trans("auth.reset.success", Map.of(), locale)));
        };
    }

    // ================================================================ 验证码

    /** {@code captcha()}。 */
    public Handler<RoutingContext> captcha() {
        return ctx -> {
            // 生成 5 位随机短语
            String charset = "abcdefghijklmnpqrstuvwxyz123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            StringBuilder phrase = new StringBuilder();
            java.util.Random rnd = new java.util.Random();
            for (int i = 0; i < 5; i++) {
                phrase.append(charset.charAt(rnd.nextInt(charset.length())));
            }
            String stored = phrase.toString();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            if (session != null) session.put("captcha", stored);

            // 生成 JPEG 100x34
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(100, 34,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(new java.awt.Color(220, 220, 220));
                g.fillRect(0, 0, 100, 34);
                g.setColor(new java.awt.Color(50, 50, 50));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 20));
                g.drawString(stored, 15, 24);
                g.dispose();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "jpg", baos);
                ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "image/jpeg")
                        .putHeader("Cache-Control", "no-store")
                        .end(Buffer.buffer(baos.toByteArray()));
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
            }
        };
    }

    // ================================================================ 绑定邮箱

    /** {@code fillEmail()} (POST /auth/bind)。 */
    public Handler<RoutingContext> fillEmail() {
        return ctx -> {
            String locale = translator.getLocale();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("email", RequestInput.inputString(ctx, "email"));
            Validator v = new Validator(input, translator, locale, option,
                    new JdbcPresenceVerifier(dataSource()));
            v.addRule("email", "required");
            v.addRule("email", "email");
            v.addRule("email", "unique:users");
            if (!v.passes()) {
                validationFailed(ctx, v.errors(), locale);
                return;
            }
            String email = RequestInput.inputString(ctx, "email");
            int uid = session == null ? 0 : (sessionGuardId(session));
            try {
                users.updateEmail(uid, email);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            ctx.response().setStatusCode(302)
                    .putHeader("Location", "http://" + hostOf(ctx) + "/user")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end();
        };
    }

    /** auth.bind.view (GET)：渲染 auth/bind。 */
    public Handler<RoutingContext> bindView() {
        return ctx -> {
            String locale = translator.getLocale();
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            // errors from session
            Map<String, List<String>> errors = errorsFromSession(session);
            Map<String, Object> vars;
            try {
                vars = AuthView.simpleVariables(option, translator, javaScript,
                        locale, urlRoot, requestPath, debug, "auth.bind.title");
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("errors", errorsView(ctx, session));
            ViewContext viewContext = new ViewContext(
                    translator, option, new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), null, session);
            vars.put("view", viewContext);
            String html = ViewEngine.render("auth/bind", java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    // ================================================================ 验证邮箱

    /** {@code verify()}。 */
    public Handler<RoutingContext> verify() {
        return ctx -> {
            String locale = translator.getLocale();
            if (!option.getBoolean("require_verification", false)) {
                ctx.fail(WebException.pretty(
                        translator.trans("user.verification.disabled", Map.of(), locale), 1));
                return;
            }
            String path = ctx.request().path();
            Map<String, String> qp = queryParams(ctx);
            if (!SignedUrl.hasValidSignature(path, qp, System.currentTimeMillis() / 1000L,
                    settingsKey(), null)) {
                ctx.fail(WebException.http(403, translator.trans("auth.verify.invalid", Map.of(), locale)));
                return;
            }
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            String requestPath = ctx.request().path();
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            Map<String, Object> vars;
            try {
                vars = AuthView.simpleVariables(option, translator, javaScript,
                        locale, urlRoot, requestPath, debug, "auth.verify.title");
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("errors", errorsView(ctx, session));
            ViewContext viewContext = new ViewContext(
                    translator, option, new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), null, session);
            vars.put("view", viewContext);
            String html = ViewEngine.render("auth/verify", java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    /** {@code handleVerify($user)}。 */
    public Handler<RoutingContext> handleVerify() {
        return ctx -> {
            String uid = ctx.pathParam("user");
            String locale = translator.getLocale();
            String path = ctx.request().path();
            Map<String, String> qp = queryParams(ctx);
            if (!SignedUrl.hasValidSignature(path, qp, System.currentTimeMillis() / 1000L,
                    settingsKey(), null)) {
                ctx.fail(WebException.http(403, translator.trans("auth.verify.invalid", Map.of(), locale)));
                return;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("email", RequestInput.inputString(ctx, "email"));
            Validator v = new Validator(input, translator, locale, option, null);
            v.addRule("email", "required");
            v.addRule("email", "email");
            if (!v.passes()) {
                validationFailed(ctx, v.errors(), locale);
                return;
            }
            String email = RequestInput.inputString(ctx, "email");
            int uidInt = 0;
            try { uidInt = Integer.parseInt(uid); } catch (NumberFormatException ignored) {}
            UserRepository.UserRow user;
            try { user = users.findById(uidInt); } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            if (user == null || !email.equals(user.email())) {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                if (session != null) session.flash("errorMessage",
                        translator.trans("auth.verify.not-matched", Map.of(), locale));
                // back()：优先 Referer，否则会话里的 _previous.url，最后回落根路径
                String previous = ctx.request().getHeader("Referer");
                if (previous == null || previous.isBlank()) {
                    previous = session == null ? null
                            : (session.previousUrl() == null ? null
                                    : String.valueOf(session.previousUrl()));
                }
                if (previous == null || previous.isBlank()) {
                    previous = "http://" + hostOf(ctx) + "/";
                }
                ctx.response().setStatusCode(302)
                        .putHeader("Location", previous)
                        .putHeader("Cache-Control", "no-cache, private")
                        .end();
                return;
            }
            try {
                users.updateVerified(uidInt, true);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            ctx.response().setStatusCode(302)
                    .putHeader("Location", "http://" + hostOf(ctx) + "/user")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end();
        };
    }

    // ================================================================ 内部辅助

    private javax.sql.DataSource dataSource() {
        return dataSource;
    }

    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors, String locale) {
        if (ErrorHandler.expectsJson(ctx)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", Validator.summarize(errors));
            body.put("errors", errors);
            json(ctx, 422, body);
            return;
        }
        // HTML: 302 + flash
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        // withInput (exclude dontFlash)
        if (session != null) {
            Map<String, Object> input = new LinkedHashMap<>();
            Object bag = ctx.get(RequestInput.INPUT_KEY);
            if (bag instanceof com.neoskin.support.PhpJson.Array arr) {
                arr.forEach((k, v) -> {
                    String key = String.valueOf(k);
                    if (!List.of("current_password", "password", "password_confirmation").contains(key)) {
                        input.put(key, v);
                    }
                });
            }
            session.flash("_old_input", input);
            // errors → ViewErrorBag
            session.flash("errors", toViewErrorBag(errors));
        }
        // url()->previous()
        String previous = null;
        String referer = ctx.request().getHeader("Referer");
        if (referer != null && !referer.isBlank()) previous = referer;
        if (previous == null && session != null) {
            Object pu = session.previousUrl();
            if (pu != null) previous = String.valueOf(pu);
        }
        if (previous == null) previous = "http://" + hostOf(ctx) + "/";
        ctx.response().setStatusCode(302)
                .putHeader("Location", previous)
                .putHeader("Cache-Control", "no-cache, private")
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(redirectBody(previous)));
    }

    private static String redirectBody(String url) {
        return "<!DOCTYPE html>\n<html>\n    <head>\n        <meta charset=\"UTF-8\" />\n        <meta http-equiv=\"refresh\" content=\"0;url='" + escape(url) + "' />\n\n        <title>Redirecting to " + escape(url) + "</title>\n    </head>\n    <body>\n        Redirecting to <a href=\"" + escape(url) + "\">" + escape(url) + "</a>.\n    </body>\n</html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static Object toViewErrorBag(Map<String, List<String>> errors) {
        com.neoskin.support.PhpJson.Array messages = new com.neoskin.support.PhpJson.Array();
        for (Map.Entry<String, List<String>> e : errors.entrySet()) {
            com.neoskin.support.PhpJson.Array list = new com.neoskin.support.PhpJson.Array();
            for (String m : e.getValue()) list.put((long) list.size(), m);
            messages.put(e.getKey(), list);
        }
        com.neoskin.support.PhpJson.Array mbProps = new com.neoskin.support.PhpJson.Array();
        mbProps.put("\0*\0messages", messages);
        mbProps.put("\0*\0format", ":message");
        com.neoskin.support.PhpUnserialize.PhpObject messageBag =
                new com.neoskin.support.PhpUnserialize.PhpObject(
                        "Illuminate\\Support\\MessageBag", mbProps);
        com.neoskin.support.PhpJson.Array bags = new com.neoskin.support.PhpJson.Array();
        bags.put("default", messageBag);
        com.neoskin.support.PhpJson.Array vebProps = new com.neoskin.support.PhpJson.Array();
        vebProps.put("\0*\0bags", bags);
        return new com.neoskin.support.PhpUnserialize.PhpObject(
                "Illuminate\\Support\\ViewErrorBag", vebProps);
    }

    /**
     * {@code ShareErrorsFromSession} 共享的 {@code errors}；中间件没跑（例如
     * 单测直连控制器）时回落到会话里现读，保证模板里 {@code errors} 恒存在
     * —— 对应 Twig「共享变量一定有值」的语义。
     */
    private static ErrorsView errorsView(RoutingContext ctx, SessionStore session) {
        Object shared = ctx.get(com.neoskin.web.middleware.ShareErrorsFromSession.CTX_KEY);
        if (shared instanceof Map<?, ?> map) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), (List<String>) v));
            return new ErrorsView(copy);
        }
        return new ErrorsView(errorsFromSession(session));
    }

    private static Map<String, List<String>> errorsFromSession(SessionStore session) {
        return com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                session == null ? null : session.get("errors"));
    }

    private static int sessionGuardId(SessionStore session) {
        Object v = session.get(com.neoskin.web.auth.SessionGuard.SESSION_KEY);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private String settingsKey() {
        return settings == null ? "" : settings.getKey();
    }

    private String locale() { return translator.getLocale(); }

    private Map<String, String> queryParams(RoutingContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        Object bag = ctx.get(RequestInput.QUERY_KEY);
        if (bag instanceof com.neoskin.support.PhpJson.Array arr) {
            arr.forEach((k, v) -> out.put(String.valueOf(k),
                    v == null ? "" : com.neoskin.support.PhpString.cast(v)));
        }
        return out;
    }

    private String sha1Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return s; }
    }

    private String applyUserPasswordFilter(String hashed) {
        if (filter == null) return hashed;
        Object f = filter.apply("user_password", hashed);
        return f == null ? hashed : String.valueOf(f);
    }

    /**
     * {@code Mail::to($email)->send(new ForgotPassword($url))}。
     *
     * <p>邮件通道由 {@link Mailer} 统一处理（smtp / log / array）。SMTP 未配置或
     * 连接失败时抛异常 —— 调用方按 {@code auth.forgot.failed} 返回，与源「发信失败」一致。</p>
     *
     * @throws Exception SMTP 未配置或连接失败
     */
    private void sendMail(String to, String subject, String body) throws Exception {
        new Mailer(settings).send(to, subject, body);
    }

    /** {@code SendEmailVerification}：仅当 {@code require_verification} 打开时发送。 */
    private void sendVerificationEmailIfRequired(RoutingContext ctx, int uid, String email,
            String locale) {
        if (!option.getBoolean("require_verification", false)) {
            return;
        }
        try {
            String host = hostOf(ctx);
            String url = VerificationMail.url(RouteUrlGenerator.forHost(host), settingsKey(),
                    host, uid);
            new Mailer(settings).send(email,
                    VerificationMail.subject(option, translator, locale),
                    VerificationMail.body(option, translator, locale, url));
        } catch (Exception e) {
            // 源：try/catch + report($e)，注册本身不受影响
            log.warn("验证邮件发送失败（uid={}）：{}", uid, e.getMessage());
        }
    }

    private String buildMailBody(String url, Option option, Translator translator, String locale) {
        String siteName = String.valueOf(option.getLocalized("site_name", "NeoSkin"));
        String message = translator.trans("auth.forgot.mail.message", Map.of("sitename", siteName), locale);
        String reset = translator.trans("auth.forgot.mail.reset", Map.of("url", url), locale);
        String ignore = translator.trans("auth.forgot.mail.ignore", Map.of(), locale);
        return "<p>" + message + "</p><p>" + reset + "</p><p>" + ignore + "</p>";
    }

    // ================================================================ ErrorsView

    /**
     * 模板里的 {@code errors}（对应 Twig 的 {@code errors.any} / {@code errors.first}）。
     *
     * <p>{@code Illuminate\Support\ViewErrorBag} 在 Twig 里是
     * {@code ViewErrorBag::any()} / {@code ViewErrorBag::first()}（转发给
     * {@code MessageBag}）。Thymeleaf 的标准方言用 OGNL，属性访问既认
     * JavaBean getter 也认同名方法，所以这里两个都给上，避免方言版本差异。</p>
     */
    public static final class ErrorsView {

        private final Map<String, List<String>> errors;

        public ErrorsView(Map<String, List<String>> errors) {
            this.errors = errors == null ? Map.of() : errors;
        }

        /** {@code errors.any}。 */
        public boolean isAny() {
            return any();
        }

        /** {@code errors.any}（方法形式）。 */
        public boolean any() {
            return !errors.isEmpty();
        }

        /** {@code errors.first}。 */
        public String getFirst() {
            return first();
        }

        /** {@code errors.first}（方法形式）。 */
        public String first() {
            for (List<String> list : errors.values()) {
                if (!list.isEmpty()) {
                    return list.get(0);
                }
            }
            return "";
        }

        /** {@code errors.all()}（Twig 语义：拍平后的消息数组）。 */
        public List<String> all() {
            List<String> all = new java.util.ArrayList<>();
            for (List<String> list : errors.values()) {
                all.addAll(list);
            }

            return all;
        }

        /** 兼容既有模板：按字段分组读取。 */
        public Map<String, List<String>> getAll() {
            return errors;
        }

        /** {@code errors.count()}。 */
        public int count() {
            int total = 0;
            for (List<String> list : errors.values()) {
                total += list.size();
            }
            return total;
        }

        @Override
        public String toString() {
            return String.valueOf(errors);
        }
    }

    /**
     * 供 {@code HandlerResolver} 使用的工厂（无 {@code DataSource} / {@code settings}：
     * {@code unique:*} 规则退化为跳过，签名 URL 用空 key —— 仅测试/未接线场景）。
     */
    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, LoginFailCounter failures,
            UserRepository users, PlayerRepository players, Cipher cipher, String salt,
            boolean debug) {
        return resolver(option, translator, javaScript, filter, failures,
                users, players, cipher, salt, debug, null, null);
    }

    /**
     * 供 {@code HandlerResolver} 使用的工厂。
     *
     * @param dataSource {@code unique:users} 的存在性校验用；{@code null} 时跳过
     * @param settings   {@code config('app.key')} 与 {@code config('mail.*')}
     */
    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, LoginFailCounter failures,
            UserRepository users, PlayerRepository players, Cipher cipher, String salt,
            boolean debug, DataSource dataSource, NeoSkinSettings settings) {
        AuthController c = new AuthController(option, translator, javaScript, filter,
                failures, users, players, cipher, salt, debug, dataSource, settings);
        return new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                return switch (action) {
                    case "App\\Http\\Controllers\\AuthController@login" -> c.login();
                    case "App\\Http\\Controllers\\AuthController@handleLogin" -> c.handleLogin();
                    case "App\\Http\\Controllers\\AuthController@logout" -> c.logout();
                    case "App\\Http\\Controllers\\AuthController@register" -> c.register();
                    case "App\\Http\\Controllers\\AuthController@handleRegister" -> c.handleRegister();
                    case "App\\Http\\Controllers\\AuthController@forgot" -> c.forgot();
                    case "App\\Http\\Controllers\\AuthController@handleForgot" -> c.handleForgot();
                    case "App\\Http\\Controllers\\AuthController@reset" -> c.reset();
                    case "App\\Http\\Controllers\\AuthController@handleReset" -> c.handleReset();
                    case "App\\Http\\Controllers\\AuthController@captcha" -> c.captcha();
                    case "App\\Http\\Controllers\\AuthController@fillEmail" -> c.fillEmail();
                    case "App\\Http\\Controllers\\AuthController@verify" -> c.verify();
                    case "App\\Http\\Controllers\\AuthController@handleVerify" -> c.handleVerify();
                    default -> null;
                };
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = resolve(action);
                if (handler != null) {
                    return handler;
                }
                // Illuminate\Routing\ViewController：同一 action 被多条 Route::view 共用，
                // 只有结合 uri 才知道渲染哪个模板。本阶段仅 auth/bind 已移植。
                if ("Illuminate\\Routing\\ViewController".equals(action)
                        && "auth/bind".equals(uri)) {
                    return c.bindView();
                }
                return null;
            }
        };
    }
}
