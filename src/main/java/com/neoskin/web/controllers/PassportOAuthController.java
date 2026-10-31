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
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.exception.OAuthError;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * Laravel Passport 的 OAuth 端点（{@code /oauth/token}、{@code /oauth/authorize}、
 * {@code /oauth/token/refresh}）。
 */
public final class PassportOAuthController {

    private final OAuthService oauth;
    private final Translator translator;
    private final Cipher cipher;

    public PassportOAuthController(OAuthService oauth, Translator translator, Cipher cipher) {
        this.oauth = oauth;
        this.translator = translator;
        this.cipher = cipher;
    }

    // ============================================================ token

    /** {@code POST /oauth/token}。 */
    public Handler<RoutingContext> issueToken() {
        return ctx -> {
            try {
                Map<String, Object> input = inputMap(ctx);
                String grant = string(input.get("grant_type"));
                Map<String, Object> response;
                try {
                    response = switch (grant) {
                        case "authorization_code" -> authorizationCodeGrant(input);
                        case "refresh_token" -> refreshTokenGrant(input);
                        case "client_credentials" -> clientCredentialsGrant(input);
                        default -> throw new OAuthError("unsupported_grant_type",
                                "The authorization grant type is not supported by the"
                                        + " authorization server.", 400);
                    };
                } catch (OAuthError e) {
                    json(ctx, e.status(), errorBody(e));
                    return;
                }

                json(ctx, 200, response);
            } catch (Exception e) {
                json(ctx, 500, errorBody(new OAuthError("server_error",
                        "The authorization server encountered an unexpected condition.", 500)));
            }
        };
    }

    private Map<String, Object> authorizationCodeGrant(Map<String, Object> input)
            throws Exception {
        OAuthStore.Client client = requireClient(input);
        String code = string(input.get("code"));
        if (code.isEmpty()) {
            throw new OAuthError("invalid_request", "The request is missing a required parameter.", 400);
        }
        OAuthStore.AuthCode authCode = oauth.store().findAuthCode(code);
        if (authCode == null || authCode.revoked()) {
            throw new OAuthError("invalid_grant", "Authorization code has been revoked", 400);
        }
        if (authCode.expiresAt() != null
                && authCode.expiresAt().compareTo(PhpDate.now(oauthTimezone())) < 0) {
            throw new OAuthError("invalid_grant", "Authorization code has expired", 400);
        }
        if (authCode.clientId() != client.id()) {
            throw new OAuthError("invalid_request",
                    "Authorization code was not issued to this client", 400);
        }

        oauth.store().revokeAuthCode(code);
        OAuthService.Issued issued = oauth.issue(authCode.userId(), client.id(), null,
                authCode.scopes(), true);

        return tokenResponse(issued);
    }

    private Map<String, Object> refreshTokenGrant(Map<String, Object> input) throws Exception {
        String refresh = string(input.get("refresh_token"));
        OAuthStore.RefreshToken token = refresh.isEmpty() ? null
                : oauth.store().findRefreshToken(refresh);
        if (token == null || token.revoked()) {
            throw new OAuthError("invalid_grant", "The refresh token is invalid.", 400);
        }
        if (token.expiresAt() != null
                && token.expiresAt().compareTo(PhpDate.now(oauthTimezone())) < 0) {
            throw new OAuthError("invalid_grant", "The refresh token is expired.", 400);
        }
        OAuthStore.Token access = oauth.store().findToken(token.accessTokenId());
        if (access == null) {
            throw new OAuthError("invalid_grant", "The refresh token is invalid.", 400);
        }
        OAuthStore.Client client = oauth.store().findActiveClient(access.clientId());
        if (client == null) {
            throw new OAuthError("invalid_client", "Client authentication failed", 401);
        }
        String clientId = string(input.get("client_id"));
        if (!clientId.isEmpty() && Long.parseLong(clientId) != client.id()) {
            throw new OAuthError("invalid_grant",
                    "The refresh token is not valid for this client.", 400);
        }

        oauth.store().revokeRefreshToken(refresh);
        oauth.store().revokeToken(access.id());
        OAuthService.Issued issued = oauth.issue(access.userId(), client.id(), access.name(),
                access.scopes(), true);

        return tokenResponse(issued);
    }

    private Map<String, Object> clientCredentialsGrant(Map<String, Object> input)
            throws Exception {
        OAuthStore.Client client = requireClient(input);
        if (client.userId() != null || client.secret() == null || client.secret().isEmpty()) {
            throw new OAuthError("invalid_client", "Client authentication failed", 401);
        }
        List<String> scopes = requestedScopes(input);
        OAuthService.Issued issued = oauth.issue(null, client.id(), null, scopes, false);

        return tokenResponse(issued);
    }

