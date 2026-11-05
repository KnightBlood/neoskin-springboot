package com.neoskin.db;

/**
 * 纹理查询的便捷仓库（只读）。
 *
 * <p>纹理文件本身按 {@code hash} 存放在 {@code storage/textures/}，
 * 本类只负责 {@code textures} 表的行映射。</p>
 */
public final class TextureRepository {

    /** {@code textures} 的一行。 */
    public record Row(long tid, String name, String type, String hash, long size,
            int uploader, boolean isPublic, String uploadAt, int likes) {

        /** {@code Texture::getModelAttribute()}。 */
        public String model() {
            return "alex".equals(type) ? "slim" : "default";
        }
    }

    private final javax.sql.DataSource dataSource;

    /** 方言；仅用于给保留字列（`public`）加引用。测试用单参构造时可为 null。 */
    private final SqlDialect dialect;

    public TextureRepository(javax.sql.DataSource dataSource) {
        this(dataSource, null);
    }

    public TextureRepository(javax.sql.DataSource dataSource, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    /** 引用标识符；无方言时保持原样（SQLite 测试路径）。 */
    private String col(String name) {
        return dialect == null ? name : dialect.quote(name);
    }

    /** {@code SkinlibController@handleUpload}：新增纹理行；返回自增 tid。 */
    public long insert(String name, String type, String hash, long size, int uploader,
            boolean pub, String uploadAt)
            throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO textures (name, type, hash, size, uploader, " + col("public")
                             + ", upload_at, likes) VALUES (?,?,?,?,?,?,?,1)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.setString(2, type);
            stmt.setString(3, hash);
            stmt.setLong(4, size);
            stmt.setInt(5, uploader);
            stmt.setInt(6, pub ? 1 : 0);
            stmt.setString(7, uploadAt);
            stmt.executeUpdate();
            try (var keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** {@code Texture::where('hash',...)->where(public true OR uploader me)->first()}。 */
    public Row findPublicOrMine(String hash, int uid) throws java.sql.SQLException {
        return find("SELECT * FROM textures WHERE hash = ? AND (" + col("public")
                        + " = 1 OR uploader = ?)",
                hash == null ? null : hash, uid);
    }

    /** {@code Texture::where('hash',...)->where('public', true)->first()}。 */
    public Row findPublicByHash(String hash) throws java.sql.SQLException {
        return find("SELECT * FROM textures WHERE hash = ? AND " + col("public") + " = 1", hash);
    }

    /** 同 hash 的行数（delete 时判断文件是否仍被占用）。 */
    public long countByHash(String hash) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM textures WHERE hash = ?")) {
            stmt.setString(1, hash);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** {@code Texture::count()}（管理员仪表盘统计）。 */
    public long countAll() throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT COUNT(*) FROM textures");
             var rs = stmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** {@code Texture::select('size')->sum('size')}：单位为 KB（上传时按 KB 记录）。 */
    public long sumSize() throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT SUM(size) FROM textures");
             var rs = stmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * {@code Texture::where('upload_at', '>=', $since)->select('upload_at')->get()}：
     * 只取上传时间列供仪表盘折线图按日聚合。
     */
    public java.util.List<String> uploadTimesSince(String since) throws java.sql.SQLException {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT upload_at FROM textures WHERE upload_at >= ?")) {
            stmt.setString(1, since);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }

        return result;
    }

    /** {@code $texture->name = ...; save()}。 */
    public void updateName(long tid, String name) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE textures SET name = ? WHERE tid = ?")) {
            stmt.setString(1, name);
            stmt.setLong(2, tid);
            stmt.executeUpdate();
        }
    }

