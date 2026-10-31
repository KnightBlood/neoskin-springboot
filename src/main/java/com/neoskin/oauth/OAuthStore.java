package com.neoskin.oauth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Passport 数据库访问（旧版 oauth_* 表结构：clients 为自增 id / redirect 文本 /
 * personal_access_client / password_client）。
 */
public final class OAuthStore {

    public record Client(long id, Integer userId, String name, String secret, String provider,
            String redirect, boolean personalAccessClient, boolean passwordClient,
            boolean revoked, String createdAt, String updatedAt) {

        public List<String> redirectUris() {
            List<String> uris = new ArrayList<>();
            if (redirect != null && !redirect.isEmpty()) {
                for (String part : redirect.split(",")) {
                    uris.add(part.trim());
                }
            }

            return uris;
        }
    }

    public record Token(String id, Integer userId, long clientId, String name,
            List<String> scopes, boolean revoked, String createdAt, String updatedAt,
            String expiresAt) {
    }

    public record AuthCode(String id, int userId, long clientId, List<String> scopes,
            boolean revoked, String expiresAt) {
    }

    public record RefreshToken(String id, String accessTokenId, boolean revoked,
            String expiresAt) {
    }

    private final DataSource dataSource;
    private final String timezone;

    public OAuthStore(DataSource dataSource, String timezone) {
        this.dataSource = dataSource;
        this.timezone = timezone;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    // ---------------------------------------------------------------- 客户端

    public Client findClient(long id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_clients WHERE id = ?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapClient(rs) : null;
            }
        }
    }

    public Client findActiveClient(long id) throws SQLException {
        Client client = findClient(id);

        return client != null && !client.revoked() ? client : null;
    }

    public List<Client> clientsForUser(int uid) throws SQLException {
        List<Client> clients = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_clients WHERE user_id = ? AND revoked = ?"
                             + " ORDER BY name")) {
            stmt.setInt(1, uid);
            stmt.setBoolean(2, false);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    clients.add(mapClient(rs));
                }
            }
        }

        return clients;
    }

    public Client findClientForUser(long id, int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_clients WHERE id = ? AND user_id = ? AND revoked = ?")) {
            stmt.setLong(1, id);
            stmt.setInt(2, uid);
            stmt.setBoolean(3, false);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapClient(rs) : null;
            }
        }
    }

    public long createClient(Integer userId, String name, String secret, String provider,
            String redirect, boolean personalAccess, boolean passwordClient) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO oauth_clients (user_id, name, secret, provider, redirect,"
                             + " personal_access_client, password_client, revoked,"
                             + " created_at, updated_at)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            if (userId == null) {
                stmt.setNull(1, java.sql.Types.BIGINT);
            } else {
                stmt.setInt(1, userId);
            }
            stmt.setString(2, name);
            stmt.setString(3, secret);
            stmt.setString(4, provider);
            stmt.setString(5, redirect);
            stmt.setBoolean(6, personalAccess);
            stmt.setBoolean(7, passwordClient);
            stmt.setBoolean(8, false);
            String now = com.neoskin.support.PhpDate.now(timezone);
            stmt.setString(9, now);
            stmt.setString(10, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    public void updateClient(long id, String name, String redirect) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE oauth_clients SET name = ?, redirect = ?, updated_at = ?"
                             + " WHERE id = ?")) {
            stmt.setString(1, name);
            stmt.setString(2, redirect);
            stmt.setString(3, com.neoskin.support.PhpDate.now(timezone));
            stmt.setLong(4, id);
            stmt.executeUpdate();
        }
    }

    /** {@code ClientRepository::delete()}：撤销该客户端的令牌并置 revoked。 */
    public void deleteClient(long id) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE oauth_refresh_tokens SET revoked = ? WHERE access_token_id IN"
                            + " (SELECT id FROM oauth_access_tokens WHERE client_id = ?)")) {
                stmt.setBoolean(1, true);
                stmt.setLong(2, id);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE oauth_access_tokens SET revoked = ? WHERE client_id = ?")) {
                stmt.setBoolean(1, true);
                stmt.setLong(2, id);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE oauth_clients SET revoked = ?, updated_at = ? WHERE id = ?")) {
                stmt.setBoolean(1, true);
                stmt.setString(2, com.neoskin.support.PhpDate.now(timezone));
                stmt.setLong(3, id);
                stmt.executeUpdate();
            }
        }
    }

    /** 第一个启用的 personal_access 客户端（源 {@code personalAccessClient()}）。 */
    public Client personalAccessClient() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_clients WHERE revoked = ?"
                             + " AND personal_access_client = ? ORDER BY id DESC")) {
            stmt.setBoolean(1, false);
            stmt.setBoolean(2, true);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapClient(rs) : null;
            }
        }
    }

    /** 客户端 secret 校验（Passport 13 使用 bcrypt 哈希）。 */
    public boolean verifyClientSecret(Client client, String secret,
            com.neoskin.service.cipher.Cipher cipher) {
        if (client.secret() == null || client.secret().isEmpty()) {
            return secret == null || secret.isEmpty();
        }
        if (secret == null) {
            return false;
        }
        if (client.secret().startsWith("$2") || client.secret().startsWith("$argon")) {
            return cipher.verify(secret, client.secret(), "");
        }

        return secret.equals(client.secret());
    }

    // ---------------------------------------------------------------- 令牌

    public void insertToken(String id, Integer userId, long clientId, String name,
            List<String> scopes, String expiresAt) throws SQLException {
        String now = com.neoskin.support.PhpDate.now(timezone);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO oauth_access_tokens (id, user_id, client_id, name, scopes,"
                             + " revoked, created_at, updated_at, expires_at)"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            stmt.setString(1, id);
            if (userId == null) {
                stmt.setNull(2, java.sql.Types.BIGINT);
            } else {
                stmt.setInt(2, userId);
            }
            stmt.setLong(3, clientId);
            stmt.setString(4, name);
            stmt.setString(5, com.neoskin.support.PhpJson.encode(scopes, 0));
            stmt.setBoolean(6, false);
            stmt.setString(7, now);
            stmt.setString(8, now);
            stmt.setString(9, expiresAt);
            stmt.executeUpdate();
        }
    }

    public Token findToken(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_access_tokens WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapToken(rs) : null;
            }
        }
    }

    public void revokeToken(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE oauth_access_tokens SET revoked = ?, updated_at = ? WHERE id = ?")) {
            stmt.setBoolean(1, true);
            stmt.setString(2, com.neoskin.support.PhpDate.now(timezone));
            stmt.setString(3, id);
            stmt.executeUpdate();
        }
    }

    public List<Token> activeTokensForUser(int uid) throws SQLException {
        List<Token> tokens = new ArrayList<>();
        String now = com.neoskin.support.PhpDate.now(timezone);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT t.* FROM oauth_access_tokens t JOIN oauth_clients c"
                             + " ON c.id = t.client_id"
                             + " WHERE t.user_id = ? AND t.revoked = ?"
                             + " AND t.expires_at > ? AND c.revoked = ? ORDER BY t.created_at DESC")) {
            stmt.setInt(1, uid);
            stmt.setBoolean(2, false);
            stmt.setString(3, now);
            stmt.setBoolean(4, false);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tokens.add(mapToken(rs));
                }
            }
        }

        return tokens;
    }

    public Token findTokenForUser(String id, int uid) throws SQLException {
        String now = com.neoskin.support.PhpDate.now(timezone);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_access_tokens WHERE id = ? AND user_id = ?"
                             + " AND revoked = ? AND expires_at > ?")) {
            stmt.setString(1, id);
            stmt.setInt(2, uid);
            stmt.setBoolean(3, false);
            stmt.setString(4, now);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapToken(rs) : null;
            }
        }
    }

    public boolean hasScopesForClientAndUser(int uid, long clientId, List<String> scopes)
            throws SQLException {
        String now = com.neoskin.support.PhpDate.now(timezone);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT scopes FROM oauth_access_tokens WHERE user_id = ? AND client_id = ?"
                             + " AND revoked = ? AND expires_at > ?")) {
            stmt.setInt(1, uid);
            stmt.setLong(2, clientId);
            stmt.setBoolean(3, false);
            stmt.setString(4, now);
            try (ResultSet rs = stmt.executeQuery()) {
                if (scopes.isEmpty()) {
                    return rs.next();
                }
                List<String> granted = new ArrayList<>();
                while (rs.next()) {
                    granted.addAll(parseScopes(rs.getString(1)));
                }

                return granted.containsAll(scopes);
            }
        }
    }

    // ---------------------------------------------------------------- 授权码

    public void insertAuthCode(String id, int userId, long clientId, List<String> scopes,
            String expiresAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO oauth_auth_codes (id, user_id, client_id, scopes, revoked,"
                             + " expires_at) VALUES (?,?,?,?,?,?)")) {
            stmt.setString(1, id);
            stmt.setInt(2, userId);
            stmt.setLong(3, clientId);
            stmt.setString(4, com.neoskin.support.PhpJson.encode(scopes, 0));
            stmt.setBoolean(5, false);
            stmt.setString(6, expiresAt);
            stmt.executeUpdate();
        }
    }

    public AuthCode findAuthCode(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_auth_codes WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                return new AuthCode(rs.getString("id"), rs.getInt("user_id"),
                        rs.getLong("client_id"), parseScopes(rs.getString("scopes")),
                        rs.getBoolean("revoked"), rs.getString("expires_at"));
            }
        }
    }

    public void revokeAuthCode(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE oauth_auth_codes SET revoked = ? WHERE id = ?")) {
            stmt.setBoolean(1, true);
            stmt.setString(2, id);
            stmt.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- 刷新令牌

    public void insertRefreshToken(String id, String accessTokenId, String expiresAt)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO oauth_refresh_tokens (id, access_token_id, revoked, expires_at)"
                             + " VALUES (?,?,?,?)")) {
            stmt.setString(1, id);
            stmt.setString(2, accessTokenId);
            stmt.setBoolean(3, false);
            stmt.setString(4, expiresAt);
            stmt.executeUpdate();
        }
    }

    public RefreshToken findRefreshToken(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM oauth_refresh_tokens WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                return new RefreshToken(rs.getString("id"), rs.getString("access_token_id"),
                        rs.getBoolean("revoked"), rs.getString("expires_at"));
            }
        }
    }

    public void revokeRefreshToken(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE oauth_refresh_tokens SET revoked = ? WHERE id = ?")) {
            stmt.setBoolean(1, true);
            stmt.setString(2, id);
            stmt.executeUpdate();
        }
    }

    public RefreshToken refreshTokenForAccessToken(String accessTokenId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            var dialect = com.neoskin.db.Paging.dialectOf(conn);
            String sql = "SELECT " + dialect.firstRowPrefix()
                    + "* FROM oauth_refresh_tokens WHERE access_token_id = ?"
                    + dialect.firstRowSuffix();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, accessTokenId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    return new RefreshToken(rs.getString("id"), rs.getString("access_token_id"),
                            rs.getBoolean("revoked"), rs.getString("expires_at"));
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    public static List<String> parseScopes(String json) {
        List<String> scopes = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return scopes;
        }
        try {
            Object decoded = com.neoskin.support.PhpJson.decode(json);
            if (decoded instanceof List<?> list) {
                for (Object item : list) {
                    scopes.add(String.valueOf(item));
                }
            } else if (decoded instanceof Map<?, ?> map) {
                for (Object item : map.values()) {
                    scopes.add(String.valueOf(item));
                }
            }
        } catch (RuntimeException ignored) {
            // 与 PHP json_decode 失败一致：空数组
        }

        return scopes;
    }

    private static Client mapClient(ResultSet rs) throws SQLException {
        return new Client(rs.getLong("id"),
                rs.getObject("user_id") == null ? null : rs.getInt("user_id"),
                rs.getString("name"), rs.getString("secret"), rs.getString("provider"),
                rs.getString("redirect"), rs.getBoolean("personal_access_client"),
                rs.getBoolean("password_client"), rs.getBoolean("revoked"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static Token mapToken(ResultSet rs) throws SQLException {
        return new Token(rs.getString("id"),
                rs.getObject("user_id") == null ? null : rs.getInt("user_id"),
                rs.getLong("client_id"), rs.getString("name"),
                parseScopes(rs.getString("scopes")), rs.getBoolean("revoked"),
                rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("expires_at"));
    }

    /** 客户端 JSON（{@code Client::toArray()}：隐藏 secret）。 */
    public static Map<String, Object> clientJson(Client client) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", client.id());
        map.put("user_id", client.userId());
        map.put("name", client.name());
        map.put("provider", client.provider());
        map.put("redirect", client.redirect());
        map.put("personal_access_client", client.personalAccessClient());
        map.put("password_client", client.passwordClient());
        map.put("revoked", client.revoked());
        map.put("created_at", client.createdAt());
        map.put("updated_at", client.updatedAt());

        return map;
    }
}
