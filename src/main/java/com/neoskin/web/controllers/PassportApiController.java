package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.UserRepository;
import com.neoskin.oauth.OAuthService;
import com.neoskin.oauth.OAuthStore;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * Passport 的 JSON API：OAuth 客户端、访问令牌、个人访问令牌、作用域。
 */
public final class PassportApiController {

    private final OAuthService oauth;
    private final Translator translator;
    private final Cipher cipher;

    public PassportApiController(OAuthService oauth, Translator translator, Cipher cipher) {
        this.oauth = oauth;
        this.translator = translator;
        this.cipher = cipher;
    }

    // ============================================================ clients

    public Handler<RoutingContext> clientsForUser() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                List<Object> list = new ArrayList<>();
                for (OAuthStore.Client client : oauth.store().clientsForUser(user.uid())) {
                    list.add(OAuthStore.clientJson(client));
                }
                json(ctx, 200, list);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> storeClient() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                Map<String, Object> input = inputMap(ctx);
                Map<String, List<String>> errors = validateClient(input);
                if (!errors.isEmpty()) {
                    validationError(ctx, errors);
                    return;
                }
                String redirect = string(input.get("redirect"));
                boolean confidential = !(input.get("confidential") instanceof Boolean b) || b;
                String plainSecret = confidential ? randomAlnum(40) : null;
                long id = oauth.store().createClient(user == null ? null : user.uid(),
                        string(input.get("name")),
                        plainSecret == null ? null : cipher.hash(plainSecret, ""), null,
                        String.join(",", splitRedirects(redirect)), false, false);
                Map<String, Object> body = OAuthStore.clientJson(oauth.store().findClient(id));
                if (confidential) {
                    body.put("plain_secret", plainSecret);
                }
                deleteSecret(body);
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> updateClient() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                long id = toLong(ctx.pathParam("client_id"));
                OAuthStore.Client client = oauth.store().findClientForUser(id, user.uid());
                if (client == null) {
                    ctx.response().setStatusCode(404).end();
                    return;
                }
                Map<String, Object> input = inputMap(ctx);
                Map<String, List<String>> errors = validateClient(input);
                if (!errors.isEmpty()) {
                    validationError(ctx, errors);
                    return;
                }
                oauth.store().updateClient(id, string(input.get("name")),
                        String.join(",", splitRedirects(string(input.get("redirect")))));
                Map<String, Object> body = OAuthStore.clientJson(oauth.store().findClient(id));
                deleteSecret(body);
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> destroyClient() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                long id = toLong(ctx.pathParam("client_id"));
                OAuthStore.Client client = oauth.store().findClientForUser(id, user.uid());
                if (client == null) {
                    ctx.response().setStatusCode(404).end();
                    return;
                }
                oauth.store().deleteClient(id);
                ctx.response().setStatusCode(204).end();
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private Map<String, List<String>> validateClient(Map<String, Object> input) {
        Validator validator = new Validator(input, translator, translator.getLocale(),
                null, null);
        validator.addRule("name", "required");
        validator.addRule("name", "max:255");
        validator.addRule("redirect", "required");
        if (validator.passes()) {
            return Map.of();
        }

        return validator.errors();
    }

    private List<String> splitRedirects(String redirect) {
        List<String> out = new ArrayList<>();
        for (String part : redirect.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }

        return out;
    }

    // ============================================================ tokens

    public Handler<RoutingContext> tokensForUser() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                List<Object> list = new ArrayList<>();
                for (OAuthStore.Token token : oauth.store().activeTokensForUser(user.uid())) {
                    OAuthStore.Client client = oauth.store().findActiveClient(token.clientId());
                    if (client == null || client.userId() == null) {
                        continue; // first-party clients 不出现在「授权应用」列表
                    }
                    list.add(tokenJson(token, client));
                }
                json(ctx, 200, list);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> destroyToken() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                String id = ctx.pathParam("token_id");
                OAuthStore.Token token = oauth.store().findTokenForUser(id, user.uid());
                if (token == null) {
                    ctx.response().setStatusCode(404).end();
                    return;
                }
                oauth.store().revokeToken(id);
                OAuthStore.RefreshToken refresh = oauth.store()
                        .refreshTokenForAccessToken(id);
                if (refresh != null) {
                    oauth.store().revokeRefreshToken(refresh.id());
                }
                ctx.response().setStatusCode(204).end();
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ personal

