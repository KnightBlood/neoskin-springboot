package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * {@code users} 表的只读访问（登录用）。
 *
 * <p>表结构以 {@code database/migrations/2016_11_18_133939_create_all_tables.php}
 * 为准，后续 migration 追加 {@code verified} / {@code remember_token} /
 * {@code oauth_provider} / {@code locale} / {@code is_dark_mode} 等列。</p>
 *
 * <p>这里刻意<b>不</b>做成 JPA Entity：登录路径只需要「按邮箱/uid 取一行」，
 * 用 JDBC 更直接，也避免把 Eloquent 的隐式行为（自动时间戳、casts）带进来。
 * 领域模型等移植 UserController 时再统一。</p>
 */
public final class UserRepository {

    /** 权限常量（源 {@code App\Models\User}）。 */
    public static final int BANNED = -1;

    public static final int NORMAL = 0;

    public static final int ADMIN = 1;

    public static final int SUPER_ADMIN = 2;

    /** {@code users} 的一行（只取登录/视图用到的列）。 */
    public record UserRow(int uid, String email, String nickname, String password,
            int permission, int avatar, int score, boolean verified, boolean darkMode,
            String locale) {

        /** {@code User::isAdmin()}：{@code permission >= ADMIN}。 */
        public boolean isAdmin() {
            return permission >= ADMIN;
        }
    }

    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** {@code User::where('email', $email)->first()}。 */
    public UserRow findByEmail(String email) throws SQLException {
        return findOne("SELECT * FROM users WHERE email = ?", email);
    }

    /** {@code User::find($uid)}。 */
    public UserRow findById(int uid) throws SQLException {
        return findOne("SELECT * FROM users WHERE uid = ?", uid);
    }

