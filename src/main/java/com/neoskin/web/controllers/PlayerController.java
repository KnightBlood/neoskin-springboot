package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.Rule;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\PlayerController} 的移植。
 *
 * <p>已实现 JSON actions（list / add / delete / rename / setTexture / clearTexture）；
 * {@code index} 视图页待模板。</p>
 */
public final class PlayerController {

    private final Option option;
    private final Translator translator;
    private final PlayerRepository players;
    private final TextureRepository textures;
    private final UserRepository users;
    private final DataSource dataSource;
    private final String timezone;
    private final com.neoskin.web.i18n.JavaScript javaScript;
    private final com.neoskin.service.BlessingFilter filter;
    private final boolean debug;

    public PlayerController(Option option, Translator translator, PlayerRepository players,
            TextureRepository textures, UserRepository users, DataSource dataSource,
            String timezone, com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter, boolean debug) {
        this.option = option;
        this.translator = translator;
        this.players = players;
        this.textures = textures;
        this.users = users;
        this.dataSource = dataSource;
        this.timezone = timezone;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
    }

    /** {@code GET user/player/list} / {@code GET api/players}。 */
    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (PlayerRepository.PlayerRow row : players.listByUid(user.uid())) {
                    // 源：Player 模型的 'retrieved' 事件
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PlayerRetrieved(row));
                    out.add(playerJson(row.pid(), row.uid(), row.name(), row.tidCape(),
                            players.lastModified(row.pid()), row.tidSkin()));
                }
                json(ctx, 200, out);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code POST user/player} / {@code POST api/players}。 */
    public Handler<RoutingContext> add() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                Validator v = validator(ctx);
                v.addRule("name", "required");
                v.addRule("name", new PlayerNameRule(option, translator));
                v.addRule("name", "min:" + option.get("player_name_length_min"));
                v.addRule("name", "max:" + option.get("player_name_length_max"));
                v.addRule("name", "unique:players");
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                String name = String.valueOf(v.validated().get("name"));
                int cost = toInt(option.get("score_per_player"));                if (user.score() < cost) {
                    json(ctx, 200, message(7,
                            translator.trans("user.player.add.lack-score", Map.of(),
                                    translator.getLocale())));
                    return;
                }
                // 源：PlayerWillBeAdded 在写库前触发；PlayerWasAdded 在写库后
                com.neoskin.event.EventRuntime.fire(
                        new com.neoskin.event.Events.PlayerWillBeAdded(name));
                int pid = players.insert(user.uid(), name, com.neoskin.support.PhpDate.now(timezone));
                users.updateScore(user.uid(), user.score() - cost);
                // 源：PlayerWasAdded（注册与手动添加都会触发）
                PlayerRepository.PlayerRow created = players.findByPid(pid);
                if (created != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PlayerWasAdded(created));
                }

                Map<String, Object> body = message(0,
                        translator.trans("user.player.add.success", Map.of("name", name),
                                translator.getLocale()),
                        playerJson(pid, user.uid(), name, 0,
                                players.lastModified(pid), -1));
                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code DELETE user/player/{player}} / {@code DELETE api/players/{player}}。 */
    public Handler<RoutingContext> delete() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                PlayerRepository.PlayerRow player = ownedPlayer(ctx, user);
                if (player == null) {
                    return;
                }
                // 源：PlayerWillBeDeleted → delete → PlayerWasDeleted
                com.neoskin.event.EventRuntime.fire(
                        new com.neoskin.event.Events.PlayerWillBeDeleted(player));
                String name = player.name();
                players.delete(player.pid());
                com.neoskin.event.EventRuntime.fire(
                        new com.neoskin.event.Events.PlayerWasDeleted(name));
                if (toBool(option.get("return_score"))) {
                    users.updateScore(user.uid(),
                            user.score() + toInt(option.get("score_per_player")));
                }
                json(ctx, 200, message(0,
                        translator.trans("user.player.delete.success", Map.of("name", name),
                                translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT user/player/{player}/name}。 */
    public Handler<RoutingContext> rename() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                PlayerRepository.PlayerRow player = ownedPlayer(ctx, user);
                if (player == null) {
                    return;
                }
                Validator v = validator(ctx);
                v.addRule("name", "required");
                v.addRule("name", new PlayerNameRule(option, translator));
                v.addRule("name", "min:" + option.get("player_name_length_min"));
                v.addRule("name", "max:" + option.get("player_name_length_max"));
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                String name = String.valueOf(v.validated().get("name"));
                PlayerRepository.PlayerRow duplicated = players.findByName(name);
                if (duplicated != null && duplicated.pid() != player.pid()) {
                    Map<String, List<String>> errors = new LinkedHashMap<>();
                    errors.put("name", List.of(translator.trans("validation.unique",
                            Map.of("attribute", "name"), translator.getLocale())));
                    validationFailed(ctx, errors);
                    return;
                }
                players.updateName(player.pid(), name, com.neoskin.support.PhpDate.now(timezone));
                // 源：Player 模型的 'updated' 事件 → PlayerProfileUpdated
                PlayerRepository.PlayerRow renamed = players.findByPid(player.pid());
                if (renamed != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PlayerProfileUpdated(renamed));
                }
                Map<String, Object> body = message(0,
                        translator.trans("user.player.rename.success",
                                Map.of("old", player.name(), "new", name), translator.getLocale()),
                        playerJson(player.pid(), player.uid(), name, player.tidCape(),
                                players.lastModified(player.pid()), player.tidSkin()));
                json(ctx, 200, body);
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT user/player/{player}/textures}。 */
    public Handler<RoutingContext> setTexture() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                PlayerRepository.PlayerRow player = ownedPlayer(ctx, user);
                if (player == null) {
                    return;
                }
                int tidSkin = player.tidSkin();
                int tidCape = player.tidCape();
                for (String type : List.of("skin", "cape")) {
                    Object raw = RequestInput.input(ctx, type);
                    if (raw == null || String.valueOf(raw).isEmpty() || toInt(raw) == 0) {
                        continue;
                    }
                    int tid = toInt(raw);
                    TextureRepository.Row texture = textures.findByTid(tid);
                    if (texture == null) {
                        json(ctx, 200, message(1,
                                translator.trans("skinlib.non-existent", Map.of(),
                                        translator.getLocale())));
                        return;
                    }
                    if (!users.inCloset(user.uid(), tid)) {
                        json(ctx, 200, message(1,
                                translator.trans("user.closet.remove.non-existent", Map.of(),
                                        translator.getLocale())));
                        return;
                    }
                    if ("skin".equals(type)) {
                        tidSkin = tid;
                    } else {
                        tidCape = tid;
                    }
                }
                players.updateTextures(player.pid(), tidSkin, tidCape,
                        com.neoskin.support.PhpDate.now(timezone));
                // 源：Player 模型的 'updated' 事件 → PlayerProfileUpdated
                PlayerRepository.PlayerRow updatedPlayer = players.findByPid(player.pid());
                if (updatedPlayer != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PlayerProfileUpdated(updatedPlayer));
                }
                json(ctx, 200, message(0,
                        translator.trans("user.player.set.success", Map.of("name", player.name()),
                                translator.getLocale()),
                        playerJson(player.pid(), player.uid(), player.name(), tidCape,
                                players.lastModified(player.pid()), tidSkin)));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code DELETE user/player/{player}/textures}。 */
    public Handler<RoutingContext> clearTexture() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                PlayerRepository.PlayerRow player = ownedPlayer(ctx, user);
                if (player == null) {
                    return;
                }
                int tidSkin = player.tidSkin();
                int tidCape = player.tidCape();
                Object typesRaw = RequestInput.input(ctx, "type");
                List<String> types = new ArrayList<>();
                if (typesRaw instanceof List<?> list) {
                    for (Object o : list) {
                        types.add(String.valueOf(o));
                    }
                }
                if (RequestInput.input(ctx, "skin") != null || types.contains("skin")) {
                    tidSkin = 0;
                }
                if (RequestInput.input(ctx, "cape") != null || types.contains("cape")) {
                    tidCape = 0;
                }
                players.updateTextures(player.pid(), tidSkin, tidCape,
                        com.neoskin.support.PhpDate.now(timezone));
                // 源：Player 模型的 'updated' 事件 → PlayerProfileUpdated
                PlayerRepository.PlayerRow updatedPlayer = players.findByPid(player.pid());
                if (updatedPlayer != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PlayerProfileUpdated(updatedPlayer));
                }
                json(ctx, 200, message(0,
                        translator.trans("user.player.clear.success", Map.of("name", player.name()),
                                translator.getLocale()),
                        playerJson(player.pid(), player.uid(), player.name(), tidCape,
                                players.lastModified(player.pid()), tidSkin)));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code GET user/player}。 */
    public Handler<RoutingContext> index() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String locale = translator.getLocale();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("count", players.countByUser(user.uid()));
                extra.put("rule", translator.trans("user.player.player-name-rule."
                        + option.get("player_name_rule"), Map.of(), locale));
                extra.put("length", translator.trans("user.player.player-name-length", Map.of(
                        "min", String.valueOf(option.get("player_name_length_min")),
                        "max", String.valueOf(option.get("player_name_length_max"))), locale));
                extra.put("score", user.score());
                extra.put("cost", toInt(option.get("score_per_player")));

                Map<String, Object> vars = pageVars(ctx, user,
                        translator.trans("general.player-manage", Map.of(), locale), locale);
                vars.put("gridRows", List.of(
                        List.of(
                                col("md-6", "user/widgets/players/list",
                                        "user/widgets/players/notice"),
                                col("md-6", "shared/previewer"))));
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "user/player", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private Map<String, Object> pageVars(RoutingContext ctx, UserRepository.UserRow user,
            String title, String locale) throws Exception {
        String host = ctx.request().getHeader("Host");
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
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

    private static Map<String, Object> col(String css, String... widgets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", css);
        m.put("widgets", List.of(widgets));
        return m;
    }

    // ============================================================ helpers

    /** 取路径上的 {@code player} 并校验归属；失败时已写响应。 */
    private PlayerRepository.PlayerRow ownedPlayer(RoutingContext ctx, UserRepository.UserRow user)
            throws SQLException {
        PlayerRepository.PlayerRow player = players.findByPid(toInt(ctx.pathParam("player")));
        if (player == null) {
            ctx.fail(404);
            return null;
        }
        if (player.uid() != user.uid()) {
            Map<String, Object> body = message(1,
                    translator.trans("admin.players.no-permission", Map.of(),
                            translator.getLocale()));
            String payload = PhpJson.encode(body, 0);
            ctx.response().setStatusCode(403)
                    .putHeader("Content-Type", "application/json")
                    .end(Buffer.buffer(payload, "UTF-8"));
            return null;
        }
        return player;
    }

    private Validator validator(RoutingContext ctx) {
        return new Validator(inputMap(ctx), translator, translator.getLocale(), option,
                new com.neoskin.web.validation.JdbcPresenceVerifier(dataSource));
    }

    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
    }

    private UserRepository.UserRow currentUser(RoutingContext ctx) throws SQLException {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            return null;
        }
        return new com.neoskin.web.auth.SessionGuard(session, users).user();
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

    /** {@code Player::toArray()} 的键序（pid, uid, name, tid_cape, last_modified, tid_skin）。 */
    private static Map<String, Object> playerJson(int pid, int uid, String name, int tidCape,
            String lastModified, int tidSkin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", pid);
        m.put("uid", uid);
        m.put("name", name);
        m.put("tid_cape", tidCape);
        m.put("last_modified", lastModified);
        m.put("tid_skin", tidSkin);
        return m;
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

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v);
        return !(s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false"));
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

    /** {@code App\Rules\PlayerName}。 */
    private static final class PlayerNameRule implements Rule {
        private final Option option;
        private final Translator translator;

        PlayerNameRule(Option option, Translator translator) {
            this.option = option;
            this.translator = translator;
        }

        @Override
        public boolean passes(String attribute, Object value, Validator validator) {
            String v = value == null ? "" : String.valueOf(value);
            String rule = String.valueOf(option.get("player_name_rule"));
            return switch (rule) {
                case "official" -> v.matches("^[A-Za-z0-9_]+$");
                case "cjk" -> v.matches("^[A-Za-z0-9_§\\u4e00-\\u9fff]+$");
                case "utf8" -> !v.matches(".*\\s.*") && java.nio.charset.StandardCharsets.UTF_8
                        .newEncoder().canEncode(v);
                case "custom" -> {
                    Object custom = option.get("custom_player_name_regexp");
                    String regex = custom == null || String.valueOf(custom).isEmpty()
                            ? ".*" : String.valueOf(custom);
                    yield v.matches(regex);
                }
                default -> true;
            };
        }

        @Override
        public String message() {
            String rule = String.valueOf(option.get("player_name_rule"));
            return translator.trans("user.player.player-name-rule." + rule, Map.of(),
                    translator.getLocale());
        }
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            PlayerRepository players, TextureRepository textures, UserRepository users,
            DataSource dataSource, String timezone, com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter, boolean debug) {
        PlayerController c = new PlayerController(option, translator, players, textures, users,
                dataSource, timezone, javaScript, filter, debug);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\PlayerController@index" -> c.index();
            case "App\\Http\\Controllers\\PlayerController@list" -> c.list();
            case "App\\Http\\Controllers\\PlayerController@add" -> c.add();
            case "App\\Http\\Controllers\\PlayerController@delete" -> c.delete();
            case "App\\Http\\Controllers\\PlayerController@rename" -> c.rename();
            case "App\\Http\\Controllers\\PlayerController@setTexture" -> c.setTexture();
            case "App\\Http\\Controllers\\PlayerController@clearTexture" -> c.clearTexture();
            default -> null;
        };
    }
}