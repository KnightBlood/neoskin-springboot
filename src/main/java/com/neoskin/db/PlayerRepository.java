package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

/**
 * {@code players} 表的只读访问（按角色名登录用）。
 *
 * <p>表结构以 {@code database/migrations/2016_11_18_133939_create_all_tables.php}
 * 与 {@code 2019_03_13_130311_rename_players_table_columns.php} 为准：
 * 原列 {@code player_name} → {@code name}，并新增 {@code tid_skin} / {@code model}。</p>
 */
public final class PlayerRepository {

    /** {@code players} 的一行（只取登录用到的列）。 */
    public record PlayerRow(int pid, int uid, String name, int tidSkin, int tidCape, String model) {
    }

    /** {@code players} + 所属用户权限 + {@code last_modified}（CustomSkinAPI 用）。 */
    public record PlayerDetail(int pid, int uid, String name, int tidSkin, int tidCape,
            String lastModified, int permission) {
    }

    /** {@code Player::where('name', $name)->firstOrFail()} 的完整视图。 */
    public PlayerDetail findDetailByName(String name) throws SQLException {
        String sql = "SELECT p.*, u.permission AS permission FROM players p "
                + "LEFT JOIN users u ON u.uid = p.uid WHERE p.name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PlayerDetail(rs.getInt("pid"), rs.getInt("uid"), rs.getString("name"),
                        rs.getInt("tid_skin"), rs.getInt("tid_cape"),
                        rs.getString("last_modified"), rs.getInt("permission"));
            }
        }
    }

    private final DataSource dataSource;

    public PlayerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** {@code Player::where('name', $name)->first()}。 */
    public PlayerRow findByName(String name) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM players WHERE name = ?")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static PlayerRow map(ResultSet rs) throws SQLException {
        return new PlayerRow(
                rs.getInt("pid"),
                rs.getInt("uid"),
                rs.getString("name"),
                intOrZero(rs, "tid_skin"),
                intOrZero(rs, "tid_cape"),
                stringOrNull(rs, "model"));
    }

    private static int intOrZero(ResultSet rs, String column) {
        try {
            return rs.getInt(column);
        } catch (SQLException e) {
            return 0;
        }
    }

    private static String stringOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    /** {@code Player::where('name', $name)->count()}。 */
    public int countByName(String name) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM players WHERE name = ?")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** {@code $user->players()->count()}。 */
    public int countByUser(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM players WHERE uid = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** {@code Player::count()}（管理员仪表盘统计）。 */
    public int countAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM players");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 插入新玩家，返回 pid。 */
    public int insert(int uid, String name) throws SQLException {
        return insert(uid, name, com.neoskin.support.PhpDate.now("Asia/Shanghai"));
    }

    /** 插入新玩家（带 {@code last_modified}），{@code tid_skin=-1} 与源 migration 默认值一致。 */
    public int insert(int uid, String name, String now) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO players (uid, name, tid_skin, tid_cape, last_modified) "
                             + "VALUES (?,?,?,?,?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, uid);
            stmt.setString(2, name);
            stmt.setInt(3, -1);
            stmt.setInt(4, 0);
            stmt.setString(5, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    /** {@code Player::find($pid)}。 */
    public PlayerRow findByPid(int pid) throws SQLException {
        String sql = "SELECT * FROM players WHERE pid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** {@code Auth::user()->players}。 */
    public java.util.List<PlayerRow> listByUid(int uid) throws SQLException {
        String sql = "SELECT * FROM players WHERE uid = ? ORDER BY pid ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                java.util.List<PlayerRow> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return out;
            }
        }
    }

    /** 更新玩家名与 {@code last_modified}。 */
    public void updateName(int pid, String name, String now) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE players SET name = ?, last_modified = ? WHERE pid = ?")) {
            stmt.setString(1, name);
            stmt.setString(2, now);
            stmt.setInt(3, pid);
            stmt.executeUpdate();
        }
    }

    /** 更新皮肤/披风 tid 与 {@code last_modified}。 */
    public void updateTextures(int pid, int tidSkin, int tidCape, String now) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE players SET tid_skin = ?, tid_cape = ?, last_modified = ? "
                             + "WHERE pid = ?")) {
            stmt.setInt(1, tidSkin);
            stmt.setInt(2, tidCape);
            stmt.setString(3, now);
            stmt.setInt(4, pid);
            stmt.executeUpdate();
        }
    }

    /**
     * {@code ResetPlayers}：把使用某材质的玩家的 {@code tid_skin}/{@code tid_cape} 置 0。
     *
     * @param column     {@code tid_skin} 或 {@code tid_cape}
     * @param excludeUid 非空时排除该用户（隐私切换时上传者自己的玩家不受影响）
     */
    public void resetTexture(long tid, String column, Integer excludeUid) throws SQLException {
        String col = "tid_cape".equals(column) ? "tid_cape" : "tid_skin";
        String sql = "UPDATE players SET " + col + " = 0 WHERE " + col + " = ?"
                + (excludeUid != null ? " AND uid <> ?" : "");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tid);
            if (excludeUid != null) {
                stmt.setInt(2, excludeUid);
            }
            stmt.executeUpdate();
        }
    }

    /**
     * {@code closet.removed} 监听者：用户把某材质从衣柜移除后，其玩家使用该材质的
     * 外观要归零（源 {@code $user->players()->where($type, $tid)->update([$type => 0])}）。
     */
    public void resetTextureForUser(int uid, long tid, String column) throws SQLException {
        String col = "tid_cape".equals(column) ? "tid_cape" : "tid_skin";
        String sql = "UPDATE players SET " + col + " = 0 WHERE " + col + " = ? AND uid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tid);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    /** 删除玩家。 */
    public void delete(int pid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM players WHERE pid = ?")) {
            stmt.setInt(1, pid);
            stmt.executeUpdate();
        }
    }

    /** 读取 {@code last_modified}。 */
    public String lastModified(int pid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT last_modified FROM players WHERE pid = ?")) {
            stmt.setInt(1, pid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** {@code $player->uid = $uid; $player->save()}（自动刷新 {@code last_modified}）。 */
    public void updateOwner(int pid, int uid, String now) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE players SET uid = ?, last_modified = ? WHERE pid = ?")) {
            stmt.setInt(1, uid);
            stmt.setString(2, now);
            stmt.setInt(3, pid);
            stmt.executeUpdate();
        }
    }

    /**
     * {@code Player::usingSearchString($q)->paginate(10)} 的数据页。
     *
     * <p>行按 Eloquent casts 归一化（pid/uid/tid_skin/tid_cape 为整数），
     * 字段顺序 = 表列顺序（{@code pid, uid, name, tid_cape, last_modified, tid_skin}）。</p>
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
                    "SELECT COUNT(*) FROM players" + where)) {
                bind(stmt, compiled.params(), 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }

            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + columns + " FROM players" + where + order
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
    public record AdminPage(java.util.List<Map<String, Object>> rows, long total) {
    }

    private static int bind(PreparedStatement stmt, java.util.List<Object> params, int from)
            throws SQLException {
        int index = from;
        for (Object param : params) {
            stmt.setObject(index++, param);
        }

        return index;
    }

    private static Map<String, Object> readAdminRow(ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String column = meta.getColumnLabel(i);
            Object value = DbValues.isDateColumn(column)
                    ? DbValues.dateTime(rs, column)
                    : rs.getObject(i);
            if ("pid".equals(column) || "uid".equals(column)
                    || "tid_skin".equals(column) || "tid_cape".equals(column)) {
                value = value == null ? null : ((Number) value).intValue();
            }
            row.put(column, value);
        }

        return row;
    }

    private static PlayerRow mapRow(ResultSet rs) throws SQLException {
        return new PlayerRow(rs.getInt("pid"), rs.getInt("uid"), rs.getString("name"),
                rs.getInt("tid_skin"), rs.getInt("tid_cape"), null);
    }
}