    private OAuthStore.Client requireClient(Map<String, Object> input) throws Exception {
        String clientId = string(input.get("client_id"));
        if (clientId.isEmpty()) {
            throw new OAuthError("invalid_request",
                    "The request is missing a required parameter: client_id", 400);
        }
        OAuthStore.Client client;
        try {
            client = oauth.store().findActiveClient(Long.parseLong(clientId));
        } catch (NumberFormatException e) {
            client = null;
        }
        if (client == null) {
            throw new OAuthError("invalid_client", "Client authentication failed", 401);
        }
        String secret = string(input.get("client_secret"));
        if (client.secret() != null && !client.secret().isEmpty()
                && !oauth.store().verifyClientSecret(client, secret, cipher)) {
            throw new OAuthError("invalid_client", "Client authentication failed", 401);
        }

        return client;
    }

    private List<String> requestedScopes(Map<String, Object> input) throws OAuthError {
        List<String> scopes = new ArrayList<>();
        String raw = string(input.get("scope"));
        if (!raw.isEmpty()) {
            for (String part : raw.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    if (!oauth.hasScope(part)) {
                        throw new OAuthError("invalid_scope",
                                "The requested scope is invalid, unknown, or malformed.", 400);
                    }
                    scopes.add(part);
                }
            }
        }

