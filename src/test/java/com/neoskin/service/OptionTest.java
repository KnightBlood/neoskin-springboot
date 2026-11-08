package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link Option} 的回归测试，对照源项目 {@code app/Services/Option.php} 的每一条行为。
 *
 * <p>关键点是「迁移后设置项不能变」：默认值表、类型转换、缺失键写回、
 * {@code storage/options.php} 缓存优先级，四条都必须逐字一致。
 */
class OptionTest {

    // ------------------------------------------------------------------ 测试脚手架

    /** 一个装好数据库 + 目录结构的测试站点。 */
    private static final class Site implements AutoCloseable {
        final Path dir;
        final HikariDataSource ds;
        final NeoSkinSettings settings;
        final AppPaths paths;
        final SchemaInstaller schema;

        Site(Path dir) throws IOException {
            this.dir = dir;
            Files.createDirectories(dir.resolve("storage"));
            NeoSkinSettings s = new NeoSkinSettings();
            s.getDatabase().setConnection("sqlite");
            s.getDatabase().setName(dir.resolve("db.sqlite").toAbsolutePath().toString());
            this.settings = s;
            this.ds = dataSource(s.getDatabase().getName());
            this.schema = new SchemaInstaller(ds, s);
            this.schema.install();
            this.paths = new AppPaths(dir, s);
        }

        /** 写 install.lock，模拟「已安装」。 */
        void install() throws IOException {
            Files.writeString(paths.installLock(), "");
        }

        Option newOption() {
            return new Option(ds, schema, paths);
        }

        /** 直接读数据库里的选项值，用来验证 set() 是否落库。 */
        String rawDbValue(String key) throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT option_value FROM options WHERE option_name = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }

        int dbRowCount() throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM options");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }

        @Override
        public void close() {
            ds.close();
        }
    }

    private static HikariDataSource dataSource(String file) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    // ------------------------------------------------------------------ 默认值表

    @Test
    void defaultsMatchConfigOptionsPhp() {
        Map<String, String> defaults = OptionDefaults.all();
        // 实测 config/options.php 共 54 个键；max_texture_width 不在这张表里，
        // 它由 2025_06_29_064944 的 migration 单独写入（见 DataMigrator）。
        assertEquals(54, defaults.size(), "config/options.php 共 54 个键");
        assertFalse(defaults.containsKey("max_texture_width"));

        // 抽查几个容易写错的
        assertEquals("NeoSkin", defaults.get("site_name"));
        assertEquals("official", defaults.get("player_name_rule"));
        assertEquals("10,100", defaults.get("sign_score"));
        assertEquals("31536000", defaults.get("cache_expire_time"));
        assertEquals("cyan", defaults.get("navbar_color"));
        assertEquals("dark-maroon", defaults.get("sidebar_color"));
        assertEquals("app/favicon.ico", defaults.get("favicon_url"));
        assertEquals("403", defaults.get("status_code_for_private"));
        assertEquals("Welcome to NeoSkin {version}!", defaults.get("announcement"));
        assertEquals("", defaults.get("version"));

        // 键顺序与源文件一致（影响 options:cache 生成文件的 diff）
        List<String> keys = List.copyOf(defaults.keySet());
        assertEquals("site_url", keys.get(0));
        assertEquals("site_name", keys.get(1));
        assertEquals("sidebar_color", keys.get(keys.size() - 1));
    }

    // ------------------------------------------------------------------ 读取与转换

    @Test
    void coercesBooleanAndNullStrings(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();

            option.set("t1", "true");
            option.set("t2", "(true)");
            option.set("f1", "false");
            option.set("f2", "(false)");
            option.set("n1", "null");
            option.set("n2", "(null)");
            option.set("plain", "hello");
            option.set("num", "42");
            option.set("upper", "TRUE");

            assertEquals(Boolean.TRUE, option.get("t1"));
            assertEquals(Boolean.TRUE, option.get("t2"));
            assertEquals(Boolean.FALSE, option.get("f1"));
            assertEquals(Boolean.FALSE, option.get("f2"));
            assertNull(option.get("n1"));
            assertNull(option.get("n2"));
            assertEquals("hello", option.get("plain"));
            assertEquals("42", option.get("num"), "非布尔串保持原样，不做数字转换");
            // PHP 用 strtolower()，因此大小写不敏感
            assertEquals(Boolean.TRUE, option.get("upper"));
        }
    }

    @Test
    void rawModeSkipsCoercion(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();
            option.set("flag", "true");

            assertEquals("true", option.get("flag", null, true));
            assertEquals(Boolean.TRUE, option.get("flag", null, false));
            assertEquals("true", option.getString("flag", null));
        }
    }

    @Test
    void missingKeyFallsBackToConfigDefaultAndWritesBack(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();

            // 空库：读 site_name 应返回 config/options.php 的默认值
            assertEquals("NeoSkin", option.get("site_name"));
            // 且必须写回数据库（PHP 的 get() 里 set() 的副作用）
            assertEquals("NeoSkin", site.rawDbValue("site_name"));

            // 第二次读不再重复写，值不变
            assertEquals("NeoSkin", option.get("site_name"));
            assertEquals(1, countRows(site, "site_name"));
        }
    }

    @Test
    void missingKeyOutsideDefaultsReturnsDefaultArgument(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();

            assertNull(option.get("no_such_option"));
            assertEquals("fallback", option.get("no_such_option", "fallback"));
            // 不在 config/options.php 里的键不会被写回
            assertNull(site.rawDbValue("no_such_option"));
        }
    }

    @Test
    void notInstalledSiteIgnoresDatabaseRows(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            // 库里先塞一行（模拟「表存在但还没写 install.lock」的异常状态）
            try (Connection conn = site.ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO options (option_name, option_value) VALUES ('site_name', 'From DB')")) {
                ps.executeUpdate();
            }

            // 不写 install.lock
            Option option = site.newOption();

            assertFalse(option.isInstalled());
            // 未安装时不读库，读到的必须是 config 默认值
            assertEquals("NeoSkin", option.get("site_name"));
        }
    }

    @Test
    void setPersistsToDatabase(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();

            option.set("site_name", "My Skin Server");
            assertEquals("My Skin Server", option.get("site_name"));
            assertEquals("My Skin Server", site.rawDbValue("site_name"));

            // 覆盖写必须走 UPDATE 而不是插入第二行
            option.set("site_name", "Renamed");
            assertEquals("Renamed", site.rawDbValue("site_name"));
            assertEquals(1, countRows(site, "site_name"));

            // 布尔值写入时序列化为 PHP 的 'true' / 'false'
            option.set("force_ssl", true);
            assertEquals("true", site.rawDbValue("force_ssl"));
            option.set("force_ssl", false);
            assertEquals("false", site.rawDbValue("force_ssl"));
        }
    }

    @Test
    void setAllAppliesEveryEntry(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();
            option.setAll(Map.of("site_name", "A", "site_description", "B"));

            assertEquals("A", option.get("site_name"));
            assertEquals("B", option.get("site_description"));
            assertEquals("A", site.rawDbValue("site_name"));
        }
    }

    @Test
    void nullValueStaysInMemoryButNotInDatabase(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();

            // option_value 是 NOT NULL，写 null 会失败——PHP 吞掉异常，内存里保留 null
            option.set("weird", null);
            assertNull(option.get("weird", null, true));
            assertNull(option.get("weird"));
            assertNull(site.rawDbValue("weird"), "库里不应出现该行");
        }
    }

    // ------------------------------------------------------------------ 本地化

    @Test
    void localizedFallsBackToBaseOption(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();
            option.set("site_name", "Base Name");
            option.set("site_name_zh_CN", "中文名");
            option.set("site_name_en", "English Name");

            Option.setCurrentLocale("zh_CN");
            assertEquals("中文名", option.getLocalized("site_name"));

            Option.setCurrentLocale("en");
            assertEquals("English Name", option.getLocalized("site_name"));

            // 没有对应语言键时回退到基础键
            Option.setCurrentLocale("ru_RU");
            assertEquals("Base Name", option.getLocalized("site_name"));
        } finally {
            Option.clearCurrentLocale();
        }
    }

    @Test
    void localizedDefaultLocaleIsZhCn(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();
            option.set("site_description", "base");
            option.set("site_description_zh_CN", "简体");

            // 没有显式设置 locale 时默认 zh_CN（config/app.php 的 'locale'）
            assertEquals("zh_CN", Option.currentLocale());
            assertEquals("简体", option.getLocalized("site_description"));
        }
    }

    // ------------------------------------------------------------------ 缓存文件

    @Test
    void cacheFileTakesPrecedenceOverDatabase(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option writer = site.newOption();
            writer.set("site_name", "From DB");
            writer.writeCache();

            // 数据库里改成别的值，但缓存文件仍在 → PHP 版会读缓存
            try (Connection conn = site.ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE options SET option_value = 'From DB v2' WHERE option_name = 'site_name'")) {
                ps.executeUpdate();
            }

            Option reader = site.newOption();
            assertEquals("From DB", reader.get("site_name"), "有 options.php 缓存时必须优先读缓存");

            // 清掉缓存后才读数据库
            reader.clearCache();
            Option fresh = site.newOption();
            assertEquals("From DB v2", fresh.get("site_name"));
        }
    }

    @Test
    void writeCacheProducesPhpRequirableFile(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            site.install();
            Option option = site.newOption();
            option.set("site_name", "My \"Skin\" Server's");
            option.set("force_ssl", "true");
            option.writeCache();

            Path cache = site.paths.optionsCache();
            assertTrue(Files.exists(cache));
            String text = Files.readString(cache, StandardCharsets.UTF_8);
            assertTrue(text.startsWith("<?php\n"), "PHP 必须能直接 require");
            assertTrue(text.contains("// This is auto-generated. DO NOT edit manually."));
            // 单引号转义：' -> \'
            assertTrue(text.contains("'site_name' => 'My \"Skin\" Server\\'s',"), text);

            // 往返：重新装载后值一致
            Option reloaded = site.newOption();
            assertEquals("My \"Skin\" Server's", reloaded.get("site_name"));
        }
    }

    @Test
    void cacheWithoutInstallLockIsStillHonoured(@TempDir Path dir) throws Exception {
        try (Site site = new Site(dir)) {
            // 注意：不写 install.lock。PHP 的构造函数先判断缓存文件是否存在，
            // 因此只要有缓存就会读，与是否安装无关。
            Files.writeString(site.paths.optionsCache(),
                    "<?php\nreturn array (\n  'site_name' => 'Cached Only',\n);\n",
                    StandardCharsets.UTF_8);

            Option option = site.newOption();
            assertEquals("Cached Only", option.get("site_name"));
        }
    }

    // ------------------------------------------------------------------ 工具

    private static int countRows(Site site, String key) throws Exception {
        try (Connection conn = site.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM options WHERE option_name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
