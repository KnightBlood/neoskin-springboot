package com.neoskin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.NeoSkinSettings;

/**
 * 方言层的关键差异：分页语法 / 行数限制 / 转义 / 唯一索引。
 *
 * <p>SQL Server 的真实验证在 GitHub Actions 矩阵（{@code MatrixSpringBootTest}）；
 * 这里锁定生成出来的 SQL 片段，避免回归到 MySQL 专有写法。</p>
 */
class SqlDialectPagingTest {

    @TempDir
    java.nio.file.Path dir;

    @Test
    void defaultPagingUsesLimitOffset() {
        assertEquals(" LIMIT ? OFFSET ?", SqlDialect.of("sqlite").pagingClause());
        assertEquals(" LIMIT ? OFFSET ?", SqlDialect.of("mysql").pagingClause());
        assertEquals(" LIMIT ? OFFSET ?", SqlDialect.of("mariadb").pagingClause());
        assertEquals(" LIMIT ? OFFSET ?", SqlDialect.of("pgsql").pagingClause());
    }

    @Test
    void sqlServerPagingUsesOffsetFetch() {
        SqlDialect sqlsrv = SqlDialect.of("sqlsrv");

        assertEquals(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", sqlsrv.pagingClause());
        assertTrue(sqlsrv.offsetBeforeLimit());
    }

    @Test
    void pagingParameterOrderFollowsDialect() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        SqlDialect.of("mysql").bindPaging(ps, 1, 10, 20);
        verify(ps).setLong(1, 10);
        verify(ps).setLong(2, 20);

        PreparedStatement pg = mock(PreparedStatement.class);
        SqlDialect.of("pgsql").bindPaging(pg, 3, 10, 20);
        verify(pg).setLong(3, 10);
        verify(pg).setLong(4, 20);

        // SQL Server: (offset, limit)
        PreparedStatement sqlsrv = mock(PreparedStatement.class);
        SqlDialect.of("sqlsrv").bindPaging(sqlsrv, 5, 10, 20);
        verify(sqlsrv).setLong(5, 20);
        verify(sqlsrv).setLong(6, 10);
    }

    @Test
    void firstRowAndLimitFragments() {
        assertEquals(" LIMIT 1", SqlDialect.of("mysql").firstRowSuffix());
        assertEquals("TOP 1 ", SqlDialect.of("sqlsrv").firstRowPrefix());
        assertEquals("", SqlDialect.of("sqlsrv").firstRowSuffix());

        assertEquals(" LIMIT ?", SqlDialect.of("pgsql").limitSuffix());
        assertEquals("TOP (?) ", SqlDialect.of("sqlsrv").limitPrefix());
        assertTrue(SqlDialect.of("sqlsrv").limitParameterFirst());

        assertEquals(" LIMIT 200", SqlDialect.of("sqlite").limitLiteralSuffix(200));
        assertEquals("TOP 200 ", SqlDialect.of("sqlsrv").limitLiteralPrefix(200));
    }

    @Test
    void pagingBridgeDetectsProductFromConnection() throws Exception {
        try (Connection conn = new org.sqlite.JDBC().connect(
                "jdbc:sqlite:" + dir.resolve("t.sqlite").toAbsolutePath(),
                new java.util.Properties())) {
            assertEquals("sqlite", Paging.dialectOf(conn).name());
            assertEquals(" LIMIT ? OFFSET ?", Paging.clause(conn));
        }
    }

    @Test
    void sqlServerDdlAvoidsMysqlOnlySyntax() throws Exception {
        SchemaInstaller sqlsrv = installer("sqlsrv");
        List<String> ddl = sqlsrv.ddlStatements();

        assertFalse(ddl.isEmpty());
        for (String statement : ddl) {
            assertFalse(statement.contains("`"), "SQL Server DDL 不应含反引号: " + statement);
            assertFalse(statement.contains(" AUTOINCREMENT"), statement);
        }
        assertTrue(ddl.stream().anyMatch(s -> s.contains("INT IDENTITY(1,1) PRIMARY KEY")),
                "主键应使用 IDENTITY");
        assertTrue(ddl.stream().anyMatch(s -> s.contains("NVARCHAR(MAX)")),
                "长文本列应为 NVARCHAR(MAX)");
        assertTrue(ddl.stream().anyMatch(s -> s.contains("BIT NOT NULL")),
                "布尔列应为 BIT");
        // 可空列的唯一索引要带过滤条件（SQL Server 唯一索引把多个 NULL 当重复）
        assertTrue(ddl.stream().anyMatch(s -> s.contains("communications_legacy_key_unique")
                        && s.contains("WHERE [legacy_key] IS NOT NULL")),
                "communications.legacy_key 的唯一索引应为过滤索引");
    }

    @Test
    void postgresDdlQuotesWithDoubleQuotes() throws Exception {
        List<String> ddl = installer("pgsql").ddlStatements();

        for (String statement : ddl) {
            assertFalse(statement.contains("`"), statement);
        }
        assertTrue(ddl.stream().anyMatch(s -> s.contains("\"group\"")),
                "保留字列（group）必须加双引号");
        assertTrue(ddl.stream().anyMatch(s -> s.contains("SERIAL PRIMARY KEY")), "自增主键用 SERIAL");
    }

    @Test
    void mysqlDdlKeepsBackticksAndEngine() throws Exception {
        List<String> ddl = installer("mysql").ddlStatements();

        assertTrue(ddl.stream().anyMatch(s -> s.contains("ENGINE=InnoDB")));
        assertTrue(ddl.stream().anyMatch(s -> s.contains("`group`")));
    }

    private SchemaInstaller installer(String connection) throws Exception {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection(connection);
        settings.getDatabase().setName(dir.resolve("unused.sqlite").toString());

        // 构造 SchemaInstaller 只读取 connection/prefix；DDL 生成不访问数据库。
        return new SchemaInstaller(null, settings);
    }
}
