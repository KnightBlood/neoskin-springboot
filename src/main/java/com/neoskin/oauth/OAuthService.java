package com.neoskin.oauth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.config.AppPaths;
import com.neoskin.db.UserRepository;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.support.PhpDate;

import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;

/**
 * Passport 的核心服务：作用域、令牌签发、Bearer/Cookie 认证。
 *
 * <p>access token 采用与 league/oauth2-server v9 相同的 RS256 JWT
 * （aud/jti/iat/nbf/exp/sub/scopes）；refresh token 使用明文随机串
 * （源为 php-encryption 加密载荷，见 PORT-NOTES §34 偏差）。</p>
 */
public final class OAuthService {

    public static final long TOKEN_TTL_SECONDS = 31536000L; // P1Y
    public static final long CODE_TTL_SECONDS = 600L;       // PT10M
    public static final String COOKIE_NAME = "laravel_token";

    private static final Map<String, String> DEFAULT_SCOPES = new LinkedHashMap<>();

    static {
        DEFAULT_SCOPES.put("User.Read", "auth.oauth.scope.user.read");
        DEFAULT_SCOPES.put("Notification.Read", "auth.oauth.scope.notification.read");
        DEFAULT_SCOPES.put("Notification.ReadWrite", "auth.oauth.scope.notification.readwrite");
        DEFAULT_SCOPES.put("Player.Read", "auth.oauth.scope.player.read");
        DEFAULT_SCOPES.put("Player.ReadWrite", "auth.oauth.scope.player.readwrite");
        DEFAULT_SCOPES.put("Closet.Read", "auth.oauth.scope.closet.read");
        DEFAULT_SCOPES.put("Closet.ReadWrtie", "auth.oauth.scope.closet.readwrite");
        DEFAULT_SCOPES.put("UsersManagement.Read",
                "auth.oauth.scope.users-management.read");
        DEFAULT_SCOPES.put("UsersManagement.ReadWrite",
                "auth.oauth.scope.users-management.readwrite");
        DEFAULT_SCOPES.put("PlayersManagement.Read",
                "auth.oauth.scope.players-management.read");
        DEFAULT_SCOPES.put("PlayersManagement.ReadWrite",
                "auth.oauth.scope.players-management.readwrite");
        DEFAULT_SCOPES.put("ClosetManagement.Read",
                "auth.oauth.scope.closet-management.read");
        DEFAULT_SCOPES.put("ClosetManagement.ReadWrite",
                "auth.oauth.scope.closet-management.readwrite");
        DEFAULT_SCOPES.put("ReportsManagement.Read",
                "auth.oauth.scope.reports-management.read");
        DEFAULT_SCOPES.put("ReportsManagement.ReadWrite",
                "auth.oauth.scope.reports-management.readwrite");
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthStore store;
    private final OAuthKeys keys;
    private final UserRepository users;
    private final Cipher cipher;
    private final byte[] cookieKey;
    private final String timezone;
    private volatile Map<String, String> scopes;

    public OAuthService(AppPaths paths, OAuthStore store, UserRepository users, Cipher cipher,
            byte[] cookieKey, String timezone) {
        this.store = store;
        this.keys = new OAuthKeys(paths);
        this.users = users;
        this.cipher = cipher;
        this.cookieKey = cookieKey;
        this.timezone = timezone;
    }

    public OAuthStore store() {
        return store;
    }

    public OAuthKeys keys() {
        return keys;
    }

    // ---------------------------------------------------------------- scopes

    /** 默认作用域 + {@code scopes} 表（插件自定义）。 */
    public Map<String, String> scopes() {
        Map<String, String> cached = scopes;
        if (cached != null) {
            return cached;
        }
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_SCOPES);
        try (var conn = store.dataSource().getConnection();
             var stmt = conn.prepareStatement("SELECT name, description FROM scopes")) {
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    merged.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (Exception ignored) {
            // 未安装 / 表不存在：只返回默认作用域（源在无 install.lock 时同样返回空库）
        }
        scopes = merged;

        return merged;
    }

    public List<Map<String, Object>> scopeJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : scopes().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("description", entry.getValue());
            list.add(item);
        }