        return scopes;
    }

    private static Map<String, Object> tokenResponse(OAuthService.Issued issued) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token_type", "Bearer");
        response.put("expires_in", issued.expiresIn());
        response.put("access_token", issued.accessToken());
        if (issued.refreshToken() != null) {
            response.put("refresh_token", issued.refreshToken());
        }

        return response;
    }

    // ============================================================ authorize

    /** {@code GET /oauth/authorize}。 */
    public Handler<RoutingContext> authorize() {
        return ctx -> {
            try {
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user == null) {
                    ctx.fail(401);
                    return;
                }
                String clientId = query(ctx, "client_id");
                String redirectUri = query(ctx, "redirect_uri");
                String responseType = query(ctx, "response_type");
                String state = query(ctx, "state");
                String scopeRaw = query(ctx, "scope");

                OAuthStore.Client client = clientId.isEmpty() ? null
                        : oauth.store().findActiveClient(Long.parseLong(clientId));
                if (client == null) {
                    json(ctx, 400, errorBody(new OAuthError("invalid_client",
                            "Client authentication failed", 400)));
                    return;
                }
                if (!responseType.isEmpty() && !"code".equals(responseType)) {
                    json(ctx, 400, errorBody(new OAuthError("unsupported_response_type",
                            "The authorization server does not support obtaining an access token"
                                    + " using this method.", 400)));
                    return;
                }
                if (!redirectUri.isEmpty()
                        && !client.redirectUris().contains(redirectUri)) {
                    json(ctx, 400, errorBody(new OAuthError("invalid_request",
                            "Invalid redirect URI", 400)));
                    return;
                }
                if (redirectUri.isEmpty()) {
                    redirectUri = client.redirectUris().isEmpty() ? ""
                            : client.redirectUris().get(0);
                }
                List<String> scopes = new ArrayList<>();
                if (!scopeRaw.isEmpty()) {
                    for (String part : scopeRaw.trim().split("\\s+")) {
                        if (!part.isEmpty()) {
                            scopes.add(part);
                        }
                    }
                } else {
                    scopes.add("User.Read");
                }

                // 已授权过相同 scope → 直接签发 code（源 hasGrantedScopes）
                if (oauth.store().hasScopesForClientAndUser(user.uid(), client.id(), scopes)) {
                    issueCodeAndRedirect(ctx, client, user, scopes, redirectUri, state);
                    return;
                }

                SessionStore session = ctx.get(StartSession.CTX_KEY);
                String authToken = OAuthService.randomHex(20);
                if (session != null) {
                    session.put("oauth_client_id", client.id());
                    session.put("oauth_redirect_uri", redirectUri);
                    session.put("oauth_state", state);
                    session.put("oauth_scopes", scopes);
                    session.put("oauth_auth_token", authToken);
                }

                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("client", OAuthStore.clientJson(client));
                vars.put("user", user.nickname());
                vars.put("scopes", scopeDescriptions(scopes));
                vars.put("auth_token", authToken);
                vars.put("approve_url", "http://" + hostOf(ctx) + "/oauth/authorize");
                String html = ViewEngine.render("oauth/authorize", java.util.Locale.ROOT, vars);
                ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "text/html; charset=utf-8")
                        .end(Buffer.buffer(html, "UTF-8"));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code POST /oauth/authorize}：同意授权。 */
    public Handler<RoutingContext> approve() {
        return ctx -> {
            try {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                if (session == null) {
                    ctx.fail(403);
                    return;
                }
                String posted = inputMap(ctx).get("auth_token") == null ? ""
                        : String.valueOf(inputMap(ctx).get("auth_token"));
                String stored = session.get("oauth_auth_token") == null ? ""
                        : String.valueOf(session.get("oauth_auth_token"));
                if (!stored.equals(posted)) {
                    ctx.fail(403);
                    return;
                }
                UserRepository.UserRow user = Authenticate.currentUser(ctx);
                OAuthStore.Client client = oauth.store()
                        .findActiveClient(toLong(session.get("oauth_client_id")));
                if (user == null || client == null) {
                    ctx.fail(403);
                    return;
                }
                List<String> scopes = OAuthService.stringList(session.get("oauth_scopes"));
                if (scopes.isEmpty()) {
                    scopes = List.of("User.Read");
                }
                String redirectUri = String.valueOf(session.get("oauth_redirect_uri"));
                String state = session.get("oauth_state") == null ? ""
                        : String.valueOf(session.get("oauth_state"));
                for (String key : List.of("oauth_client_id", "oauth_redirect_uri",
                        "oauth_state", "oauth_scopes", "oauth_auth_token")) {
                    session.forget(key);
                }
                issueCodeAndRedirect(ctx, client, user, scopes, redirectUri, state);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code DELETE /oauth/authorize}：拒绝授权。 */
    public Handler<RoutingContext> deny() {
        return ctx -> {
            try {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                String redirectUri = session == null ? ""
                        : String.valueOf(session.get("oauth_redirect_uri"));
                String state = session == null || session.get("oauth_state") == null ? ""
                        : String.valueOf(session.get("oauth_state"));
                if (session != null) {
                    for (String key : List.of("oauth_client_id", "oauth_redirect_uri",
                            "oauth_state", "oauth_scopes", "oauth_auth_token")) {
                        session.forget(key);
                    }
                }
                if (redirectUri == null || redirectUri.isEmpty() || "null".equals(redirectUri)) {
                    ctx.fail(400);
                    return;
                }
                String target = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                        + "error=access_denied";
                if (!state.isEmpty()) {
                    target += "&state=" + encode(state);
                }
                redirect(ctx, target);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private void issueCodeAndRedirect(RoutingContext ctx, OAuthStore.Client client,
            UserRepository.UserRow user, List<String> scopes, String redirectUri, String state)
            throws Exception {
        String code = OAuthService.randomHex(40);
        oauth.store().insertAuthCode(code, user.uid(), client.id(), scopes,
                PhpDate.at((Instant.now().getEpochSecond() + OAuthService.CODE_TTL_SECONDS)
                        * 1000, oauthTimezone()));
        String target = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                + "code=" + code;
        if (state != null && !state.isEmpty()) {
            target += "&state=" + encode(state);
        }
        redirect(ctx, target);
    }

    private List<Map<String, Object>> scopeDescriptions(List<String> scopes) {
        Map<String, String> all = oauth.scopes();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String scope : scopes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", scope);
            item.put("description", all.getOrDefault(scope, scope));
            list.add(item);
        }

        return list;
    }

    // ============================================================ refresh

    /** {@code POST /oauth/token/refresh}：写 transient cookie。 */
    public Handler<RoutingContext> transientRefresh() {
        return ctx -> {
            UserRepository.UserRow user = Authenticate.currentUser(ctx);
            if (user == null) {
                ctx.fail(401);
                return;
            }
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            String csrf = session == null ? "" : String.valueOf(session.token());
            ctx.response().addCookie(oauth.transientCookie(user.uid(), csrf, 120));
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/plain; charset=UTF-8")
                    .end("Refreshed.");
        };
    }

    // ============================================================ helpers

    private String oauthTimezone() {
        return "Asia/Shanghai";
    }

    private void redirect(RoutingContext ctx, String location) {
        ctx.response().setStatusCode(302)
                .putHeader("Location", location)
                .putHeader("Cache-Control", "no-cache, private")
                .end();
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> errorBody(OAuthError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.error());
        body.put("error_description", error.description());

        return body;
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private static String query(RoutingContext ctx, String name) {
        String value = ctx.request().getParam(name);

        return value == null ? "" : value;
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(OAuthService oauth, Translator translator,
            Cipher cipher) {
        PassportOAuthController c = new PassportOAuthController(oauth, translator, cipher);

        return action -> switch (action) {
            case "Laravel\\Passport\\Http\\Controllers\\AccessTokenController@issueToken" ->
                    c.issueToken();
            case "Laravel\\Passport\\Http\\Controllers\\AuthorizationController@authorize" ->
                    c.authorize();
            case "Laravel\\Passport\\Http\\Controllers\\ApproveAuthorizationController@approve" ->
                    c.approve();
            case "Laravel\\Passport\\Http\\Controllers\\DenyAuthorizationController@deny" ->
                    c.deny();
            case "Laravel\\Passport\\Http\\Controllers\\TransientTokenController@refresh" ->
                    c.transientRefresh();
            default -> null;
        };
    }
}
