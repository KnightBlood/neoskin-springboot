package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.CommunicationRenderer;
import com.neoskin.service.Communications;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpArr;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\CommunicationsController} 的移植。
 */
public final class CommunicationsController {

    private final Option option;
    private final Translator translator;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final UserRepository users;
    private final boolean debug;
    private final DataSource dataSource;
    private final Communications service;
    private final CommunicationRepository communications;
    private final NotificationRepository notifications;
    private final CommunicationRenderer renderer;
    private final String timezone;

    public CommunicationsController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, UserRepository users, boolean debug,
            DataSource dataSource, Communications service,
            CommunicationRepository communications, NotificationRepository notifications,
            CommunicationRenderer renderer, String timezone) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.filter = filter;
        this.users = users;
        this.debug = debug;
        this.dataSource = dataSource;
        this.service = service;
        this.communications = communications;
        this.notifications = notifications;
        this.renderer = renderer;
        this.timezone = timezone;
    }

    // ============================================================ index

    public Handler<RoutingContext> index() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                Object edit = RequestInput.query(ctx, "edit");
                CommunicationRepository.Row editing = null;
                if (isFilled(edit)) {
                    editing = communications.find(toLong(edit));
                    if (editing == null) {
                        ctx.fail(404);
                        return;
                    }
                }
                boolean announcement = toBool(RequestInput.query(ctx, "announcement"));
                Map<String, Object> announcementSeed = null;
                if (announcement) {
                    editing = service.currentAnnouncement(locale);
                    if (editing != null && "both".equals(editing.kind)) {
                        announcementSeed = editing.toArray();
                        announcementSeed.put("kind", "announcement");
                        editing = null;
                    }
                }

                long total = communications.countMessages();
                int page = Math.max(1, (int) toLong(RequestInput.query(ctx, "page")));
                int perPage = 15;
                long offset = (long) (page - 1) * perPage;
                List<CommunicationRepository.Row> rows = communications.messagePage(perPage, offset);
                List<Map<String, Object>> history = new ArrayList<>();
                RouteUrlGenerator routes = RouteUrlGenerator.forHost(hostOf(ctx));
                for (CommunicationRepository.Row row : rows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.id);
                    item.put("title", row.title);
                    item.put("kind", row.kind);
                    item.put("status", row.status);
                    item.put("read_count", notifications.countRead(row.id));
                    item.put("delivery_count", notifications.countDelivery(row.id));
                    item.put("updated_at", row.updatedAt);
                    item.put("edit_url", routes.route("admin.communications.index",
                            Map.of("edit", row.id), true));
                    item.put("withdraw_url", routes.route("admin.communications.withdraw",
                            Map.of("communication", row.id), true));
                    history.add(item);
                }
                Map<String, Object> pager = paginator(history, total, page, perPage, "/admin/communications");
                pager.put("items", history);

                Map<String, Object> initial;
                if (editing != null) {
                    initial = editing.toArray();
                } else if (announcementSeed != null) {
                    initial = announcementSeed;
                } else {
                    initial = new LinkedHashMap<>();
                    initial.put("title", announcement ? "站点公告" : "");
                    initial.put("kind", announcement ? "announcement" : "message");
                    initial.put("editor", "blocks");
                    initial.put("blocks", List.of());
                    initial.put("source", "");
                    initial.put("locale", "");
                    initial.put("audience", Map.of("receiver", "all"));
                }
                for (String key : List.of("title", "kind", "editor", "source", "locale", "blocks")) {
                    initial.put(key, old(ctx, key, initial.get(key)));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> aud = initial.get("audience") instanceof Map
                        ? (Map<String, Object>) initial.get("audience") : Map.of();
                Map<String, Object> audience = new LinkedHashMap<>();
                audience.put("receiver", old(ctx, "receiver", aud.getOrDefault("receiver", "all")));
                audience.put("uid", old(ctx, "uid", aud.get("uid")));
                audience.put("email", old(ctx, "email", aud.get("email")));
                initial.put("audience", audience);

                Map<String, Object> vars = pageVars(ctx, "admin", "通知与公告", locale);
                vars.put("messages", history);
                vars.put("pagination", pager);
                vars.put("index_url", routes.route("admin.communications.index", Map.of(), true));
                vars.put("new_url", routes.route("admin.communications.index", Map.of("new", 1), true));
                vars.put("announcement_url", routes.route("admin.communications.index",
                        Map.of("announcement", 1), true));
                vars.put("form_action", editing != null
                        ? routes.route("admin.communications.update",
                                Map.of("communication", editing.id), true)
                        : routes.route("admin.communications.store", Map.of(), true));
                vars.put("preview_url", routes.route("admin.communications.preview", Map.of(), true));
                vars.put("import_url", routes.route("admin.communications.import", Map.of(), true));
                vars.put("editing_status", editing == null ? "" : editing.status);
                if (editing != null) {
                    vars.put("editing", editing);
                }
                vars.put("editor_data", initial);
                vars.put("editor_data_json", PhpJson.encode(initial,
                        PhpJson.JSON_HEX_TAG | PhpJson.JSON_HEX_AMP
                                | PhpJson.JSON_HEX_APOS | PhpJson.JSON_HEX_QUOT));
                boolean editorOpen = editing != null || announcement
                        || toBool(RequestInput.query(ctx, "new")) || hasOldInput(ctx);
                vars.put("editor_open", editorOpen);
                render(ctx, "admin/communications", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ store

    public Handler<RoutingContext> store() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                Map<String, Object> data = validateContent(ctx);
                if (data == null) {
                    return;
                }
                CommunicationRepository.Row message = service.save(data, null, user == null ? 0 : user.uid());
                if (wantsJson(ctx)) {
                    json(ctx, 200, savedBody(
                            "published".equals(message.status) ? "内容已发布。" : "草稿已保存。",
                            message.id, message.status));
                    return;
                }
                Map<String, Object> params = "announcement".equals(message.kind)
                        ? Map.of("announcement", 1) : Map.of("edit", message.id);
                redirect(ctx, RouteUrlGenerator.forHost(hostOf(ctx))
                        .route("admin.communications.index", params, true),
                        "communication_notice",
                        "published".equals(message.status) ? "内容已发布。" : "草稿已保存。");
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ update

    public Handler<RoutingContext> update() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                long id = toLong(ctx.pathParam("communication"));
                CommunicationRepository.Row communication = communications.find(id);
                if (communication == null) {
                    ctx.fail(404);
                    return;
                }
                Map<String, Object> data = validateContent(ctx);
                if (data == null) {
                    return;
                }
                if ("published".equals(communication.status)) {
                    data.put("audience", communication.audience);
                }
                CommunicationRepository.Row saved = service.save(data, communication,
                        user == null ? 0 : user.uid());
                if (wantsJson(ctx)) {
                    json(ctx, 200, savedBody("内容已更新。", saved.id, saved.status));
                    return;
                }
                Map<String, Object> params = "announcement".equals(saved.kind)
                        ? Map.of("announcement", 1) : Map.of("edit", saved.id);
                redirect(ctx, RouteUrlGenerator.forHost(hostOf(ctx))
                        .route("admin.communications.index", params, true),
                        "communication_notice", "内容已更新。");
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ withdraw

    public Handler<RoutingContext> withdraw() {
        return ctx -> {
            try {
                long id = toLong(ctx.pathParam("communication"));
                CommunicationRepository.Row communication = communications.find(id);
                if (communication == null) {
                    ctx.fail(404);
                    return;
                }
                service.withdraw(communication);
                if (wantsJson(ctx)) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", 0);
                    body.put("message", "内容已撤回。");
                    json(ctx, 200, body);
                    return;
                }
                redirect(ctx, RouteUrlGenerator.forHost(hostOf(ctx))
                        .route("admin.communications.index", Map.of(), true),
                        "communication_notice", "内容已撤回。");
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ importSource

    public Handler<RoutingContext> importSource() {
        return ctx -> {
            Object source = RequestInput.input(ctx, "source");
            String text = source == null ? "" : String.valueOf(source);
            if (codepointLength(text) > 100000) {
                validationJson(ctx, Map.of("source", List.of("source 不能超过 100000 个字符。")));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("blocks", renderer.importBlocks(text));
            json(ctx, 200, body);
        };
    }

    // ============================================================ preview

    public Handler<RoutingContext> preview() {
        return ctx -> {
            try {
                // merge: title || '预览'，kind=announcement，receiver=all，operation=draft
                Object title = RequestInput.input(ctx, "title");
                if (!isFilled(title)) {
                    putInput(ctx, "title", "预览");
                }
                putInput(ctx, "kind", "announcement");
                putInput(ctx, "receiver", "all");
                putInput(ctx, "operation", "draft");
                Map<String, Object> data = validateContent(ctx);
                if (data == null) {
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("html", renderer.render(data, null));
                String payload = PhpJson.encode(body, 0);
                ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .putHeader("Cache-Control", "no-store")
                        .end(Buffer.buffer(payload, "UTF-8"));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ inbox

    public Handler<RoutingContext> inbox() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                long total = notifications.countInbox(user.uid());
                int page = Math.max(1, (int) toLong(RequestInput.query(ctx, "page")));
                int perPage = 20;
                long offset = (long) (page - 1) * perPage;
                List<NotificationRepository.Row> rows =
                        notifications.inboxPage(user.uid(), perPage, offset);
                List<NotificationRepository.Row> visible = new ArrayList<>();
                List<Map<String, Object>> items = new ArrayList<>();
                for (NotificationRepository.Row row : rows) {
                    if (service.visible(row)) {
                        row.displayTitle = service.title(row);
                        visible.add(row);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", row.id);
                        item.put("display_title", row.displayTitle);
                        item.put("read_at", row.readAt);
                        item.put("created_at", row.createdAt);
                        item.put("message_url", RouteUrlGenerator.forHost(hostOf(ctx))
                                .route("user.inbox.message", Map.of("id", row.id), true));
                        items.add(item);
                    }
                }
                Map<String, Object> pager = paginator(items, total, page, perPage, "/user/inbox");
                pager.put("items", items);
                String locale = translator.getLocale();
                Map<String, Object> vars = pageVars(ctx, "user", "站内信", locale);
                vars.put("messages", pager);
                render(ctx, "user/inbox", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ message

    public Handler<RoutingContext> message() {
        return ctx -> {
            try {
                UserRepository.UserRow user = currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String id = ctx.pathParam("id");
                NotificationRepository.Row message =
                        notifications.findByNotifiableAndId(user.uid(), id);
                if (message == null || !service.visible(message)) {
                    ctx.fail(404);
                    return;
                }
                notifications.markAsRead(id, com.neoskin.support.PhpDate.now(timezone));
                String locale = translator.getLocale();
                Map<String, Object> vars = pageVars(ctx, "user", service.title(message), locale);
                vars.put("message", message);
                vars.put("message_created_at", message.createdAt);
                vars.put("inbox_url", RouteUrlGenerator.forHost(hostOf(ctx))
                        .route("user.inbox", Map.of(), true));
                vars.put("body_html", service.content(message));
                vars.put("message_title", service.title(message));
                render(ctx, "user/message", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ validation

    /** {@code validateContent()}；失败时已写响应并返回 {@code null}。 */
    private Map<String, Object> validateContent(RoutingContext ctx) throws SQLException {
        if (hasInput(ctx, "blocks_json")) {
            Object raw = RequestInput.input(ctx, "blocks_json");
            if (!isFilled(raw) || !isJson(String.valueOf(raw))) {
                validationFailed(ctx, Map.of("blocks_json", List.of("blocks_json 不能为空。")));
                return null;
            }
            putInput(ctx, "blocks", PhpJson.decode(String.valueOf(raw)));
        }

        Map<String, Object> input = inputMap(ctx);
        Validator v = new Validator(input, translator, translator.getLocale(), option,
                new com.neoskin.web.validation.JdbcPresenceVerifier(dataSource));
        addRules(v);
        if (!v.passes()) {
            validationFailed(ctx, v.errors());
            return null;
        }
        Map<String, Object> data = v.validated();
        if (data.get("locale") == null) {
            data.put("locale", "");
        }
        Map<String, Object> audience = new LinkedHashMap<>();
        for (String key : List.of("receiver", "uid", "email")) {
            if (data.containsKey(key)) {
                audience.put(key, data.get(key));
            }
        }
        data.put("audience", audience);
        return data;
    }

    private void addRules(Validator v) {
        v.addRule("title", "required");
        v.addRule("title", "string");
        v.addRule("title", "max:100");
        v.addRule("kind", "required");
        v.addRule("kind", "in:announcement,message,both");
        v.addRule("editor", "required");
        v.addRule("editor", "in:source,blocks");
        v.addRule("source", "nullable");
        v.addRule("source", "string");
        v.addRule("source", "max:100000");
        v.addRule("blocks", "nullable");
        v.addRule("blocks", "array");
        v.addRule("blocks", "max:80");
        v.addRule("blocks.*", "array:type,title,content,tone,expanded");
        v.addRule("blocks.*.type", "required");
        v.addRule("blocks.*.type", "in:text,heading,alert,collapse,divider");
        v.addRule("blocks.*.content", "nullable");
        v.addRule("blocks.*.content", "string");
        v.addRule("blocks.*.content", "max:10000");
        v.addRule("blocks.*.title", "nullable");
        v.addRule("blocks.*.title", "string");
        v.addRule("blocks.*.title", "max:200");
        v.addRule("blocks.*.tone", "nullable");
        v.addRule("blocks.*.tone", "in:info,success,warning,danger,primary,secondary");
        v.addRule("blocks.*.expanded", "nullable");
        v.addRule("blocks.*.expanded", "boolean");
        v.addRule("locale", "nullable");
        v.addRule("locale", "string");
        v.addRule("locale", "max:20");
        v.addRule("receiver", "required");
        v.addRule("receiver", "in:all,normal,uid,email");
        v.addRule("uid", "required_if:receiver,uid");
        v.addRule("uid", "nullable");
        v.addRule("uid", "integer");
        v.addRule("uid", "exists:users,uid");
        v.addRule("email", "required_if:receiver,email");
        v.addRule("email", "nullable");
        v.addRule("email", "email");
        v.addRule("email", "exists:users,email");
        v.addRule("operation", "required");
        v.addRule("operation", "in:draft,publish");
    }

    // ============================================================ helpers

    private Map<String, Object> pageVars(RoutingContext ctx, String scope, String title,
            String locale) throws Exception {
        String urlRoot = "http://" + hostOf(ctx);
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = currentUser(ctx);
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"),
                RouteUrlGenerator.forHost(hostOf(ctx)),
                locale, session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = PageView.variables(option, translator, javaScript, filter,
                user, locale, urlRoot, ctx.request().path(), debug, scope, vc);
        vars.put("pageTitle", title);
        vars.put("errors", new AuthController.ErrorsView(
                com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                        session == null ? null : session.get("errors"))));
        return vars;
    }

    private void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = ViewEngine.render(template, java.util.Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    private void redirect(RoutingContext ctx, String url, String flashKey, String flashValue) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            session.flash(flashKey, flashValue);
        }
        ctx.response().setStatusCode(302)
                .putHeader("Location", url)
                .putHeader("Cache-Control", "no-cache, private")
                .end();
    }

    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            Map<String, Object> old = new LinkedHashMap<>();
            for (String k : List.of("title", "kind", "editor", "source", "locale",
                    "blocks_json", "receiver", "uid", "email", "operation")) {
                Object val = RequestInput.input(ctx, k);
                if (val != null) {
                    old.put(k, val);
                }
            }
            session.flash("_old_input", old);
        }
        validationJson(ctx, errors);
    }

    private void validationJson(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(422)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload, "UTF-8"));
    }

    private static boolean wantsJson(RoutingContext ctx) {
        return ctx.request().getHeader("X-Neoskin-Spa") != null;
    }

    private static Map<String, Object> savedBody(String message, long id, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", status);
        body.put("data", data);
        return body;
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload, "UTF-8"));
    }

    private Map<String, Object> paginator(List<?> items, long total, int page, int perPage,
            String baseUrl) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("items", items);
        p.put("total", total);
        p.put("per_page", perPage);
        p.put("current_page", page);
        long last = perPage <= 0 ? 1 : Math.max(1, (total + perPage - 1) / perPage);
        p.put("last_page", last);
        p.put("previous_page_url", page > 1 ? baseUrl + "?page=" + (page - 1) : null);
        p.put("next_page_url", page < last ? baseUrl + "?page=" + (page + 1) : null);
        p.put("on_first_page", page <= 1);
        p.put("has_more_pages", page < last);
        List<Map<String, Object>> pages = new ArrayList<>();
        long prev = 0;
        for (long n = 1; n <= last; n++) {
            if (prev > 0 && n > prev + 1) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("gap", true);
                pages.add(gap);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", n);
            item.put("url", baseUrl + "?page=" + n);
            item.put("current", n == page);
            pages.add(item);
            prev = n;
        }
        p.put("pages", pages);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputMap(RoutingContext ctx) {
        Object body = ctx.get(RequestInput.INPUT_KEY);
        Map<String, Object> merged = new LinkedHashMap<>();
        Object query = ctx.get(RequestInput.QUERY_KEY);
        if (query instanceof Map<?, ?> q) {
            q.forEach((k, val) -> merged.put(String.valueOf(k), val));
        }
        if (body instanceof Map<?, ?> b) {
            b.forEach((k, val) -> merged.put(String.valueOf(k), val));
        }
        return merged;
    }

    private void putInput(RoutingContext ctx, String key, Object value) {
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof PhpJson.Array array) {
            array.put(key, value);
        }
    }

    private static Object old(RoutingContext ctx, String key, Object fallback) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            return fallback;
        }
        Object raw = session.get("_old_input");
        if (!(raw instanceof Map<?, ?> m)) {
            return fallback;
        }
        Object v = m.get(key);
        return v == null ? fallback : v;
    }

    private static boolean hasOldInput(RoutingContext ctx) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        return session != null && session.has("_old_input");
    }

    private static boolean hasInput(RoutingContext ctx, String key) {
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> m) {
            return m.containsKey(key);
        }
        return false;
    }

    private static boolean isJson(String text) {
        try {
            PhpJson.decode(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int codepointLength(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean isFilled(Object v) {
        if (v == null) return false;
        if (v instanceof String s) return !PhpString.trim(s).isEmpty();
        return true;
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

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    // ============================================================ resolver

    private UserRepository.UserRow currentUser(RoutingContext ctx) throws SQLException {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            return null;
        }
        return new com.neoskin.web.auth.SessionGuard(session, users).user();
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, UserRepository users,
            boolean debug, DataSource dataSource, Communications service,
            CommunicationRepository communications, NotificationRepository notifications,
            CommunicationRenderer renderer, String timezone) {
        CommunicationsController c = new CommunicationsController(option, translator, javaScript,
                filter, users, debug, dataSource, service, communications, notifications,
                renderer, timezone);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\CommunicationsController@index" -> c.index();
            case "App\\Http\\Controllers\\CommunicationsController@store" -> c.store();
            case "App\\Http\\Controllers\\CommunicationsController@update" -> c.update();
            case "App\\Http\\Controllers\\CommunicationsController@withdraw" -> c.withdraw();
            case "App\\Http\\Controllers\\CommunicationsController@importSource" -> c.importSource();
            case "App\\Http\\Controllers\\CommunicationsController@preview" -> c.preview();
            case "App\\Http\\Controllers\\CommunicationsController@inbox" -> c.inbox();
            case "App\\Http\\Controllers\\CommunicationsController@message" -> c.message();
            default -> null;
        };
    }
}