    /** {@code $texture->type = ...; save()}。 */
    public void updateType(long tid, String type) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE textures SET type = ? WHERE tid = ?")) {
            stmt.setString(1, type);
            stmt.setLong(2, tid);
            stmt.executeUpdate();
        }
    }

    /** {@code $texture->public = ...; save()}。 */
    public void updatePublic(long tid, boolean pub) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE textures SET " + col("public") + " = ? WHERE tid = ?")) {
            stmt.setInt(1, pub ? 1 : 0);
            stmt.setLong(2, tid);
            stmt.executeUpdate();
        }
    }

    public Row findByTid(long tid) throws java.sql.SQLException {
        return find("SELECT * FROM textures WHERE tid = ?", tid);
    }

    private Row find(String sql, Object param) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, param);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        }
    }

    private Row find(String sql, Object param, Object param2) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, param);
            stmt.setObject(2, param2);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        }
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("tid"), rs.getString("name"), rs.getString("type"),
                rs.getString("hash"), rs.getLong("size"), rs.getInt("uploader"),
                rs.getBoolean("public"), DbValues.dateTime(rs, "upload_at"),
                rs.getInt("likes"));
    }

    public Row findByHash(String hash) throws java.sql.SQLException {
        return find("SELECT * FROM textures WHERE hash = ?", hash);
    }

    /** 删除纹理行。 */
    public void deleteByTid(long tid) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM textures WHERE tid = ?")) {
            stmt.setLong(1, tid);
            stmt.executeUpdate();
        }
    }

    /** 重置 {@code likes}（attach/detach 后同步）。 */
    public void updateLikes(long tid, int likes) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE textures SET likes = ? WHERE tid = ?")) {
            stmt.setInt(1, likes);
            stmt.setLong(2, tid);
            stmt.executeUpdate();
        }
    }

    /**
     * {@code SkinlibController@library} 的查询：纹理 + 上传者昵称，按 sortBy 倒序分页。
     *
     * @param type         {@code skin}（steve+alex）/ steve / alex / cape
     * @param keyword      名称模糊匹配；可为 null
     * @param uploaderUid  上传者过滤；可为 null
     * @param viewerUid    当前用户 uid；null 表示匿名
     * @param admin        当前用户是否管理员
     */
    public java.util.List<java.util.Map<String, Object>> page(String type, String keyword,
            Integer uploaderUid, Integer viewerUid, boolean admin, String sortBy,
            int limit, int offset) throws java.sql.SQLException {
        try (var conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT t.tid, t.name, t.type, t.uploader, t." + col("public")
                            + ", t.likes, u.nickname "
                            + "FROM textures t LEFT JOIN users u ON u.uid = t.uploader WHERE 1=1");
            java.util.List<Object> params = new java.util.ArrayList<>();
            appendFilters(sql, params, type, keyword, uploaderUid, viewerUid, admin);
            sql.append(" ORDER BY t.").append(sortColumn(sortBy)).append(" DESC");
            sql.append(Paging.clause(conn));
            try (var stmt = conn.prepareStatement(sql.toString())) {
                int next = bind(stmt, params);
                Paging.bind(conn, stmt, next, limit, offset);
                try (var rs = stmt.executeQuery()) {
                    java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
                    while (rs.next()) {
                        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("tid", rs.getLong("tid"));
                        m.put("name", rs.getString("name"));
                        m.put("type", rs.getString("type"));
                        m.put("uploader", rs.getInt("uploader"));
                        m.put("public", rs.getBoolean("public"));
                        m.put("likes", rs.getInt("likes"));
                        m.put("nickname", rs.getString("nickname"));
                        out.add(m);
                    }
                    return out;
                }
            }
        }
    }

    public long count(String type, String keyword, Integer uploaderUid, Integer viewerUid,
            boolean admin) throws java.sql.SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM textures t WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        appendFilters(sql, params, type, keyword, uploaderUid, viewerUid, admin);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql.toString())) {
            bind(stmt, params);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void appendFilters(StringBuilder sql, java.util.List<Object> params,
            String type, String keyword, Integer uploaderUid, Integer viewerUid, boolean admin) {
        if ("skin".equals(type)) {
            sql.append(" AND t.type IN ('steve','alex')");
        } else if (type != null && !type.isEmpty()) {
            sql.append(" AND t.type = ?");
            params.add(type);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND t.name LIKE ?");
            params.add("%" + keyword + "%");
        }
        if (uploaderUid != null) {
            sql.append(" AND t.uploader = ?");
            params.add(uploaderUid);
        }
        if (!admin) {
            if (viewerUid != null) {
                sql.append(" AND (t.").append(col("public")).append(" = 1 OR t.uploader = ?)");
                params.add(viewerUid);
            } else {
                sql.append(" AND t.").append(col("public")).append(" = 1");
            }
        }
    }

    private static String sortColumn(String sortBy) {
        return switch (sortBy == null ? "time" : sortBy) {
            case "name" -> "name";
            case "likes" -> "likes";
            case "tid" -> "tid";
            default -> "upload_at";
        };
    }

    /** 绑定参数并返回下一个可用索引。 */
    private static int bind(java.sql.PreparedStatement stmt, java.util.List<Object> params)
            throws java.sql.SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }

        return params.size() + 1;
    }
}