package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code Translator} 的 DB 覆盖层（Spatie {@code TranslationLoaderManager}）。
 *
 * <p>锁四条源语义：</p>
 * <ol>
 *   <li>同 group/key：<b>DB 覆盖文件</b>；</li>
 *   <li>DB 没提到的键：保留文件值（{@code array_replace_recursive} 语义）；</li>
 *   <li>插件命名空间（{@code ns::group}）：<b>不查库</b>；</li>
 *   <li>{@code invalidateGroup} 之后重新读库（admin 改完立即生效）。</li>
 * </ol>
 */
class TranslatorOverlayTest {

    @TempDir
    Path dir;

    private HikariDataSource ds;
    private DbTranslationLoader db;
    private Translator translator;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        new SchemaInstaller(ds, settings).install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        // install.lock 必须存在，否则 DbTranslationLoader 按「未安装」返回空表
        Files.createFile(paths.installLock());

        db = new DbTranslationLoader(ds, paths, "en");
        translator = new Translator(new TranslationFileLoader(), db, "zh_CN", "en");
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 文件的原始值（作为对照）。 */
    private String fileValue(String group, String key) {
        return new Translator(new TranslationFileLoader(), "zh_CN", "en")
                .trans(group + "." + key, Map.of(), null);
    }

    @Test
    void dbValueOverridesFile() throws Exception {
        String original = fileValue("admin", "i18n.updated");
        assertNotEquals("被改了", original, "前置条件：文件里的值不是测试值");

        insert("admin", "i18n.updated", "zh_CN", "被改了");

        assertEquals("被改了", translator.trans("admin.i18n.updated", Map.of(), null));
    }

    @Test
    void siblingFileKeysSurviveMerge() throws Exception {
        insert("admin", "i18n.updated", "zh_CN", "被改了");

        // 同一个 group 下没被 DB 覆盖的键，仍取文件值
        assertEquals(fileValue("admin", "i18n.deleted"),
                translator.trans("admin.i18n.deleted", Map.of(), null));
    }

    @Test
    void nestedMergeKeepsSiblings() throws Exception {
        String deleted = fileValue("admin", "i18n.deleted");
        insert("admin", "i18n.updated", "zh_CN", "改A");

        // 同 group、不同 key —— 递归合并后两条都在
        assertEquals("改A", translator.trans("admin.i18n.updated", Map.of(), null));
        assertEquals(deleted, translator.trans("admin.i18n.deleted", Map.of(), null));
    }

    @Test
    void namespacedGroupIgnoresDb() throws Exception {
        // 插件命名空间：源里直接 return 文件结果，不查库
        insert("admin", "i18n.updated", "zh_CN", "被改了");

        String namespaced = translator.trans("demo::admin.i18n.updated", Map.of(), null);
        // 命名空间不存在 → 回退 key 本身（说明没有从库里捞到「被改了」）
        assertNotEquals("被改了", namespaced);
    }

    @Test
    void invalidateGroupPicksUpNewValue() throws Exception {
        insert("admin", "i18n.updated", "zh_CN", "第一次");
        assertEquals("第一次", translator.trans("admin.i18n.updated", Map.of(), null));

        update("admin", "i18n.updated", "zh_CN", "第二次");
        // 未失效 → 仍是缓存值
        assertEquals("第一次", translator.trans("admin.i18n.updated", Map.of(), null));

        db.invalidateGroup("admin");
        assertEquals("第二次", translator.trans("admin.i18n.updated", Map.of(), null));
    }

    @Test
    void noInstallLockMeansNoOverlay() throws Exception {
        insert("admin", "i18n.updated", "zh_CN", "被改了");
        // 删掉 install.lock → 覆盖层整体失效
        Files.deleteIfExists(dir.resolve("storage").resolve("install.lock"));

        assertNotEquals("被改了", translator.trans("admin.i18n.updated", Map.of(), null));
        assertTrue(true);
    }

    // ================================================================ 工具

    private void insert(String group, String key, String locale, String text) throws Exception {
        String json = "{\"" + locale + "\":\"" + text + "\"}";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO language_lines (`group`, `key`, `text`) VALUES (?, ?, ?)")) {
            stmt.setString(1, group);
            stmt.setString(2, key);
            stmt.setString(3, json);
            stmt.executeUpdate();
        }
    }

    private void update(String group, String key, String locale, String text) throws Exception {
        String json = "{\"" + locale + "\":\"" + text + "\"}";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE language_lines SET `text` = ? WHERE `group` = ? AND `key` = ?")) {
            stmt.setString(1, json);
            stmt.setString(2, group);
            stmt.setString(3, key);
            stmt.executeUpdate();
        }
    }
}