    public Handler<RoutingContext> personalTokensForUser() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                List<Object> list = new ArrayList<>();
                for (OAuthStore.Token token : oauth.store().activeTokensForUser(user.uid())) {
                    OAuthStore.Client client = oauth.store().findActiveClient(token.clientId());
                    if (client == null || !client.personalAccessClient()) {
                        continue;
                    }
                    list.add(tokenJson(token, client));
                }
                json(ctx, 200, list);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> storePersonalToken() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        null, null);
                validator.addRule("name", "required");
                validator.addRule("name", "max:255");
                if (!validator.passes()) {
                    validationError(ctx, validator.errors());
                    return;
                }
                List<String> scopes = new ArrayList<>();
                for (String scope : OAuthService.stringList(input.get("scopes"))) {
                    if (!oauth.hasScope(scope)) {
                        Map<String, List<String>> errors = new LinkedHashMap<>();
                        errors.put("scopes", List.of("The selected scopes is invalid."));
                        validationError(ctx, errors);
                        return;
                    }
                    scopes.add(scope);
                }
                OAuthStore.Client personal = oauth.store().personalAccessClient();
                if (personal == null) {
                    ctx.fail(new RuntimeException(
                            "Personal access client not found for 'users' user provider."
                                    + " Please create one."));
                    return;
                }
                OAuthService.Issued issued = oauth.issue(user.uid(), personal.id(),
                        string(input.get("name")), scopes, false);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accessTokenId", issued.tokenId());
                body.put("tokenType", "Bearer");
                body.put("expiresIn", issued.expiresIn());
                body.put("accessToken", issued.accessToken());
                json(ctx, 200, body);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    public Handler<RoutingContext> destroyPersonalToken() {
        return ctx -> destroyToken().handle(ctx);
    }

    // ============================================================ scopes

    public Handler<RoutingContext> scopes() {
        return ctx -> {
            try {
                json(ctx, 200, oauth.scopeJson());
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ helpers

    private static Map<String, Object> tokenJson(OAuthStore.Token token,
            OAuthStore.Client client) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", token.id());
        map.put("user_id", token.userId());
        map.put("client_id", token.clientId());
        map.put("name", token.name());
        map.put("scopes", token.scopes());
        map.put("revoked", token.revoked());
        map.put("created_at", token.createdAt());
        map.put("updated_at", token.updatedAt());
        map.put("expires_at", token.expiresAt());
        map.put("client", OAuthStore.clientJson(client));

        return map;
    }

    private static void deleteSecret(Map<String, Object> client) {
        client.remove("secret");
    }

    private static String randomAlnum(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }

        return sb.toString();
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private void validationError(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long toLong(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public static HandlerResolver resolver(OAuthService oauth, Translator translator,
            Cipher cipher) {
        PassportApiController c = new PassportApiController(oauth, translator, cipher);

        return action -> switch (action) {
            case "Laravel\\Passport\\Http\\Controllers\\ClientController@forUser" ->
                    c.clientsForUser();
            case "Laravel\\Passport\\Http\\Controllers\\ClientController@store" ->
                    c.storeClient();
            case "Laravel\\Passport\\Http\\Controllers\\ClientController@update" ->
                    c.updateClient();
            case "Laravel\\Passport\\Http\\Controllers\\ClientController@destroy" ->
                    c.destroyClient();
            case "Laravel\\Passport\\Http\\Controllers\\AuthorizedAccessTokenController@forUser" ->
                    c.tokensForUser();
            case "Laravel\\Passport\\Http\\Controllers\\AuthorizedAccessTokenController@destroy" ->
                    c.destroyToken();
            case "Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@forUser" ->
                    c.personalTokensForUser();
            case "Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@store" ->
                    c.storePersonalToken();
            case "Laravel\\Passport\\Http\\Controllers\\PersonalAccessTokenController@destroy" ->
                    c.destroyPersonalToken();
            case "Laravel\\Passport\\Http\\Controllers\\ScopeController@all" -> c.scopes();
            default -> null;
        };
    }
}
