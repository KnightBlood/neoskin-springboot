package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Communications;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.Validator;
import com.neoskin.web.error.ErrorHandler;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\NotificationsController} 的移植。
 *
 * <p>{@code send} 复用 {@link Communications} 的数据模型：先建一条
 * {@code communications}（kind=message / status=published / editor=source），
 * 再按收件人集合逐个写入 {@code notifications}（{@code SiteMessage} 的 database 通道）。</p>
 */
public final class NotificationsController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final CommunicationRepository communications;
    private final NotificationRepository notifications;
    private final Communications service;
    private final DataSource dataSource;
    private final String timezone;

    public NotificationsController(Option option, Translator translator, UserRepository users,
            CommunicationRepository communications, NotificationRepository notifications,
            Communications service, DataSource dataSource, String timezone) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.communications = communications;
        this.notifications = notifications;
        this.service = service;
        this.dataSource = dataSource;
        this.timezone = timezone;
    }

    // ============================================================ send

    /** {@code POST admin/notifications/send} / {@code POST api/admin/notifications}。 */
    public Handler<RoutingContext> send() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }

                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        option, new JdbcPresenceVerifier(dataSource));
                validator.addRule("receiver", "required");
                validator.addRule("receiver", "in:all,normal,uid,email");
                validator.addRule("uid", "required_if:receiver,uid");
                validator.addRule("uid", "nullable");
                validator.addRule("uid", "integer");
                validator.addRule("uid", "exists:users");
                validator.addRule("email", "required_if:receiver,email");
                validator.addRule("email", "nullable");
                validator.addRule("email", "email");
                validator.addRule("email", "exists:users");
                validator.addRule("title", "required");
                validator.addRule("title", "max:20");
                validator.addRule("content", "string");
                validator.addRule("content", "nullable");
                if (!validator.passes()) {
                    validationFailed(ctx, validator.errors());
                    return;
                }

                String receiver = String.valueOf(input.get("receiver"));
                String title = String.valueOf(input.get("title"));
                String content = input.get("content") == null ? ""
                        : String.valueOf(input.get("content"));
                Integer uid = input.get("uid") == null ? null
                        : Integer.valueOf(String.valueOf(input.get("uid")));
                String email = input.get("email") == null ? null
                        : String.valueOf(input.get("email"));
                String now = PhpDate.now(timezone);

                Map<String, Object> audience = new LinkedHashMap<>();
                audience.put("receiver", receiver);
                if (input.containsKey("uid")) {
                    audience.put("uid", input.get("uid"));
                }
                if (input.containsKey("email")) {
                    audience.put("email", input.get("email"));
                }

                CommunicationRepository.Row message = new CommunicationRepository.Row();
                message.authorId = user.uid();
                message.title = title;
                message.kind = CommunicationRepository.KIND_MESSAGE;
                message.status = CommunicationRepository.STATUS_PUBLISHED;
                message.locale = "";
                message.editor = "source";
                message.source = content;
                message.audience = audience;
                message.publishedAt = now;
                message.deliveryToken = randomToken();
                message.createdAt = now;
                message.updatedAt = now;
                message = communications.save(message);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("communication_id", message.id);
                payload.put("delivery_token", message.deliveryToken);
                payload.put("format", "html");
                payload.put("title", title);
                payload.put("content", content);
                String dataJson = PhpJson.encode(payload, 0);

                List<Integer> receivers = users.idsForAudience(receiver, uid, email);
                for (Integer notifiable : receivers) {
                    notifications.insert(UUID.randomUUID().toString(), notifiable, dataJson,
                            now);
                }

                SessionStore session = ctx.get(StartSession.CTX_KEY);
                if (session != null) {
                    session.put("sentResult",
                            trans("admin.notifications.send.success"));
                }
                ctx.response().setStatusCode(302)
                        .putHeader("Location", "http://" + hostOf(ctx) + "/admin")
                        .putHeader("Cache-Control", "no-cache, private")
                        .end();
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    // ============================================================ all

    /** {@code GET api/user/notifications}：全部未读（id + title）。 */
    public Handler<RoutingContext> all() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                List<Object> items = new ArrayList<>();
                for (NotificationRepository.Row row : notifications.unread(user.uid(), 1000)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.id);
                    item.put("title", service.title(row));
                    items.add(item);
                }
                json(ctx, 200, items);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ read

    /** {@code POST user/notifications/{id}} / {@code POST api/user/notifications/{id}}。 */
    public Handler<RoutingContext> read() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String id = ctx.pathParam("id");
                NotificationRepository.Row row = notifications.findByNotifiableAndId(
                        user.uid(), id);
                if (row == null || row.readAt != null || !service.visible(row)) {
                    ctx.fail(404);
                    return;
                }

                notifications.markAsRead(id, PhpDate.now(timezone));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", service.title(row));
                body.put("content", service.content(row));
                body.put("time", row.createdAt);
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
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

    /** 校验失败：HTML 表单回 302 + errors；JSON 客户端回 422。 */
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
            previous = "http://" + hostOf(ctx) + "/admin";
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

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, CommunicationRepository communications,
            NotificationRepository notifications, Communications service, DataSource dataSource,
            String timezone) {
        NotificationsController c = new NotificationsController(option, translator, users,
                communications, notifications, service, dataSource, timezone);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\NotificationsController@send" -> c.send();
            case "App\\Http\\Controllers\\NotificationsController@all" -> c.all();
            case "App\\Http\\Controllers\\NotificationsController@read" -> c.read();
            default -> null;
        };
    }
}
