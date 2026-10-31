package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.config.AppPaths;
import com.neoskin.db.ReportRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.support.search.SearchString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.error.ErrorHandler;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.pagination.LengthAwarePaginator;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.Validator;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\ReportController} 的移植。
 *
 * <p>四个动作：{@code submit}（举报材质）、{@code track}（我的举报页）、
 * {@code manage}（管理端列表，走搜索串）、{@code review}（拒绝/删除/封禁）。</p>
 */
public final class ReportController {

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final TextureRepository textures;
    private final ReportRepository reports;
    private final AppPaths paths;
    private final DataSource dataSource;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final boolean debug;
    private final String timezone;

    public ReportController(Option option, Translator translator, UserRepository users,
            TextureRepository textures, ReportRepository reports, AppPaths paths,
            DataSource dataSource, JavaScript javaScript, BlessingFilter filter, boolean debug,
            String timezone) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.textures = textures;
        this.reports = reports;
        this.paths = paths;
        this.dataSource = dataSource;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
        this.timezone = timezone;
    }

    // ============================================================ submit

    /** {@code POST skinlib/report}。 */
    public Handler<RoutingContext> submit() {
        return ctx -> {
            try {
                UserRepository.UserRow reporter = Authenticate.currentUser(ctx);
                if (reporter == null) {
                    ctx.fail(401);
                    return;
                }
                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        option, new JdbcPresenceVerifier(dataSource));
                validator.addRule("tid", "required");
                validator.addRule("tid", "exists:textures");
                validator.addRule("reason", "required");
                if (!validator.passes()) {
                    validationFailed(ctx, validator.errors());
                    return;
                }

                int tid = toInt(input.get("tid"));
                String reason = String.valueOf(input.get("reason"));

                if (reports.countByReporterAndTid(reporter.uid(), tid) > 0) {
                    json(ctx, 200, message(1, trans("skinlib.report.duplicate")));
                    return;
                }

                int scoreChange = toInt(option.get("reporter_score_modification", 0));
                if (scoreChange < 0 && reporter.score() < -scoreChange) {
                    json(ctx, 200, message(1, trans("skinlib.upload.lack-score")));
                    return;
                }
                users.updateScore(reporter.uid(), reporter.score() + scoreChange);

                TextureRepository.Row texture = textures.findByTid(tid);
                long id = reports.insert(tid, texture == null ? 0 : texture.uploader(),
                        reporter.uid(), reason, ReportRepository.PENDING,
                        PhpDate.now(timezone));
                if (id <= 0) {
                    ctx.fail(500);
                    return;
                }

                json(ctx, 200, message(0, trans("skinlib.report.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ track

    /** {@code GET user/reports}：我的举报（分页 10）。 */
    public Handler<RoutingContext> track() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String locale = translator.getLocale();
                int page = Math.max(1, toInt(RequestInput.query(ctx, "page")));
                ReportRepository.Page result = reports.pageByReporter(user.uid(), page, 10);

                List<String> labels = statusLabels(locale);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ReportRepository.Row row : result.rows()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tid", row.tid());
                    item.put("reason", row.reason());
                    item.put("status", row.status());
                    item.put("status_label", row.status() >= 0 && row.status() < labels.size()
                            ? labels.get(row.status()) : "");
                    item.put("report_at", row.reportAt());
                    item.put("show_url", RouteUrlGenerator.forHost(hostOf(ctx))
                            .route("skinlib.show", Map.of("texture", row.tid()), false));
                    rows.add(item);
                }

                Map<String, Object> pagerQuery = new LinkedHashMap<>();
                ctx.request().params().forEach(entry -> {
                    if (!"page".equals(entry.getKey())) {
                        pagerQuery.put(entry.getKey(), entry.getValue());
                    }
                });
                String host = hostOf(ctx);
                String urlRoot = "http://" + host;
                LengthAwarePaginator<ReportRepository.Row> paginator = LengthAwarePaginator.of(
                        result.rows(), result.total(), 10, page, pagerQuery,
                        urlRoot + "/user/reports");

                SessionStore session = ctx.get(StartSession.CTX_KEY);
                ViewContext vc = new ViewContext(translator, option,
                        new PhpUrlGenerator(urlRoot, "http"),
                        RouteUrlGenerator.forHost(host), locale,
                        session == null ? "" : String.valueOf(session.token()), user, session);
                Map<String, Object> vars = PageView.variables(option, translator, javaScript,
                        filter, user, locale, urlRoot, ctx.request().path(), debug, "user", vc);
                vars.put("pageTitle", translator.trans("general.my-reports", Map.of(), locale));
                vars.put("reports", rows);
                vars.put("reports_links", paginator.linksHtml());

                vars.put("errors", new AuthController.ErrorsView(
                        com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                                session == null ? null : session.get("errors"))));
                String html = ViewEngine.render("user/report", java.util.Locale.ROOT, vars);
                ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "text/html; charset=utf-8")
                        .end(Buffer.buffer(html, "UTF-8"));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code trans('front-end.report.status')} 是 YAML 列表 → 取出来按下标渲染。 */
    private List<String> statusLabels(String locale) {
        Object raw = translator.get("front-end.report.status", Map.of(), locale);
        List<String> labels = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                labels.add(String.valueOf(item));
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (int i = 0; i < 3; i++) {
                labels.add(String.valueOf(map.get(String.valueOf(i))));
            }
        }
        while (labels.size() < 3) {
            labels.add("");
        }

        return labels;
    }

    // ============================================================ manage

    /** {@code GET admin/reports/list} / {@code GET api/admin/reports}。 */
    public Handler<RoutingContext> manage() {
        return ctx -> {
            try {
                String q = RequestInput.inputString(ctx, "q");
                int page = Math.max(1, toInt(RequestInput.input(ctx, "page")));
                SearchString.Result compiled = SearchString.compile(q, reportSearchOptions());
                ReportRepository.AdminPage result = reports.adminPage(compiled, page, 9);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("current_page", page);
                body.put("data", result.rows());
                body.put("from", result.total() == 0 ? null : (long) (page - 1) * 9 + 1);
                body.put("last_page", (int) Math.max(Math.ceil(result.total() / 9.0), 1));
                body.put("per_page", 9);
                body.put("to", result.total() == 0 ? null
                        : Math.min(result.total(), (long) page * 9));
                body.put("total", result.total());
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code Report::$searchStringColumns}。 */
    private static SearchString.Options reportSearchOptions() {
        return new SearchString.Options()
                .column("id")
                .column("tid")
                .column("uploader")
                .column("reporter")
                .column("reason")
                .column("status")
                .date("report_at")
                .keyword("sort", "order_by")
                .keyword("fields", "select")
                .keyword("limit", "limit")
                .keyword("from", "offset");
    }

    // ============================================================ review

    /** {@code PUT admin/reports/{report}} / {@code PUT api/admin/reports/{report}}。 */
    public Handler<RoutingContext> review() {
        return ctx -> {
            try {
                UserRepository.UserRow auth = Authenticate.currentUser(ctx);
                String raw = ctx.pathParam("report");
                ReportRepository.Row report = raw == null ? null
                        : reports.findById(toLong(raw));
                if (report == null) {
                    ctx.fail(404);
                    return;
                }

                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        option, new JdbcPresenceVerifier(dataSource));
                validator.addRule("action", "required");
                validator.addRule("action", "in:delete,ban,reject");
                if (!validator.passes()) {
                    validationFailed(ctx, validator.errors());
                    return;
                }

                String action = String.valueOf(input.get("action"));

                if ("reject".equals(action)) {
                    if (report.status() == ReportRepository.PENDING) {
                        int score = toInt(option.get("reporter_score_modification", 0));
                        UserRepository.UserRow informer = users.findById(report.reporter());
                        if (informer != null && score > 0) {
                            users.updateScore(informer.uid(), informer.score() - score);
                        }
                    }
                    reports.updateStatus(report.id(), ReportRepository.REJECTED);
                    json(ctx, 200, message(0, trans("general.op-success"),
                            Map.of("status", ReportRepository.REJECTED)));
                    return;
                }

                if ("delete".equals(action)) {
                    TextureRepository.Row texture = textures.findByTid(report.tid());
                    if (texture != null) {
                        try {
                            com.neoskin.storage.TextureStorageRuntime.get(paths.textures())
                            .delete(texture.hash());
                        } catch (Exception ignored) {
                            // Storage::delete 对不存在的文件同样不报错
                        }
                        textures.deleteByTid(texture.tid());
                        // texture.deleted → UpdateScoreForDeletedTexture + ResetPlayers + CleanUpCloset
                        com.neoskin.service.TextureSideEffects.onDeleted(dataSource, option, texture);
                    } else {
                        // 材质已被上传者删除：退还积分，但不给举报者奖励
                        returnScore(report);
                        reports.updateStatus(report.id(), ReportRepository.RESOLVED);
                        json(ctx, 200, message(0, trans("general.texture-deleted"),
                                Map.of("status", ReportRepository.RESOLVED)));
                        return;
                    }
                } else if ("ban".equals(action)) {
                    UserRepository.UserRow uploader = users.findById(report.uploader());
                    if (uploader == null) {
                        json(ctx, 200, message(1,
                                trans("admin.users.operations.non-existent")));
                        return;
                    }
                    if (auth == null || auth.permission() <= uploader.permission()) {
                        json(ctx, 200, message(1,
                                trans("admin.users.operations.no-permission")));
                        return;
                    }
                    users.updatePermission(uploader.uid(), UserRepository.BANNED);
                }

                returnScore(report);
                giveAward(report);
                reports.updateStatus(report.id(), ReportRepository.RESOLVED);
                json(ctx, 200, message(0, trans("general.op-success"),
                        Map.of("status", ReportRepository.RESOLVED)));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code ReportController::returnScore}。 */
    private void returnScore(ReportRepository.Row report) throws Exception {
        int score = toInt(option.get("reporter_score_modification", 0));
        if (report.status() == ReportRepository.PENDING && score < 0) {
            UserRepository.UserRow informer = users.findById(report.reporter());
            if (informer != null) {
                users.updateScore(informer.uid(), informer.score() - score);
            }
        }
    }

    /** {@code ReportController::giveAward}。 */
    private void giveAward(ReportRepository.Row report) throws Exception {
        if (report.status() == ReportRepository.PENDING) {
            UserRepository.UserRow informer = users.findById(report.reporter());
            if (informer != null) {
                users.updateScore(informer.uid(), informer.score()
                        + toInt(option.get("reporter_reward_score", 0)));
            }
        }
    }

    // ============================================================ helpers

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        if (ErrorHandler.expectsJson(ctx)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", Validator.summarize(errors));
            body.put("errors", errors);
            json(ctx, 422, body);
            return;
        }
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            session.flash("errors", AuthController.toViewErrorBag(errors));
            session.flash("_old_input", inputMap(ctx));
        }
        String previous = ctx.request().getHeader("Referer");
        if (previous == null || previous.isBlank()) {
            previous = "http://" + hostOf(ctx) + "/skinlib";
        }
        ctx.response().setStatusCode(302)
                .putHeader("Location", previous)
                .putHeader("Cache-Control", "no-cache, private")
                .end();
    }

    private String trans(String key) {
        return translator.trans(key, Map.of(), translator.getLocale());
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> message(int code, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", text);

        return body;
    }

    private static Map<String, Object> message(int code, String text,
            Map<String, Object> data) {
        Map<String, Object> body = message(code, text);
        body.put("data", data);

        return body;
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, TextureRepository textures, ReportRepository reports,
            AppPaths paths, DataSource dataSource, JavaScript javaScript, BlessingFilter filter,
            boolean debug, String timezone) {
        ReportController c = new ReportController(option, translator, users, textures, reports,
                paths, dataSource, javaScript, filter, debug, timezone);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\ReportController@submit" -> c.submit();
            case "App\\Http\\Controllers\\ReportController@track" -> c.track();
            case "App\\Http\\Controllers\\ReportController@manage" -> c.manage();
            case "App\\Http\\Controllers\\ReportController@review" -> c.review();
            default -> null;
        };
    }
}
