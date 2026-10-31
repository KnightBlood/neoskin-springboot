package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.db.ClosetRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
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
 * {@code App\Http\Controllers\ClosetController} 的移植。
 *
 * <p>源实现里的 {@code Dispatcher}（closet.* 系列）与 {@code Filter}
 * （add_closet_item_name 等）在无监听器时为 no-op，此处直通；
 * 事件总线接入后需补挂点。</p>
 */
public final class ClosetController {

    private final Option option;
    private final Translator translator;
    private final ClosetRepository closet;
    private final TextureRepository textures;
    private final UserRepository users;
    private final DataSource dataSource;
    private final String timezone;
    private final com.neoskin.web.i18n.JavaScript javaScript;
    private final com.neoskin.service.BlessingFilter filter;
    private final boolean debug;

    public ClosetController(Option option, Translator translator, ClosetRepository closet,
            TextureRepository textures, UserRepository users, DataSource dataSource,
            String timezone, com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter, boolean debug) {
        this.option = option;
        this.translator = translator;
        this.closet = closet;
        this.textures = textures;
        this.users = users;
        this.dataSource = dataSource;
        this.timezone = timezone;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
    }

    /** {@code GET user/closet}。 */
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
                extra.put("unverified",
                        toBool(option.get("require_verification")) && !user.verified());
                extra.put("rule", translator.trans("user.player.player-name-rule."
                        + option.get("player_name_rule"), Map.of(), locale));
                extra.put("length", translator.trans("user.player.player-name-length", Map.of(
                        "min", String.valueOf(option.get("player_name_length_min")),
                        "max", String.valueOf(option.get("player_name_length_max"))), locale));

