package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.support.search.SearchString;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.PlayerNameRule;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\PlayersManagementController} 的移植（web 与 api 共用）。
 *
 * <p>构造函数里的权限闭包（按角色所属用户的权限比较）在 {@link #guardPlayer} 中还原。</p>
 */
public final class PlayersManagementController {

    private static final int PER_PAGE = 10;

    private final Option option;
    private final Translator translator;
    private final PlayerRepository players;
    private final UserRepository users;
    private final TextureRepository textures;
    private final DataSource dataSource;
    private final String timezone;

    public PlayersManagementController(Option option, Translator translator,
            PlayerRepository players, UserRepository users, TextureRepository textures,
            DataSource dataSource, String timezone) {
        this.option = option;
        this.translator = translator;
        this.players = players;
        this.users = users;
        this.textures = textures;
        this.dataSource = dataSource;
        this.timezone = timezone;
    }

    // ============================================================ list

    /** {@code list()}：{@code Player::usingSearchString($q)->paginate(10)}。 */
    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                String q = string(RequestInput.query(ctx, "q"));
                int page = Math.max(1, toInt(RequestInput.query(ctx, "page")));

                SearchString.Result compiled = SearchString.compile(q, playerSearchOptions());
                PlayerRepository.AdminPage result = players.adminPage(compiled, page, PER_PAGE);

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

    /** {@code Player::getSearchStringColumns()} + 默认关键字。 */
    private static SearchString.Options playerSearchOptions() {
        return new SearchString.Options()
                .column("pid")
                .column("uid")
                .columnRegex("tid_skin", "/^(?:tid_)?skin$/")
                .columnRegex("tid_cape", "/^(?:tid_)?cape$/")
                .searchable("name")
                .date("last_modified")
                .keyword("sort", "order_by")
                .keyword("fields", "select")
                .keyword("limit", "limit")
                .keyword("from", "offset");
    }

    // ============================================================ 写操作

    /** {@code name()}。 */
    public Handler<RoutingContext> name() {
        return ctx -> {
            try {
                PlayerRepository.PlayerRow player = guardPlayer(ctx);
                if (player == null) {
                    return;
                }

                Validator validator = validator(ctx);
                validator.addRule("player_name", "required");
                validator.addRule("player_name",
                        new PlayerNameRule(option, translator, translator.getLocale()));
                validator.addRule("player_name",
                        "min:" + option.get("player_name_length_min"));
                validator.addRule("player_name",
                        "max:" + option.get("player_name_length_max"));
                validator.addRule("player_name", "unique:players,name");
                if (!validator.passes()) {
                    validationFailed(ctx, validator.errors());
                    return;
                }

                String newName = String.valueOf(validator.validated().get("player_name"));
                players.updateName(player.pid(), newName, PhpDate.now(timezone));
                json(ctx, 200, message(0, translator.trans("admin.players.name.success",
                        Map.of("player", newName), translator.getLocale())));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code owner()}。 */
    public Handler<RoutingContext> owner() {
        return ctx -> {
            try {
                PlayerRepository.PlayerRow player = guardPlayer(ctx);
                if (player == null) {
                    return;
                }
                if (!validate(ctx, Map.of("uid", List.of("required", "integer")))) {
                    return;
                }

                int uid = toInt(RequestInput.input(ctx, "uid"));
                UserRepository.UserRow user = users.findById(uid);
                if (user == null) {
                    json(ctx, 200, message(1, trans("admin.users.operations.non-existent")));
                    return;
                }

                players.updateOwner(player.pid(), uid, PhpDate.now(timezone));
                json(ctx, 200, message(0, translator.trans("admin.players.owner.success",
                        Map.of("player", player.name(), "user", String.valueOf(user.nickname())),
                        translator.getLocale())));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code texture()}：{@code tid} + {@code type=skin|cape}。 */
    public Handler<RoutingContext> texture() {
        return ctx -> {
            try {
                PlayerRepository.PlayerRow player = guardPlayer(ctx);
                if (player == null) {
                    return;
                }
                if (!validate(ctx, Map.of(
                        "tid", List.of("required", "integer"),
                        "type", List.of("required", "in:skin,cape")))) {
                    return;
                }

                int tid = toInt(RequestInput.input(ctx, "tid"));
                String type = String.valueOf(RequestInput.input(ctx, "type"));

                if (tid != 0 && textures.findByTid(tid) == null) {
                    json(ctx, 200, message(1, translator.trans(
                            "admin.players.textures.non-existent",
                            Map.of("tid", tid), translator.getLocale())));
                    return;
                }

                int tidSkin = player.tidSkin();
                int tidCape = player.tidCape();
                if ("skin".equals(type)) {
                    tidSkin = tid;
                } else {
                    tidCape = tid;
                }
                players.updateTextures(player.pid(), tidSkin, tidCape, PhpDate.now(timezone));

                json(ctx, 200, message(0, translator.trans("admin.players.textures.success",
                        Map.of("player", player.name()), translator.getLocale())));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code delete()}。 */
    public Handler<RoutingContext> delete() {
        return ctx -> {
            try {
                PlayerRepository.PlayerRow player = guardPlayer(ctx);
                if (player == null) {
                    return;
                }
                players.delete(player.pid());
                json(ctx, 200, message(0, trans("admin.players.delete.success")));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ 守卫

    /**
     * 隐式绑定 + 构造函数闭包：角色所属用户不是自己且权限不低于自己 → 403。
     *
     * @return {@code null} 表示已经写出错误响应
     */
    private PlayerRepository.PlayerRow guardPlayer(RoutingContext ctx) throws Exception {
        String raw = ctx.pathParam("player");
        PlayerRepository.PlayerRow player = raw == null ? null
                : players.findByPid(toInt(raw));
        if (player == null) {
            ctx.fail(404);
            return null;
        }

        UserRepository.UserRow owner = users.findById(player.uid());
        if (owner == null) {
            // 源侧 $player->user 为空时会出现 "property on null" → 500
            ctx.fail(new IllegalStateException("Attempt to read property 'uid' on null"));
            return null;
        }

        UserRepository.UserRow auth = Authenticate.currentUser(ctx);
        if (auth != null && owner.uid() != auth.uid()
                && owner.permission() >= auth.permission()) {
            json(ctx, 403, message(1, trans("admin.players.no-permission")));
            return null;
        }

        return player;
    }

    // ============================================================ 工具

    private boolean validate(RoutingContext ctx, Map<String, List<String>> rules) {
        Validator validator = validator(ctx);
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            for (String rule : entry.getValue()) {
                validator.addRule(entry.getKey(), rule);
            }
        }
        if (validator.passes()) {
            return true;
        }
        validationFailed(ctx, validator.errors());

        return false;
    }

    private Validator validator(RoutingContext ctx) {
        return new Validator(inputMap(ctx), translator, translator.getLocale(), option,
                new JdbcPresenceVerifier(dataSource));
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private void validationFailed(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
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

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
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
            PlayerRepository players, UserRepository users, TextureRepository textures,
            DataSource dataSource, String timezone) {
        PlayersManagementController c = new PlayersManagementController(option, translator,
                players, users, textures, dataSource, timezone);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\PlayersManagementController@list" -> c.list();
            case "App\\Http\\Controllers\\PlayersManagementController@name" -> c.name();
            case "App\\Http\\Controllers\\PlayersManagementController@owner" -> c.owner();
            case "App\\Http\\Controllers\\PlayersManagementController@texture" -> c.texture();
            case "App\\Http\\Controllers\\PlayersManagementController@delete" -> c.delete();
            default -> null;
        };
    }
}
