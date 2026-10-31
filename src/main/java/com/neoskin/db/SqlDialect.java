package com.neoskin.db;

/**
 * SQL 方言抽象。
 *
 * <p>源项目通过 Laravel 的 Schema Builder 在 5 种数据库上生成同一套表结构。本移植用
 * 纯 JDBC 复刻同样的 DDL（见 {@link SchemaInstaller}），因此需要一个方言层把
 * Laravel 的列类型（{@code increments} / {@code string} / {@code longText} /
 * {@code dateTime} / {@code boolean} …）映射成各数据库的真实类型。
 *
 * <p>类型映射严格对照 Laravel 13 的
 * {@code Illuminate\Database\Schema\Grammars\*Grammar}，保证与 PHP 版建出来的
 * 表结构一致（字段名、类型、默认值、可空性、索引）。
 */
public abstract class SqlDialect {

    /** 源 config/database.php 的 connection 名。 */
    private final String name;

    protected SqlDialect(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * 按 {@code DB_CONNECTION} 选择方言。
     *
     * <p>源项目只有 sqlite / mysql / pgsql / sqlsrv 四个连接，MariaDB 通过 mysql 连接
     * 访问；但本项目额外接受 {@code mariadb} 作为独立值（AGENTS.md 允许），
     * 以便使用 MariaDB 专用 JDBC 驱动。
     */
    public static SqlDialect of(String connection) {
        String c = connection == null ? "" : connection.trim().toLowerCase();
        return switch (c) {
            case "sqlite" -> new Sqlite();
            case "mariadb" -> new MariaDb();
            case "mysql" -> new MySql();
            case "pgsql", "postgres", "postgresql" -> new Postgres();
            case "sqlsrv", "mssql", "sqlserver" -> new SqlServer();
            default -> throw new IllegalArgumentException(
                    "Unsupported DB_CONNECTION: " + connection
                            + " (expected sqlite / mysql / mariadb / pgsql / sqlsrv)");
        };
    }

    /** Hibernate 方言类名，供 JPA 使用。 */
    public abstract String hibernateDialect();

    /** JDBC URL 前缀，例如 {@code jdbc:mysql://}。 */
    public abstract String jdbcPrefix();

    /** 默认端口，空字符串表示不适用（SQLite）。 */
    public abstract String defaultPort();

    /** 标识符引用。 */
    public abstract String quote(String identifier);

    /**
     * 从 JSON 列里取一个顶层文本键（对应 Eloquent 的 {@code where('data->key', ...)}
     * 的读侧）。
     *
     * <p>语义注记（对应 Laravel {@code data->key} 的<strong>读</strong>）：
     * Eloquent 的比较是把 JSON 里的标量转成字符串（{@code {'communication_id': 5}}
     * 与 {@code 5} 相等，与 {@code '5'} 也相等）。MySQL 的
     * {@code JSON_UNQUOTE(JSON_EXTRACT())} 返回的可能带类型前缀的值与
     * PostgreSQL 的 {@code ->>} 都是「文本化」，但数值列会变成 {@code "5"}；
     * SQLite 的 {@code JSON_EXTRACT()} 返回 <em>数值</em> 而不是字符串。
     * 统一做法：调用方在 SQL 里额外套 {@code CAST(... AS CHAR/VARCHAR)}，
     * 见 {@link #castText(String)}。</p>
     */
    public abstract String jsonText(String column, String key);

    /** {@code CAST(x AS 文本)}：把 JSON 取值转成可比的文本。 */
    public abstract String castText(String expr);

    /**
     * 从 JSON 列里取一个顶层整数键（数值比较用，如
     * {@code communications.id = data.communication_id} 的关联）。
     *
     * <p>MySQL 用裸 {@code JSON_EXTRACT}（数值型标量返回数字）；PG 的
     * {@code ->>} 返回文本所以补 {@code ::bigint}。SQLite 的
     * {@code JSON_EXTRACT} 本身返回数字，与 MySQL 同型。</p>
     */
    public abstract String jsonInt(String column, String key);

    /** {@code increments()} / {@code bigIncrements()} 的列定义。 */
    public abstract String autoIncrementPk(String column, boolean big);

    public abstract String varchar(int length);

    public abstract String text();

    public abstract String longText();

    public abstract String intType();

    public abstract String unsignedInt();

    public abstract String bigInt();

    public abstract String unsignedBigInt();

    public abstract String tinyInt();

    public abstract String unsignedTinyInt();

    public abstract String dateTime();

    public abstract String timestamp();

    public abstract String bool();

    /** boolean 字面量。 */
    public abstract String boolLiteral(boolean value);

    // ------------------------------------------------------------- 分页 / 行数限制

    /**
     * 分页子句（含 2 个占位符）。
     *
     * <p>默认（MySQL / MariaDB / PostgreSQL / SQLite）：{@code LIMIT ? OFFSET ?}；
     * SQL Server 不支持该语法，用 {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}
     * （要求前面有 {@code ORDER BY}，所有调用点都满足）。</p>
     *
     * <p><b>参数顺序不同</b>：SQL Server 是 {@code (offset, limit)}，其余是
     * {@code (limit, offset)}，所以绑定必须走 {@link #bindPaging}。</p>
     */
    public String pagingClause() {
        return " LIMIT ? OFFSET ?";
    }

    /** 分页参数是否 (offset, limit) 顺序（SQL Server 为 true）。 */
    public boolean offsetBeforeLimit() {
        return false;
    }

    /** 绑定分页参数，返回下一个可用索引。 */
    public int bindPaging(java.sql.PreparedStatement ps, int index, long limit, long offset)
            throws java.sql.SQLException {
        if (offsetBeforeLimit()) {
            ps.setLong(index++, offset);
            ps.setLong(index++, limit);
        } else {
            ps.setLong(index++, limit);
            ps.setLong(index++, offset);
        }

        return index;
    }

    /** 单行查询的 SELECT 前缀（SQL Server 是 {@code TOP 1 }）。 */
    public String firstRowPrefix() {
        return "";
    }

    /** 单行查询的后缀（非 SQL Server 是 {@code LIMIT 1}）。 */
    public String firstRowSuffix() {
        return " LIMIT 1";
    }

    /** 带参数行数限制的 SELECT 前缀（SQL Server 是 {@code TOP (?)}）。 */
    public String limitPrefix() {
        return "";
    }

    /** 带参数行数限制的后缀（非 SQL Server 是 {@code LIMIT ?}）。 */
    public String limitSuffix() {
        return " LIMIT ?";
    }

    /**
     * 行数限制参数是否出现在 SELECT 前缀里（SQL Server 的 {@code TOP (?)}
     * 必须最先绑定）。
     */
    public boolean limitParameterFirst() {
        return false;
    }

    /** 字面量行数限制的 SELECT 前缀（SQL Server：{@code TOP n }）。 */
    public String limitLiteralPrefix(int n) {
        return "";
    }

    /** 字面量行数限制的后缀（其余数据库：{@code LIMIT n}）。 */
    public String limitLiteralSuffix(int n) {
        return " LIMIT " + n;
    }

    /**
     * 唯一索引是否需要过滤条件（SQL Server 的唯一索引把多个 NULL 视为重复，
     * Laravel 的 sqlsrv grammar 对可空列生成 {@code WHERE col IS NOT NULL}）。
     */
    public boolean filteredUniqueIndex() {
        return false;
    }

    // ------------------------------------------------------------------ 便利方法

    /** 带索引的 varchar。 */
    public String index(String name, String table, String... columns) {
        StringBuilder sb = new StringBuilder("CREATE INDEX ").append(quote(name))
                .append(" ON ").append(quote(table)).append(" (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(columns[i]));
        }
        return sb.append(')').toString();
    }

    // ================================================================== MySQL / MariaDB

    static class MySql extends SqlDialect {
        MySql() {
            this("mysql");
        }

        MySql(String name) {
            super(name);
        }

        @Override public String hibernateDialect() { return "org.hibernate.dialect.MySQLDialect"; }
        @Override public String jdbcPrefix() { return "jdbc:mysql://"; }
        @Override public String defaultPort() { return "3306"; }
        @Override public String quote(String id) { return "`" + id + "`"; }
        @Override public String autoIncrementPk(String col, boolean big) {
            return quote(col) + (big ? " BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY"
                    : " INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY");
        }
        @Override public String varchar(int len) { return "VARCHAR(" + len + ")"; }
        @Override public String text() { return "TEXT"; }
        @Override public String longText() { return "LONGTEXT"; }
        @Override public String intType() { return "INT"; }
        @Override public String unsignedInt() { return "INT UNSIGNED"; }
        @Override public String bigInt() { return "BIGINT"; }
        @Override public String unsignedBigInt() { return "BIGINT UNSIGNED"; }
        @Override public String tinyInt() { return "TINYINT"; }
        @Override public String unsignedTinyInt() { return "TINYINT UNSIGNED"; }
        @Override public String dateTime() { return "DATETIME"; }
        @Override public String timestamp() { return "TIMESTAMP"; }
        @Override public String bool() { return "TINYINT(1)"; }
        @Override public String boolLiteral(boolean v) { return v ? "1" : "0"; }
        @Override public String jsonText(String col, String key) {
            return "JSON_UNQUOTE(JSON_EXTRACT(" + col + ", '$." + key + "'))";
        }
        @Override public String castText(String expr) { return "CAST(" + expr + " AS CHAR)"; }
        @Override public String jsonInt(String col, String key) {
            return "JSON_EXTRACT(" + col + ", '$." + key + "')";
        }

        /** 源项目建表引擎固定 InnoDB + utf8mb4，这里保持一致。 */
        @Override public String tableSuffix() {
            return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        }
    }

    static final class MariaDb extends MySql {
        MariaDb() {
            super("mariadb");
        }

        @Override public String hibernateDialect() { return "org.hibernate.dialect.MariaDBDialect"; }
        @Override public String jdbcPrefix() { return "jdbc:mariadb://"; }
    }

    /** 建表语句后缀（仅 MySQL 族需要引擎与字符集）。 */
    public String tableSuffix() {
        return "";
    }

    // ================================================================== PostgreSQL

    static class Postgres extends SqlDialect {
        Postgres() {
            super("pgsql");
        }

        @Override public String hibernateDialect() { return "org.hibernate.dialect.PostgreSQLDialect"; }
        @Override public String jdbcPrefix() { return "jdbc:postgresql://"; }
        @Override public String defaultPort() { return "5432"; }
        @Override public String quote(String id) { return "\"" + id + "\""; }
        @Override public String autoIncrementPk(String col, boolean big) {
            // Laravel 的 increments() 在 PG 上生成 serial / bigserial
            return quote(col) + (big ? " BIGSERIAL PRIMARY KEY" : " SERIAL PRIMARY KEY");
        }
        @Override public String varchar(int len) { return "VARCHAR(" + len + ")"; }
        @Override public String text() { return "TEXT"; }
        @Override public String longText() { return "TEXT"; }
        @Override public String intType() { return "INTEGER"; }
        @Override public String unsignedInt() { return "INTEGER"; }
        @Override public String bigInt() { return "BIGINT"; }
        @Override public String unsignedBigInt() { return "BIGINT"; }
        @Override public String tinyInt() { return "SMALLINT"; }
        @Override public String unsignedTinyInt() { return "SMALLINT"; }
        @Override public String dateTime() { return "TIMESTAMP(0) WITHOUT TIME ZONE"; }
        @Override public String timestamp() { return "TIMESTAMP(0) WITHOUT TIME ZONE"; }
        @Override public String bool() { return "BOOLEAN"; }
        @Override public String boolLiteral(boolean v) { return v ? "TRUE" : "FALSE"; }
        @Override public String jsonText(String col, String key) {
            return "(" + col + "->>'" + key + "')";
        }
        @Override public String castText(String expr) { return "CAST(" + expr + " AS TEXT)"; }
        @Override public String jsonInt(String col, String key) {
            return "(CAST(" + col + "->>'" + key + "' AS BIGINT))";
        }
    }

    // ================================================================== SQL Server

    static class SqlServer extends SqlDialect {
        SqlServer() {
            super("sqlsrv");
        }

        @Override public String hibernateDialect() { return "org.hibernate.dialect.SQLServerDialect"; }
        @Override public String jdbcPrefix() { return "jdbc:sqlserver://"; }
        @Override public String defaultPort() { return "1433"; }
        @Override public String quote(String id) { return "[" + id + "]"; }
        @Override public String autoIncrementPk(String col, boolean big) {
            return quote(col) + (big ? " BIGINT IDENTITY(1,1) PRIMARY KEY"
                    : " INT IDENTITY(1,1) PRIMARY KEY");
        }
        @Override public String varchar(int len) { return "NVARCHAR(" + len + ")"; }
        @Override public String text() { return "NVARCHAR(MAX)"; }
        @Override public String longText() { return "NVARCHAR(MAX)"; }
        @Override public String intType() { return "INT"; }
        @Override public String unsignedInt() { return "INT"; }
        @Override public String bigInt() { return "BIGINT"; }
        @Override public String unsignedBigInt() { return "BIGINT"; }
        @Override public String tinyInt() { return "TINYINT"; }
        @Override public String unsignedTinyInt() { return "TINYINT"; }
        @Override public String dateTime() { return "DATETIME"; }
        @Override public String timestamp() { return "DATETIME"; }
        @Override public String bool() { return "BIT"; }
        @Override public String boolLiteral(boolean v) { return v ? "1" : "0"; }
        @Override public String jsonText(String col, String key) {
            return "JSON_VALUE(" + col + ", '$." + key + "')";
        }
        @Override public String castText(String expr) { return "CAST(" + expr + " AS NVARCHAR(4000))"; }
        @Override public String jsonInt(String col, String key) {
            return "CAST(JSON_VALUE(" + col + ", '$." + key + "') AS BIGINT)";
        }

        // SQL Server 没有 LIMIT/OFFSET；分页用 OFFSET ... FETCH NEXT ...，
        // 参数顺序是 (offset, limit)。
        @Override public String pagingClause() {
            return " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        }
        @Override public boolean offsetBeforeLimit() { return true; }
        @Override public String firstRowPrefix() { return "TOP 1 "; }
        @Override public String firstRowSuffix() { return ""; }
        @Override public String limitPrefix() { return "TOP (?) "; }
        @Override public String limitSuffix() { return ""; }
        @Override public boolean limitParameterFirst() { return true; }
        @Override public String limitLiteralPrefix(int n) { return "TOP " + n + " "; }
        @Override public String limitLiteralSuffix(int n) { return ""; }
        @Override public boolean filteredUniqueIndex() { return true; }
    }

    // ================================================================== SQLite

    static class Sqlite extends SqlDialect {
        Sqlite() {
            super("sqlite");
        }

        @Override public String hibernateDialect() { return "org.hibernate.community.dialect.SQLiteDialect"; }
        @Override public String jdbcPrefix() { return "jdbc:sqlite:"; }
        @Override public String defaultPort() { return ""; }
        @Override public String quote(String id) { return "\"" + id + "\""; }
        @Override public String autoIncrementPk(String col, boolean big) {
            // SQLite 只支持 INTEGER PRIMARY KEY AUTOINCREMENT（无 BIGINT 变体）
            return quote(col) + " INTEGER PRIMARY KEY AUTOINCREMENT";
        }
        @Override public String varchar(int len) { return "VARCHAR(" + len + ")"; }
        @Override public String text() { return "TEXT"; }
        @Override public String longText() { return "TEXT"; }
        @Override public String intType() { return "INTEGER"; }
        @Override public String unsignedInt() { return "INTEGER"; }
        @Override public String bigInt() { return "INTEGER"; }
        @Override public String unsignedBigInt() { return "INTEGER"; }
        @Override public String tinyInt() { return "TINYINT"; }
        @Override public String unsignedTinyInt() { return "TINYINT"; }
        @Override public String dateTime() { return "DATETIME"; }
        @Override public String timestamp() { return "DATETIME"; }
        @Override public String bool() { return "TINYINT(1)"; }
        @Override public String boolLiteral(boolean v) { return v ? "1" : "0"; }
        @Override public String jsonText(String col, String key) {
            return "JSON_EXTRACT(" + col + ", '$." + key + "')";
        }
        @Override public String castText(String expr) { return "CAST(" + expr + " AS TEXT)"; }
        @Override public String jsonInt(String col, String key) {
            return "JSON_EXTRACT(" + col + ", '$." + key + "')";
        }
    }
}