        return list;
    }

    public boolean hasScope(String scope) {
        return scopes().containsKey(scope);
    }

    // ---------------------------------------------------------------- 签发

    public record Issued(String accessToken, String refreshToken, String tokenId,
            long expiresIn) {
    }

    /** 签发 access token（可选 refresh token）。 */
    public Issued issue(Integer userId, long clientId, String name, List<String> scopeList,
            boolean withRefresh) throws Exception {
        String tokenId = randomHex(40);
        long now = Instant.now().getEpochSecond();
        long exp = now + TOKEN_TTL_SECONDS;
        store.insertToken(tokenId, userId, clientId, name, scopeList,
                PhpDate.at(exp * 1000, timezone));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", List.of(String.valueOf(clientId)));
        claims.put("jti", tokenId);
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", exp);
        claims.put("sub", userId == null ? String.valueOf(clientId) : String.valueOf(userId));
        claims.put("scopes", scopeList);
        String accessToken = Jwts.rs256(claims, keys);

        String refresh = null;
        if (withRefresh) {
            refresh = randomHex(40);
            store.insertRefreshToken(refresh, tokenId,
                    PhpDate.at((now + TOKEN_TTL_SECONDS) * 1000, timezone));
        }

        return new Issued(accessToken, refresh, tokenId, TOKEN_TTL_SECONDS);
    }

    /**
     * 把 {@link com.neoskin.support.PhpJson.Array}（PHP 数组语义，JSON 数组也解成它）
     * 或 {@link List} 拍平成字符串列表。
     */
    public static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                out.add(String.valueOf(item));
            }
        }

        return out;
    }

    public static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    // ---------------------------------------------------------------- 认证

    /** 认证结果：用户 / 客户端 / 作用域 / token id。 */
    public record AuthResult(UserRepository.UserRow user, Long clientId,
            List<String> scopes, String tokenId) {

        public boolean can(String scope) {
            return scopes.contains("*") || scopes.contains(scope);
        }
    }

    /** Bearer token 或 Passport transient cookie 认证；失败返回 null。 */
    public AuthResult authenticate(RoutingContext ctx) {
        String authorization = ctx.request().getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer", 0, 6)) {
            String jwt = authorization.substring(6).trim();
            Map<String, Object> claims = Jwts.decodeRs256(jwt, keys);
            if (claims == null) {
                return null;
            }
            long now = Instant.now().getEpochSecond();
            if (claims.get("exp") instanceof Number exp && exp.longValue() < now) {
                return null;
            }
            if (claims.get("nbf") instanceof Number nbf && nbf.longValue() > now) {
                return null;
            }
            Object sub = claims.get("sub");
            Object jti = claims.get("jti");
            if (sub == null || jti == null) {
                return null;
            }
            try {
                OAuthStore.Token token = store.findToken(String.valueOf(jti));
                if (token == null || token.revoked()) {
                    return null;
                }
                List<String> audiences = stringList(claims.get("aud"));
                Long clientId;
                try {
                    clientId = audiences.isEmpty() ? null
                            : Long.valueOf(audiences.get(0));
                } catch (NumberFormatException e) {
                    clientId = null;
                }
                List<String> scopes = stringList(claims.get("scopes"));
                UserRepository.UserRow user = null;
                try {
                    user = users.findById(Integer.parseInt(String.valueOf(sub)));
                } catch (NumberFormatException ignored) {
                    // client_credentials：sub 是客户端 id，没有用户
                }

                return new AuthResult(user, clientId, scopes, String.valueOf(jti));
            } catch (Exception e) {
                return null;
            }
        }

        Cookie cookie = ctx.request().getCookie(COOKIE_NAME);
        if (cookie != null) {
            Map<String, Object> claims = Jwts.decodeHs256(cookie.getValue(), cookieKey);
            if (claims == null) {
                return null;
            }
            long now = Instant.now().getEpochSecond();
            if (claims.get("exp") instanceof Number exp && exp.longValue() < now) {
                return null;
            }
            String csrf = ctx.request().getHeader("X-CSRF-TOKEN");
            if (csrf == null) {
                csrf = ctx.request().getHeader("X-XSRF-TOKEN");
            }
            if (csrf == null || !csrf.equals(String.valueOf(claims.get("csrf")))) {
                return null;
            }
            try {
                UserRepository.UserRow user = users.findById(
                        Integer.parseInt(String.valueOf(claims.get("sub"))));

                return user == null ? null
                        : new AuthResult(user, null, List.of("*"), null);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    /** {@code ApiTokenCookieFactory::make()}（HS256 + session 有效期）。 */
    public Cookie transientCookie(int userId, String csrf, int lifetimeMinutes) {
        long exp = Instant.now().getEpochSecond() + lifetimeMinutes * 60L;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId);
        claims.put("csrf", csrf);
        claims.put("exp", exp);
        String jwt = Jwts.hs256(claims, cookieKey);

        return Cookie.cookie(COOKIE_NAME, jwt)
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(false)
                .setSameSite(io.vertx.core.http.CookieSameSite.LAX)
                .setMaxAge(lifetimeMinutes * 60L);
    }
}
