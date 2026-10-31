package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * {@code user_closet} 关联表的仓库。
 *
 * <p>对应 {@code User::closet()}（belongsToMany Texture，withPivot item_name）。</p>
 */
public final class ClosetRepository {

    /** 收藏夹条目：pivot 字段 + 纹理行。 */
    public record Item(int userUid, long textureTid, String itemName,
            TextureRepository.Row texture) {
    }

    private final DataSource dataSource;

    public ClosetRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 类型过滤条件：cape → 只披风；其余 → steve/alex。 */
    public static String typeCondition(String category) {
        return "cape".equals(category)
                ? "t.type = 'cape'"
                : "t.type IN ('steve','alex')";
    }

    /** {@code getClosetData} 的计数。 */
    public long count(int uid, String category, String search) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM user_closet c JOIN textures t ON t.tid = c.texture_tid "
                        + "WHERE c.user_uid = ? AND ").append(typeCondition(category));
        if (search != null) {
            sql.append(" AND c.item_name LIKE ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setInt(1, uid);
            if (search != null) {
                stmt.setString(2, "%" + search + "%");
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** {@code getClosetData} 的分页行，按 {@code texture_tid} 倒序。 */
    public List<Item> page(int uid, String category, String search, int limit, int offset)
            throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT t.*, c.user_uid AS cu, c.texture_tid AS ct, c.item_name AS ci "
                            + "FROM textures t JOIN user_closet c ON t.tid = c.texture_tid "
                            + "WHERE c.user_uid = ? AND ").append(typeCondition(category));
            if (search != null) {
                sql.append(" AND c.item_name LIKE ?");
            }
            sql.append(" ORDER BY t.tid DESC").append(Paging.clause(conn));
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                int i = 1;
                stmt.setInt(i++, uid);
                if (search != null) {
                    stmt.setString(i++, "%" + search + "%");
                }
                Paging.bind(conn, stmt, i, limit, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Item> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new Item(rs.getInt("cu"), rs.getLong("ct"), rs.getString("ci"),
                                mapTexture(rs)));
                    }
                    return out;
                }
            }
        }
    }

    /** {@code allIds}：{@code pluck('texture_tid')}。 */
    public List<Long> allIds(int uid) throws SQLException {        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT texture_tid FROM user_closet WHERE user_uid = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        }
    }

    /** {@code $texture->likers()->where('user_uid','!=',uploader)}：衣柜里收藏该材质的其他用户。 */
    public List<Integer> likerUids(long tid, int excludeUid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT user_uid FROM user_closet WHERE texture_tid = ? AND user_uid <> ?"
                             + " ORDER BY user_uid")) {
            stmt.setLong(1, tid);
            stmt.setInt(2, excludeUid);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Integer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
                return out;
            }
        }
    }

    /** {@code $user->closet}：管理端接口用的完整衣柜（不过滤材质类型）。 */
    public List<Item> all(int uid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT t.*, c.user_uid AS cu, c.texture_tid AS ct, c.item_name AS ci "
                             + "FROM textures t JOIN user_closet c ON t.tid = c.texture_tid "
                             + "WHERE c.user_uid = ? ORDER BY t.tid")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Item> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Item(rs.getInt("cu"), rs.getLong("ct"), rs.getString("ci"),
                            mapTexture(rs)));
                }
                return out;
            }
        }
    }

    /** {@code $user->closet()->find($tid)}：找单条 pivot 记录。 */
    public Item find(int uid, long tid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT t.*, c.user_uid AS cu, c.texture_tid AS ct, c.item_name AS ci "
                             + "FROM textures t JOIN user_closet c ON t.tid = c.texture_tid "
                             + "WHERE c.user_uid = ? AND c.texture_tid = ?")) {
            stmt.setInt(1, uid);
            stmt.setLong(2, tid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? new Item(rs.getInt("cu"), rs.getLong("ct"), rs.getString("ci"),
                                mapTexture(rs))
                        : null;
            }
        }
    }

    /** {@code ...->exists()}：只判断存在。 */
    public boolean exists(int uid, long tid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM user_closet WHERE user_uid = ? AND texture_tid = ?")) {
            stmt.setInt(1, uid);
            stmt.setLong(2, tid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** {@code $user->closet()->attach($tid, ['item_name' => $name])}。 */
    public void attach(int uid, long tid, String itemName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO user_closet (user_uid, texture_tid, item_name) VALUES (?,?,?)")) {
            stmt.setInt(1, uid);
            stmt.setLong(2, tid);
            stmt.setString(3, itemName);
            stmt.executeUpdate();
        }
    }

    /** {@code updateExistingPivot($tid, ['item_name' => $name])}。 */
    public void renameItem(int uid, long tid, String itemName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE user_closet SET item_name = ? WHERE user_uid = ? AND texture_tid = ?")) {
            stmt.setString(1, itemName);
            stmt.setInt(2, uid);
            stmt.setLong(3, tid);
            stmt.executeUpdate();
        }
    }

    /** {@code $user->closet()->detach($tid)}。 */
    public void detach(int uid, long tid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM user_closet WHERE user_uid = ? AND texture_tid = ?")) {
            stmt.setInt(1, uid);
            stmt.setLong(2, tid);
            stmt.executeUpdate();
        }
    }

    private static TextureRepository.Row mapTexture(ResultSet rs) throws SQLException {
        return new TextureRepository.Row(rs.getLong("tid"), rs.getString("name"),
                rs.getString("type"), rs.getString("hash"), rs.getLong("size"),
                rs.getInt("uploader"), rs.getBoolean("public"), rs.getString("upload_at"),
                rs.getInt("likes"));
    }

    /** {@code Texture::toArray()}（含 model 追加属性）。 */
    public static Map<String, Object> textureJson(TextureRepository.Row t) {
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
        m.put("model", t.model());
        return m;
    }
}
