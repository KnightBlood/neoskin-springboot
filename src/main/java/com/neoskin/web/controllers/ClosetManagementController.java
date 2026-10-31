package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.ClosetRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\ClosetManagementController} 的移植。
 *
 * <p>管理员查看/添加/移除指定用户的衣柜。响应体分别对应源里的
 * {@code $user->closet}（含 pivot）与 {@code compact('user','texture')}。</p>
 */
public final class ClosetManagementController {

    private final Translator translator;
    private final UserRepository users;
    private final TextureRepository textures;
    private final ClosetRepository closet;

    public ClosetManagementController(Option option, Translator translator,
            UserRepository users, TextureRepository textures, ClosetRepository closet) {
        this.translator = translator;
        this.users = users;
        this.textures = textures;
        this.closet = closet;
    }

    /** {@code list(User $user)}：{@code $user->closet}（模型 + pivot）。 */
    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                UserRepository.UserRow user = targetUser(ctx);
                if (user == null) {
                    return;
                }
                List<Object> items = new ArrayList<>();
                for (ClosetRepository.Item item : closet.all(user.uid())) {
                    Map<String, Object> texture = textureJson(item.texture());
                    Map<String, Object> pivot = new LinkedHashMap<>();
                    pivot.put("user_uid", item.userUid());
                    pivot.put("texture_tid", item.textureTid());
                    pivot.put("item_name", item.itemName());
                    texture.put("pivot", pivot);
                    items.add(texture);
                }
                json(ctx, 200, items);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code add(Request, Dispatcher, User)}。 */
    public Handler<RoutingContext> add() {
        return ctx -> {
            try {
                UserRepository.UserRow user = targetUser(ctx);
                if (user == null) {
                    return;
                }
                long tid = toLong(RequestInput.input(ctx, "tid"));
                TextureRepository.Row texture = textures.findByTid(tid);
                if (texture == null) {
                    json(ctx, 200, message(1, trans("user.closet.add.not-found")));
                    return;
                }
                if (closet.exists(user.uid(), tid)) {
                    json(ctx, 200, message(1, trans("user.closet.add.repeated")));
                    return;
                }

                closet.attach(user.uid(), texture.tid(), texture.name());

                json(ctx, 200, message(0, "", data(user, texture)));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code remove(Request, Dispatcher, User)}。 */
    public Handler<RoutingContext> remove() {
        return ctx -> {
            try {
                UserRepository.UserRow user = targetUser(ctx);
                if (user == null) {
                    return;
                }
                long tid = toLong(RequestInput.input(ctx, "tid"));
                if (closet.find(user.uid(), tid) == null) {
                    json(ctx, 200, message(1, trans("user.closet.remove.non-existent")));
                    return;
                }

                closet.detach(user.uid(), tid);
                TextureRepository.Row texture = textures.findByTid(tid);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user", users.findJsonById(user.uid()));
                data.put("texture", texture == null ? null : textureJson(texture));
                json(ctx, 200, message(0, "", data));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ helpers

    /** {@code {user}} 绑定：按 uid 查，找不到 → 404。 */
    private UserRepository.UserRow targetUser(RoutingContext ctx) throws Exception {
        String raw = ctx.pathParam("user");
        UserRepository.UserRow user = raw == null ? null : users.findById((int) toLong(raw));
        if (user == null) {
            ctx.fail(404);
        }

        return user;
    }

    private Map<String, Object> data(UserRepository.UserRow user, TextureRepository.Row texture)
            throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", users.findJsonById(user.uid()));
        data.put("texture", texture == null ? null : textureJson(texture));

        return data;
    }

    /** {@code Texture::toArray()}（当前模型没有 $appends，故不含 {@code model}）。 */
    private static Map<String, Object> textureJson(TextureRepository.Row t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tid", t.tid());
        m.put("name", t.name());
        m.put("type", t.type());
        m.put("hash", t.hash());
        m.put("size", t.size());
        m.put("uploader", t.uploader());
        m.put("public", t.isPublic());
        m.put("upload_at", t.uploadAt());
        m.put("likes", t.likes());

        return m;
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

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, TextureRepository textures, ClosetRepository closet) {
        ClosetManagementController c = new ClosetManagementController(option, translator, users,
                textures, closet);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\ClosetManagementController@list" -> c.list();
            case "App\\Http\\Controllers\\ClosetManagementController@add" -> c.add();
            case "App\\Http\\Controllers\\ClosetManagementController@remove" -> c.remove();
            default -> null;
        };
    }
}
