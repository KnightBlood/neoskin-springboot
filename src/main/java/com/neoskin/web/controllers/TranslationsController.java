package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.DbTranslationLoader;
import com.neoskin.web.i18n.LanguageLine;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\TranslationsController} 的移植（admin 翻译管理）。
 *
 * <p>四条路由（源 {@code routes/web.php} 的 {@code admin.i18n.*}）：</p>
 * <table border="1">
 *   <tr><th>方法 / 路径</th><th>动作</th><th>响应</th></tr>
 *   <tr><td>GET /admin/i18n/list</td><td>{@code list}</td>
 *       <td>{@code LanguageLine::paginate(10)} 的 JSON</td></tr>
 *   <tr><td>POST /admin/i18n</td><td>{@code create}</td>
 *       <td>302 → {@code /admin/i18n}（session 记 success）</td></tr>
 *   <tr><td>PUT /admin/i18n/{line}</td><td>{@code update}</td>
 *       <td>{@code json(trans('admin.i18n.updated'), 0)}</td></tr>
 *   <tr><td>DELETE /admin/i18n/{line}</td><td>{@code delete}</td>
 *       <td>{@code json(trans('admin.i18n.deleted'), 0)}</td></tr>
 * </table>
 *
 * <p>校验规则（源 {@code validate()}）：</p>
 * <ul>
 *   <li>create：{@code group|key|text} 均 {@code required|string}；</li>
 *   <li>update：{@code text} {@code required|string}。</li>
 * </ul>
 *
 * <p>两处副作用必须保留：</p>
 * <ul>
 *   <li>create / update / delete 之后，若 group 为 {@code front-end}，
 *       调 {@code JavaScript::resetTime($locale)}（让前端语言包下次重新编译）；</li>
 *   <li>create 之后 {@code session()->put('success', true)} 再 302。</li>
 * </ul>
 *
 * <p><b>校验失败的响应形状</b>：源是 Laravel 的 {@code ValidationException}。
 * 对 XHR（{@code expectsJson()}）返回 422 + {@code {message, errors}}；
 * 对表单提交则 302 回上一页 + {@code errors} 闪存。本移植对 JSON 请求
 * （{@code Accept: application/json} 或 {@code X-Requested-With}）返回 422，
 * 其余返回 302（重定向目标沿用 {@code Referer}，缺失时回 {@code /admin/i18n}）。</p>
 */
public final class TranslationsController {

    /** {@code JavaScript::resetTime} 的触发组（源里唯一的特例组名）。 */
    private static final String FRONT_END_GROUP = "front-end";

    private final DataSource dataSource;

    private final Translator translator;

    private final String timezone;

    /** 缓存失效钩子（连通 {@link DbTranslationLoader}）。 */
    private final java.util.function.Consumer<String> flush;

    /** {@code JavaScript} 服务；{@code null} 表示尚未接入（跳过 resetTime）。 */
    private final com.neoskin.web.i18n.JavaScript javaScript;

    public TranslationsController(DataSource dataSource, Translator translator, String timezone,
            java.util.function.Consumer<String> flush,
            com.neoskin.web.i18n.JavaScript javaScript) {
        this.dataSource = dataSource;
        this.translator = translator;
        this.timezone = timezone;
        this.flush = flush == null ? group -> { } : flush;
        this.javaScript = javaScript;
    }

    // ================================================================= 动作

    /** {@code list()}：{@code LanguageLine::paginate(10)}。 */
    public Handler<RoutingContext> list() {
        return ctx -> {
            // query 包里的值经归一化后是字符串（PHP 的 $_GET 同理），
            // 必须按字符串解析；直接 instanceof Number 会恒 false → 永远第 1 页。
            Object rawPage = RequestInput.query(ctx, "page");
            int page = 1;
            if (rawPage != null) {
                try {
                    page = Integer.parseInt(PhpString.cast(rawPage).trim());
                } catch (NumberFormatException ignored) {
                    // Laravel 的 paginate 对非数字页码按第 1 页处理
                    page = 1;
                }
            }
            LanguageLine.Page page_;
            try {
                page_ = LanguageLine.list(dataSource, page);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }

            // PHP 的 json_encode 对「连续 0..n-1 下标」的数组输出 JSON 数组，
            // 所以这里必须用 PhpJson.Array 并顺序 put，不能用 List
            // （否则输出成 {"0":...} 对象，前端 datatable 读不了）。
            PhpJson.Array data = new PhpJson.Array();
            for (LanguageLine line : page_.rows()) {
                PhpJson.Array row = new PhpJson.Array();
                row.put("id", line.getId());
                row.put("group", line.getGroup());
                row.put("key", line.getKey());
                row.put("text", line.getText());
                row.put("created_at", line.getCreatedAt());
                row.put("updated_at", line.getUpdatedAt());
                data.put(data.size(), row);
            }

            // LengthAwarePaginator::toArray()
            PhpJson.Array body = new PhpJson.Array();
            body.put("current_page", page_.currentPage());
            body.put("data", data);
            body.put("from", page_.from());
            body.put("last_page", page_.lastPage());
            body.put("per_page", page_.perPage());
            body.put("to", page_.to());
            body.put("total", page_.total());

            json(ctx, 200, body);
        };
    }

