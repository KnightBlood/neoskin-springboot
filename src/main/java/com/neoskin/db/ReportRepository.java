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

import com.neoskin.support.search.SearchString;

/**
 * {@code reports} 表的访问（举报提交 / 我的举报 / 管理端列表与审核）。
 */
public final class ReportRepository {

    public static final int PENDING = 0;
    public static final int RESOLVED = 1;
    public static final int REJECTED = 2;

    /** 一行举报。 */
    public record Row(long id, int tid, int uploader, int reporter, String reason, int status,
            String reportAt) {
    }

    private final DataSource dataSource;
    private final UserRepository users;
    private final TextureRepository textures;

    public ReportRepository(DataSource dataSource, UserRepository users,
            TextureRepository textures) {
        this.dataSource = dataSource;
        this.users = users;
        this.textures = textures;
    }

    /** {@code Report::find($id)}。 */
    public Row findById(long id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM reports WHERE id = ?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** {@code Report::where('reporter',$uid)->where('tid',$tid)->count()}。 */
    public long countByReporterAndTid(int reporter, int tid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM reports WHERE reporter = ? AND tid = ?")) {
            stmt.setInt(1, reporter);
            stmt.setInt(2, tid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** {@code $report->save()}（新建，返回自增 id）。 */
    public long insert(int tid, int uploader, int reporter, String reason, int status,
            String reportAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO reports (tid, uploader, reporter, reason, status, report_at)"
                             + " VALUES (?, ?, ?, ?, ?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, tid);
            stmt.setInt(2, uploader);
            stmt.setInt(3, reporter);
            stmt.setString(4, reason);
            stmt.setInt(5, status);
            stmt.setString(6, reportAt);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** {@code $report->status = ...; $report->save()}。 */
    public void updateStatus(long id, int status) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE reports SET status = ? WHERE id = ?")) {
            stmt.setInt(1, status);
            stmt.setLong(2, id);
            stmt.executeUpdate();
        }
    }

    /** {@code Report::where('reporter',$uid)->orderBy('report_at','desc')->paginate(10)}。 */
    public Page pageByReporter(int reporter, int page, int perPage) throws SQLException {
        int current = Math.max(page, 1);
        try (Connection conn = dataSource.getConnection()) {
            long total;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM reports WHERE reporter = ?")) {
                stmt.setInt(1, reporter);
                try (ResultSet rs = stmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }

            List<Row> rows = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM reports WHERE reporter = ?"
                            + " ORDER BY report_at DESC" + Paging.clause(conn))) {
                stmt.setInt(1, reporter);
                Paging.bind(conn, stmt, 2, perPage, (current - 1) * perPage);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                }
            }

            return new Page(rows, total, current, perPage);
        }
    }

    /** 我的举报列表页。 */
    public record Page(List<Row> rows, long total, int currentPage, int perPage) {
    }

    /**
     * {@code Report::usingSearchString($q)->with(['texture','textureUploader','informer'])
     * ->paginate(9)}：行内嵌三个关联模型（缺失时为 null）。
     */
    public AdminPage adminPage(SearchString.Result compiled, int page, int perPage)
            throws SQLException {
        int current = Math.max(page, 1);
        String where = compiled.whereSql() == null ? "" : " WHERE " + compiled.whereSql();
        String columns = compiled.select().isEmpty()
                ? "*" : String.join(", ", compiled.select());
        String order = compiled.orderBy().isEmpty()
                ? "" : " ORDER BY " + String.join(", ", compiled.orderBy());

        long total;
        List<Row> rows = new ArrayList<>();
        // 先把分页数据全部取出并释放连接（toJson 需要再查纹理/用户，
        // 连接池只有 1 条连接时必须避免嵌套持有）
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM reports" + where)) {
                bind(stmt, compiled.params(), 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + columns + " FROM reports" + where + order
                            + Paging.clause(conn))) {
                int index = bind(stmt, compiled.params(), 1);
                index = Paging.bind(conn, stmt, index, perPage, (current - 1) * perPage);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                }
            }
        }

        List<Map<String, Object>> json = new ArrayList<>();
        for (Row row : rows) {
            json.add(toJson(row));
        }

        return new AdminPage(json, total);
    }

    /** 管理端列表的一页。 */
    public record AdminPage(List<Map<String, Object>> rows, long total) {
    }

    private Map<String, Object> toJson(Row row) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.id());
        m.put("tid", row.tid());
        m.put("uploader", row.uploader());
        m.put("reporter", row.reporter());
        m.put("reason", row.reason());
        m.put("status", row.status());
        m.put("report_at", row.reportAt());

        TextureRepository.Row texture = textures.findByTid(row.tid());
        m.put("texture", texture == null ? null : ClosetRepository.textureJson(texture));
        m.put("texture_uploader", users.findJsonById(row.uploader()));
        m.put("informer", users.findJsonById(row.reporter()));

        return m;
    }

    private static int bind(PreparedStatement stmt, List<Object> params, int from)
            throws SQLException {
        int index = from;
        for (Object param : params) {
            stmt.setObject(index++, param);
        }

        return index;
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getInt("tid"), rs.getInt("uploader"),
                rs.getInt("reporter"), rs.getString("reason"), rs.getInt("status"),
                rs.getString("report_at"));
    }
}
