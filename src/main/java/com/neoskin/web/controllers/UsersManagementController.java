package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpString;
import com.neoskin.support.search.SearchString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\UsersManagementController} 的移植（web 与 api 共用同一批 action）。
 *
 * <p>路由上的 {@code Closure}（控制器构造函数里的中间件）等价逻辑在
 * {@link #guardUser} 里实现：目标用户不存在 404；目标不是自己且权限不低于自己 → 403。</p>
 */
public final class UsersManagementController {

    private static final int PER_PAGE = 10;

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final Cipher cipher;
    private final String salt;
    private final DataSource dataSource;

    public UsersManagementController(Option option, Translator translator, UserRepository users,
            Cipher cipher, String salt, DataSource dataSource) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.cipher = cipher;
        this.salt = salt;
        this.dataSource = dataSource;
    }

    // ============================================================ list

    /** {@code list()}：{@code User::usingSearchString($q)->paginate(10)}。 */
    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                String q = RequestInput.inputString(ctx, "q");
                int page = Math.max(1, toInt(RequestInput.input(ctx, "page")));

                SearchString.Result compiled = SearchString.compile(q, userSearchOptions());
                UserRepository.AdminPage result = users.adminPage(compiled, page, PER_PAGE);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("current_page", page);
                body.put("data", result.rows());
                body.put("from", result.total() == 0 ? null : (long) (page - 1) * PER_PAGE + 1);
                body.put("last_page", lastPage(result.total()));
                body.put("per_page", PER_PAGE);
                body.put("to", result.total() == 0 ? null
                        : Math.min(result.total(), (long) page * PER_PAGE));
                body.put("total", result.total());
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private static int lastPage(long total) {
        return (int) Math.max(Math.ceil(total / (double) PER_PAGE), 1);
    }

    /** {@code User::getSearchStringColumns()} + 默认关键字。 */
    private static SearchString.Options userSearchOptions() {
        return new SearchString.Options()
                .column("uid")
                .searchable("email")
                .searchable("nickname")
                .column("avatar")
                .column("score")
                .column("permission")
                .column("ip")
                .date("last_sign_at")
                .date("register_at")
                .booleanColumn("verified")
                .booleanColumn("is_dark_mode")
                .keyword("sort", "order_by")
                .keyword("fields", "select")
                .keyword("limit", "limit")
                .keyword("from", "offset");
    }

    // ============================================================ 写操作

    /** {@code email()}。 */
    public Handler<RoutingContext> email() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                if (!validate(ctx, Map.of("email", List.of("required", "email",
                        "unique:users,email," + target.uid() + ",uid")))) {
                    return;
                }
                String email = RequestInput.inputString(ctx, "email");
                users.updateEmail(target.uid(), email);
                json(ctx, 200, message(0, trans("admin.users.operations.email.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code verification()}：切换 {@code verified}。 */
    public Handler<RoutingContext> verification() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                users.updateVerified(target.uid(), !target.verified());
                json(ctx, 200, message(0, trans("admin.users.operations.verification.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code nickname()}。 */
    public Handler<RoutingContext> nickname() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                if (!validate(ctx, Map.of("nickname",
                        List.of("required", "string")))) {
                    return;
                }
                String nickname = RequestInput.inputString(ctx, "nickname");
                users.updateNickname(target.uid(), nickname);
                json(ctx, 200, message(0, translator.trans(
                        "admin.users.operations.nickname.success",
                        Map.of("new", nickname), translator.getLocale())));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code password()}：{@code changePassword()}（bcrypt + secure.salt）。 */
    public Handler<RoutingContext> password() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                if (!validate(ctx, Map.of("password",
                        List.of("required", "string", "min:8", "max:16")))) {
                    return;
                }
                String password = RequestInput.inputString(ctx, "password");
                users.changePassword(target.uid(), cipher.hash(password, salt));
                json(ctx, 200, message(0, trans("admin.users.operations.password.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code score()}。 */
    public Handler<RoutingContext> score() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                if (!validate(ctx, Map.of("score", List.of("required", "integer")))) {
                    return;
                }
                int score = toInt(RequestInput.input(ctx, "score"));
                users.updateScore(target.uid(), score);
                json(ctx, 200, message(0, trans("admin.users.operations.score.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code permission()}：超级管理员才能授予 admin；不能改自己。 */
    public Handler<RoutingContext> permission() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                if (!validate(ctx, Map.of("permission",
                        List.of("required", "in:-1,0,1")))) {
                    return;
                }
                UserRepository.UserRow auth = Authenticate.currentUser(ctx);
                int permission = toInt(RequestInput.input(ctx, "permission"));

                if (permission == UserRepository.ADMIN
                        && auth.permission() < UserRepository.SUPER_ADMIN) {
                    noPermission(ctx);
                    return;
                }
                if (target.uid() == auth.uid()) {
                    noPermission(ctx);
                    return;
                }

                users.updatePermission(target.uid(), permission);
                json(ctx, 200, message(0, trans("admin.users.operations.permission")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code delete()}：先删角色再删用户。 */
    public Handler<RoutingContext> delete() {
        return ctx -> {
            try {
                UserRepository.UserRow target = guardUser(ctx);
                if (target == null) {
                    return;
                }
                users.deleteWithPlayers(target.uid());
                json(ctx, 200, message(0, trans("admin.users.operations.delete.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ 守卫

    /**
     * 路由模型绑定 + 构造函数闭包（权限不得高于自己）。
     *
     * <p>返回 {@code null} 表示已经写出错误响应。</p>
     */
    private UserRepository.UserRow guardUser(RoutingContext ctx) throws Exception {
        String raw = ctx.pathParam("user");
        UserRepository.UserRow target = raw == null ? null
                : users.findById(toInt(raw));
        if (target == null) {
            ctx.fail(404); // 隐式绑定失败 → ModelNotFoundException
            return null;
        }

        UserRepository.UserRow auth = Authenticate.currentUser(ctx);
        if (auth != null && target.uid() != auth.uid()
                && target.permission() >= auth.permission()) {
            noPermission(ctx);
            return null;
        }

        return target;
    }

    private void noPermission(RoutingContext ctx) {
        json(ctx, 403, message(1, trans("admin.users.operations.no-permission")));
    }

    // ============================================================ 工具

    private boolean validate(RoutingContext ctx, Map<String, List<String>> rules) {
        Validator validator = new Validator(inputMap(ctx), translator, translator.getLocale(),
                option, new JdbcPresenceVerifier(dataSource));
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            for (String rule : entry.getValue()) {
                validator.addRule(entry.getKey(), rule);
            }
        }
        if (validator.passes()) {
            return true;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(validator.errors()));
        body.put("errors", validator.errors());
        json(ctx, 422, body);

        return false;
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
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

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, Cipher cipher, String salt, DataSource dataSource) {
        UsersManagementController c = new UsersManagementController(option, translator, users,
                cipher, salt, dataSource);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\UsersManagementController@list" -> c.list();
            case "App\\Http\\Controllers\\UsersManagementController@email" -> c.email();
            case "App\\Http\\Controllers\\UsersManagementController@verification" ->
                    c.verification();
            case "App\\Http\\Controllers\\UsersManagementController@nickname" -> c.nickname();
            case "App\\Http\\Controllers\\UsersManagementController@password" -> c.password();
            case "App\\Http\\Controllers\\UsersManagementController@score" -> c.score();
            case "App\\Http\\Controllers\\UsersManagementController@permission" ->
                    c.permission();
            case "App\\Http\\Controllers\\UsersManagementController@delete" -> c.delete();
            default -> null;
        };
    }
}