    /** {@code create()}：校验 → 建行 → session success → 302 /admin/i18n。 */
    public Handler<RoutingContext> create() {
        return ctx -> {
            String group = RequestInput.inputString(ctx, "group");
            String key = RequestInput.inputString(ctx, "key");
            String text = RequestInput.inputString(ctx, "text");
            // 注意：Map.of 不接受 null（缺字段时 inputString 返回 null），
            // 必须用可容纳 null 的 LinkedHashMap
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("group", group);
            fields.put("key", key);
            fields.put("text", text);
            Map<String, List<String>> errors = requiredStrings(fields);
            if (!errors.isEmpty()) {
                validationFailed(ctx, errors);
                return;
            }

            String locale = translator.getLocale();
            LanguageLine line = new LanguageLine();
            line.setGroup(group);
            line.setKey(key);
            line.setTranslation(locale, text);
            try {
                line.save(dataSource, timezone, flush);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            resetTimeIfNeeded(group, locale);

            SessionStore session = ctx.get(StartSession.CTX_KEY);
            if (session != null) {
                session.put("success", true);
            }
            redirect(ctx, "/admin/i18n");
        };
    }

    /** {@code update()}：按主键定位 → 改当前 locale 的文本 → json(...,0)。 */
    public Handler<RoutingContext> update() {
        return ctx -> {
            LanguageLine line = bind(ctx);
            if (line == null) {
                return;
            }
            String text = RequestInput.inputString(ctx, "text");
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("text", text);
            Map<String, List<String>> errors = requiredStrings(fields);
            if (!errors.isEmpty()) {
                validationFailed(ctx, errors);
                return;
            }

            String locale = translator.getLocale();
            line.setTranslation(locale, text);
            try {
                line.save(dataSource, timezone, flush);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            resetTimeIfNeeded(line.getGroup(), locale);

            json(ctx, 200, message(0, translator.trans("admin.i18n.updated", Map.of(), null)));
        };
    }

    /** {@code delete()}：删行 → json(...,0)。 */
    public Handler<RoutingContext> delete() {
        return ctx -> {
            LanguageLine line = bind(ctx);
            if (line == null) {
                return;
            }
            String group = line.getGroup();
            try {
                line.delete(dataSource, flush);
            } catch (SQLException e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            // 源用删除前的 $line->group（delete 后属性仍在）
            resetTimeIfNeeded(group, translator.getLocale());

            json(ctx, 200, message(0, translator.trans("admin.i18n.deleted", Map.of(), null)));
        };
    }

    /** 供 HandlerResolver 使用的工厂。 */
    public static HandlerResolver resolver(DataSource dataSource, Translator translator,
            String timezone, java.util.function.Consumer<String> flush,
            com.neoskin.web.i18n.JavaScript javaScript) {
        TranslationsController c = new TranslationsController(
                dataSource, translator, timezone, flush, javaScript);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\TranslationsController@list" -> c.list();
            case "App\\Http\\Controllers\\TranslationsController@create" -> c.create();
            case "App\\Http\\Controllers\\TranslationsController@update" -> c.update();
            case "App\\Http\\Controllers\\TranslationsController@delete" -> c.delete();
            default -> null;
        };
    }

    // ================================================================= 内部

    /**
     * 路由模型绑定：{@code {line}} → {@code LanguageLine::find($id)}。
     * 找不到 → 404（Laravel 的 implicit binding 行为）。
     * 路径参数是路径段（非 query），Vert.x 用 {@code pathParam}。
     */
    private LanguageLine bind(RoutingContext ctx) {
        String raw = ctx.pathParam("line");
        int id;
        try {
            id = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(404).end();
            return null;
        }
        try {
            LanguageLine line = LanguageLine.find(dataSource, id);
            if (line == null) {
                ctx.response().setStatusCode(404).end();
                return null;
            }
            return line;
        } catch (SQLException e) {
            ctx.response().setStatusCode(500).end();
            return null;
        }
    }

    /** group 为 {@code front-end} 时让前端语言包下次重编译。 */
    private void resetTimeIfNeeded(String group, String locale) {
        if (javaScript != null && FRONT_END_GROUP.equals(group)) {
            javaScript.resetTime(locale);
        }
    }

    /** {@code required|string}：缺失 / 空串 / 非字符串都算不通过。 */
    private static Map<String, List<String>> requiredStrings(Map<String, String> fields) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String value = e.getValue();
            if (value == null || value.isEmpty()) {
                errors.put(e.getKey(), List.of(
                        "The " + e.getKey() + " field is required."));
            }
        }
        return errors;
    }

    /** {@code ValidationException} 的响应：JSON 请求 422，表单 302。 */
    private static void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        if (expectsJson(ctx)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "The given data was invalid.");
            body.put("errors", errors);
            json(ctx, 422, body);
            return;
        }
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            session.flash("errors", errors);
        }
        redirect(ctx, refererOr(ctx, "/admin/i18n"));
    }

    /** {@code $request->expectsJson()}：Accept 含 json 或 XHR 头。 */
    private static boolean expectsJson(RoutingContext ctx) {
        String accept = ctx.request().getHeader("Accept");
        boolean jsonAccept = accept != null && accept.contains("application/json");
        String xhr = ctx.request().getHeader("X-Requested-With");
        return jsonAccept || "XMLHttpRequest".equalsIgnoreCase(xhr);
    }

    private static String refererOr(RoutingContext ctx, String fallback) {
        String referer = ctx.request().getHeader("Referer");
        return referer == null || referer.isEmpty() ? fallback : referer;
    }

    /** {@code response()->json($data)}。 */
    private static void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, PhpJson.JSON_UNESCAPED_UNICODE
                | PhpJson.JSON_UNESCAPED_SLASHES);
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code json($message, $code)} 的两参数形态。 */
    private static Map<String, Object> message(int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", msg);
        return body;
    }

    /** {@code redirect($url)}：302 + Location。 */
    private static void redirect(RoutingContext ctx, String url) {
        ctx.response()
                .setStatusCode(302)
                .putHeader("Location", url)
                .end();
    }
}