                Map<String, Object> vars = pageVars(ctx, user,
                        translator.trans("general.my-closet", Map.of(), locale), locale);
                vars.put("gridRows", List.of(
                        List.of(
                                col("md-8", "user/widgets/email-verification",
                                        "user/widgets/closet/list"),
                                col("md-4", "shared/previewer"))));
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "user/closet", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code GET user/closet/list} / {@code GET api/closet}：LengthAwarePaginator JSON。 */
    public Handler<RoutingContext> getClosetData() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String category = string(RequestInput.input(ctx, "category"), "skin");
                String search = nonEmpty(RequestInput.input(ctx, "q"));
                int page = Math.max(1, toInt(RequestInput.input(ctx, "page")));
                int perPage = toInt(orDefault(RequestInput.input(ctx, "perPage"), 6));

                long total = closet.count(user.uid(), category, search);
                long last = Math.max(1, (total + perPage - 1) / perPage);
                long offset = (long) (page - 1) * perPage;
                List<ClosetRepository.Item> items =
                        closet.page(user.uid(), category, search,
                                perPage, (int) offset);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ClosetRepository.Item item : items) {
                    Map<String, Object> m = ClosetRepository.textureJson(item.texture());
                    Map<String, Object> pivot = new LinkedHashMap<>();
                    pivot.put("user_uid", item.userUid());
                    pivot.put("texture_tid", item.textureTid());
                    pivot.put("item_name", item.itemName());
                    m.put("pivot", pivot);
                    rows.add(m);
                }

                String path = "http://" + hostOf(ctx) + pathOf(ctx) ;
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

    /** {@code GET user/closet/ids}：{@code pluck('texture_tid')}。 */
    public Handler<RoutingContext> allIds() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                json(ctx, 200, closet.allIds(user.uid()));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code POST user/closet} / {@code POST api/closet}。 */
    public Handler<RoutingContext> add() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                Validator v = validator(ctx);
                v.addRule("tid", "required");
                v.addRule("tid", "integer");
                v.addRule("name", "required");
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                Map<String, Object> data = v.validated();
                long tid = toLong(data.get("tid"));
                String name = String.valueOf(data.get("name"));

                // filter 'add_closet_item_name' / event 'closet.adding'：无监听，no-op
                // filter 'can_add_closet_item'：无监听，true
                if (user.score() < toInt(option.get("score_per_closet_item"))) {
                    json(ctx, 200, message(1, translator.trans("user.closet.add.lack-score",
                            Map.of(), translator.getLocale())));
                    return;
                }
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    json(ctx, 200, message(1, translator.trans("user.closet.add.not-found",
                            Map.of(), translator.getLocale())));
                    return;
                }
                if (!texture.isPublic() && texture.uploader() != user.uid() && !user.isAdmin()) {
                    json(ctx, 200, message(1, translator.trans("skinlib.show.private",
                            Map.of(), translator.getLocale())));
                    return;
                }
                if (closet.exists(user.uid(), tid)) {
                    json(ctx, 200, message(1, translator.trans("user.closet.add.repeated",
                            Map.of(), translator.getLocale())));
                    return;
                }

                closet.attach(user.uid(), tid, name);
                users.updateScore(user.uid(),
                        user.score() - toInt(option.get("score_per_closet_item")));
                textures.updateLikes(tid, texture.likes() + 1);

                UserRepository.UserRow uploader = users.findById(texture.uploader());
                if (uploader != null && uploader.uid() != user.uid()) {
                    users.updateScore(uploader.uid(),
                            uploader.score() + toInt(option.get("score_award_per_like", 0)));
                }

                json(ctx, 200, message(0, translator.trans("user.closet.add.success",
                        Map.of("name", name), translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code PUT user/closet/{tid}} / {@code PUT api/closet/{tid}}。 */
    public Handler<RoutingContext> rename() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                Validator v = validator(ctx);
                v.addRule("name", "required");
                if (!v.passes()) {
                    validationFailed(ctx, v.errors());
                    return;
                }
                String name = String.valueOf(v.validated().get("name"));
                long tid = toLong(ctx.pathParam("tid"));

                ClosetRepository.Item item = closet.find(user.uid(), tid);
                if (item == null) {
                    json(ctx, 200, message(1, translator.trans("user.closet.remove.non-existent",
                            Map.of(), translator.getLocale())));
                    return;
                }

                closet.renameItem(user.uid(), tid, name);
                json(ctx, 200, message(0, translator.trans("user.closet.rename.success",
                        Map.of("name", name), translator.getLocale())));
            } catch (SQLException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code DELETE user/closet/{tid}} / {@code DELETE api/closet/{tid}}。 */
    public Handler<RoutingContext> remove() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long tid = toLong(ctx.pathParam("tid"));

                ClosetRepository.Item item = closet.find(user.uid(), tid);
                if (item == null) {
                    json(ctx, 200, message(1, translator.trans("user.closet.remove.non-existent",
                            Map.of(), translator.getLocale())));
                    return;
                }

                closet.detach(user.uid(), tid);
                if (toBool(option.get("return_score"))) {
                    users.updateScore(user.uid(),
                            user.score() + toInt(option.get("score_per_closet_item")));
                }

                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture != null) {
                    textures.updateLikes(tid, texture.likes() - 1);
                    // 源：$dispatcher->dispatch('closet.removed', [$texture, $user])
                    com.neoskin.event.EventRuntime.fire("closet.removed",
                            dataSource, texture, user.uid());
                    UserRepository.UserRow uploader = users.findById(texture.uploader());
                    if (uploader != null) {
                        users.updateScore(uploader.uid(),
                                uploader.score() - toInt(option.get("score_award_per_like", 0)));
                    }
                }

                json(ctx, 200, message(0, translator.trans("user.closet.remove.success",
                        Map.of(), translator.getLocale())));
            } catch (SQLException e) {
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
                javaScript, filter, user, locale, urlRoot, ctx.request().path(), debug, "user",
                vc);
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

    /** {@code LengthAwarePaginator::links()}：未知当前页之外的每页都列出（同 Skinlib）。 */
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

    private static String pathOf(RoutingContext ctx) {
        return ctx.request().path();
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

    private static long toLong(Object v) {
        return toInt(v);
    }

    private static String string(Object v, String fallback) {
        return v == null || String.valueOf(v).isEmpty() ? fallback : String.valueOf(v);
    }

    private static String nonEmpty(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static Object orDefault(Object v, int fallback) {
        return v == null ? fallback : v;
    }

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            ClosetRepository closet, TextureRepository textures, UserRepository users,
            DataSource dataSource, String timezone, com.neoskin.web.i18n.JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter, boolean debug) {
        ClosetController c = new ClosetController(option, translator, closet, textures, users,
                dataSource, timezone, javaScript, filter, debug);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\ClosetController@index" -> c.index();
            case "App\\Http\\Controllers\\ClosetController@getClosetData" -> c.getClosetData();
            case "App\\Http\\Controllers\\ClosetController@allIds" -> c.allIds();
            case "App\\Http\\Controllers\\ClosetController@add" -> c.add();
            case "App\\Http\\Controllers\\ClosetController@rename" -> c.rename();
            case "App\\Http\\Controllers\\ClosetController@remove" -> c.remove();
            default -> null;
        };
    }
}