    /** {@code auth_user()->toArray()}：完整用户行（隐藏 password/remember_token）。 */
    public Map<String, Object> findJsonById(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE uid = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? readAdminRow(rs) : null;
            }
        }
    }

    private UserRow findOne(String sql, Object param) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static UserRow map(ResultSet rs) throws SQLException {
        return new UserRow(
                rs.getInt("uid"),
                rs.getString("email"),
                nullable(rs, "nickname"),
                rs.getString("password"),
                rs.getInt("permission"),
                rs.getInt("avatar"),
                rs.getInt("score"),
                bool(rs, "verified"),
                bool(rs, "is_dark_mode"),
                nullable(rs, "locale"));
    }

    /** {@code User::where('email', $email)->count()}。 */
    public int countByEmail(String email) throws SQLException {
        return count("SELECT COUNT(*) FROM users WHERE email = ?", email);
    }

    /** {@code User::where('ip', $ip)->count()}。 */
    public int countByIp(String ip) throws SQLException {
        return count("SELECT COUNT(*) FROM users WHERE ip = ?", ip);
    }

    /** {@code User::count()}（管理员仪表盘统计）。 */
    public int countAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * {@code NotificationsController@send} 的收件人集合：
     * {@code all} / {@code normal} / {@code uid} / {@code email}。
     */
    public List<Integer> idsForAudience(String receiver, Integer uid, String email)
            throws SQLException {
        String sql;
        Object param = null;
        switch (receiver == null ? "all" : receiver) {
            case "normal" -> sql = "SELECT uid FROM users WHERE permission = "
                    + NORMAL + " ORDER BY uid";
            case "uid" -> {
                sql = "SELECT uid FROM users WHERE uid = ?";
                param = uid;
            }
            case "email" -> {
                sql = "SELECT uid FROM users WHERE email = ?";
                param = email;
            }
            default -> sql = "SELECT uid FROM users ORDER BY uid";
        }

        List<Integer> ids = new java.util.ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (param != null) {
                stmt.setObject(1, param);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }

        return ids;
    }

    /**
     * {@code User::where('register_at', '>=', $since)->select('register_at')->get()}：
     * 只取注册时间列供仪表盘折线图按日聚合。
     */
    public java.util.List<String> registerTimesSince(String since) throws SQLException {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT register_at FROM users WHERE register_at >= ?")) {
            stmt.setString(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }

        return result;
    }

    private int count(String sql, Object param) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 插入新用户，返回生成的 uid。 */
    public int insert(String email, String nickname, int score, String password,
            String ip, String timezone) throws SQLException {
        String now = com.neoskin.support.PhpDate.now(timezone);
        String sql = "INSERT INTO users (email, nickname, score, avatar, password, ip, "
                + "permission, last_sign_at, register_at, verified, verification_token, "
                + "remember_token, locale, is_dark_mode) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql,
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, email);
            stmt.setString(2, nickname);
            stmt.setInt(3, score);
            stmt.setInt(4, 0);
            stmt.setString(5, password);
            stmt.setString(6, ip);
            stmt.setInt(7, NORMAL);
            stmt.setString(8, now);            // last_sign_at = now - 1 day (adjusted below)
            stmt.setString(9, now);            // register_at = now
            stmt.setBoolean(10, false);
            stmt.setString(11, "");
            stmt.setNull(12, java.sql.Types.VARCHAR);
            stmt.setNull(13, java.sql.Types.VARCHAR);
            stmt.setBoolean(14, false);
            stmt.executeUpdate();
            // last_sign_at 应为 now - 1 day：更新
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                int uid = keys.next() ? keys.getInt(1) : 0;
                if (uid > 0) {
                    // 调整 last_sign_at = now - 1 day
                    String yesterday = com.neoskin.support.PhpDate.at(
                            java.time.Instant.now().minusSeconds(86400), timezone);
                    try (PreparedStatement u = conn.prepareStatement(
                            "UPDATE users SET last_sign_at = ? WHERE uid = ?")) {
                        u.setString(1, yesterday);
                        u.setInt(2, uid);
                        u.executeUpdate();
                    }
                }
                return uid;
            }
        }
    }

    /**
     * 安装向导：创建超级管理员（完整控制 verified / permission / 时间戳）。
     */
    public int insertSuperAdmin(String email, String nickname, int score, String password,
            String ip, String registerAt, String lastSignAt) throws SQLException {
        String sql = "INSERT INTO users (email, nickname, score, avatar, password, ip,"
                + " permission, last_sign_at, register_at, verified, verification_token,"
                + " remember_token, locale, is_dark_mode) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql,
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, email);
            stmt.setString(2, nickname);
            stmt.setInt(3, score);
            stmt.setInt(4, 0);
            stmt.setString(5, password);
            stmt.setString(6, ip);
            stmt.setInt(7, SUPER_ADMIN);
            stmt.setString(8, lastSignAt);
            stmt.setString(9, registerAt);
            stmt.setBoolean(10, true);
            stmt.setString(11, "");
            stmt.setNull(12, java.sql.Types.VARCHAR);
            stmt.setNull(13, java.sql.Types.VARCHAR);
            stmt.setBoolean(14, false);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    /** 更新 email。 */
    public void updateEmail(int uid, String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET email = ? WHERE uid = ?")) {
            stmt.setString(1, email);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 更新 locale（{@code SetAppLocale} 监听者用）。 */
    public void updateLocale(int uid, String locale) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET locale = ? WHERE uid = ?")) {
            stmt.setString(1, locale);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 设置 verified。 */
    public void updateVerified(int uid, boolean verified) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET verified = ? WHERE uid = ?")) {
            stmt.setBoolean(1, verified);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 修改密码。 */
    public void changePassword(int uid, String hashedPassword) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET password = ? WHERE uid = ?")) {
            stmt.setString(1, hashedPassword);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }
    /** 读取 {@code last_sign_at}（签到用）。 */
    public String lastSignAt(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT last_sign_at FROM users WHERE uid = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 更新昵称。 */
    public void updateNickname(int uid, String nickname) throws SQLException {
        updateOne("UPDATE users SET nickname = ? WHERE uid = ?", nickname, uid);
    }

    /** 更新分数与签到时间（{@code user->score += n; user->last_sign_at = now; save()}）。 */
    public void updateScoreAndSignAt(int uid, int score, String lastSignAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET score = ?, last_sign_at = ? WHERE uid = ?")) {
            stmt.setInt(1, score);
            stmt.setString(2, lastSignAt);
            stmt.setInt(3, uid);
            stmt.executeUpdate();
        }
    }

    /** 更新分数。 */
    public void updateScore(int uid, int score) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET score = ? WHERE uid = ?")) {
            stmt.setInt(1, score);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** {@code $user->closet()->where('texture_tid', $tid)->exists()}。 */
    public boolean inCloset(int uid, int tid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM user_closet WHERE user_uid = ? AND texture_tid = ?")) {
            stmt.setInt(1, uid);
            stmt.setInt(2, tid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 切换暗色模式。 */
    public void updateDarkMode(int uid, boolean darkMode) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET is_dark_mode = ? WHERE uid = ?")) {
            stmt.setBoolean(1, darkMode);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 更新头像 tid。 */
    public void updateAvatar(int uid, int avatar) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET avatar = ? WHERE uid = ?")) {
            stmt.setInt(1, avatar);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 更新邮箱并把 {@code verified} 置为 false。 */
    public void updateEmailAndUnverify(int uid, String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET email = ?, verified = ? WHERE uid = ?")) {
            stmt.setString(1, email);
            stmt.setBoolean(2, false);
            stmt.setInt(3, uid);
            stmt.executeUpdate();
        }
    }

    /** 删除用户。 */
    /** 更新 permission。 */
    public void updatePermission(int uid, int permission) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET permission = ? WHERE uid = ?")) {
            stmt.setInt(1, permission);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /**
     * {@code User::usingSearchString($q)->paginate(10)} 的数据页。
     *
     * <p>行按 Eloquent 的 casts 归一化（uid/score/avatar/permission 为整数，
     * verified/is_dark_mode 为布尔），并隐藏 {@code password}/{@code remember_token}；
     * 列顺序与表结构一致（{@code toArray()} 的字段顺序）。</p>
     */
    public AdminPage adminPage(com.neoskin.support.search.SearchString.Result compiled,
            int page, int perPage) throws SQLException {
        int current = Math.max(page, 1);
        String where = compiled.whereSql() == null ? "" : " WHERE " + compiled.whereSql();
        String columns = compiled.select().isEmpty()
                ? "*" : String.join(", ", compiled.select());
        String order = compiled.orderBy().isEmpty()
                ? "" : " ORDER BY " + String.join(", ", compiled.orderBy());

        try (Connection conn = dataSource.getConnection()) {
            long total;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM users" + where)) {
                bind(stmt, compiled.params(), 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }

            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + columns + " FROM users" + where + order
                            + Paging.clause(conn))) {
                int index = bind(stmt, compiled.params(), 1);
                index = Paging.bind(conn, stmt, index, perPage, (current - 1) * perPage);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(readAdminRow(rs));
                    }
                }
            }

            return new AdminPage(rows, total);
        }
    }

    /** 管理员列表的一页数据。 */
    public record AdminPage(List<Map<String, Object>> rows, long total) {
    }

    private static int bind(PreparedStatement stmt, List<Object> params, int from)
            throws SQLException {
        int index = from;
        for (Object param : params) {
            stmt.setObject(index++, param);
        }

        return index;
    }

    /** 行 → JSON 友好 Map（跳过 hidden 字段，按表列顺序）。 */
    public static Map<String, Object> readAdminRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String column = meta.getColumnLabel(i);
            if ("password".equals(column) || "remember_token".equals(column)) {
                continue;
            }
            Object value = rs.getObject(i);
            switch (column) {
                case "uid", "score", "avatar", "permission" ->
                        value = value == null ? null : ((Number) value).intValue();
                case "verified", "is_dark_mode" -> value = toBooleanValue(value);
                default -> {
                    // 保持驱动返回值（字符串/null）
                }
            }
            row.put(column, value);
        }

        return row;
    }

    private static boolean toBooleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }

        return value != null && !"0".equals(String.valueOf(value))
                && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * {@code $user->delete()}：先删该用户的角色（{@code Player::where('uid')->delete()}），
     * 再删用户本身。
     */
    public void deleteWithPlayers(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM players WHERE uid = ?")) {
                    stmt.setInt(1, uid);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM users WHERE uid = ?")) {
                    stmt.setInt(1, uid);
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    public void delete(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM users WHERE uid = ?")) {
            stmt.setInt(1, uid);
            stmt.executeUpdate();
        }
    }

    private void updateOne(String sql, Object first, int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, first);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    private static String nullable(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private static boolean bool(ResultSet rs, String column) {
        try {
            return rs.getBoolean(column);
        } catch (SQLException e) {
            return false;
        }
    }
}
