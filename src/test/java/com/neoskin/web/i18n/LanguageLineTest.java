package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.support.PhpDate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link LanguageLine}（写侧模型）的回归：
 *
 * <ol>
 *   <li>CRUD 往返：create → find → setTranslation → save → getTranslation；</li>
 *   <li>{@code paginate(10)} 的分页字段；</li>
 *   <li>{@code flushGroupCache}：写/删之后 {@link DbTranslationLoader} 读到的
 *       行必须立即反映新数据（源用 {@code Cache::forget}，这里整组失效）；</li>
 *   <li>时间戳：insert 补全 created_at/updated_at，update 只动 updated_at。</li>
 * </ol>
 */
class LanguageLineTest {

    @TempDir
    Path dir;

    private HikariDataSource ds() throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }

    private AppPaths appPaths() {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        return new AppPaths(dir, settings);
    }

    /** 计 flush 钩子被调用的次数（save/delete 各一次）。 */
    private static final AtomicInteger FLUSHES = new AtomicInteger();

    private void flushSink(String group) {
        FLUSHES.incrementAndGet();
    }

    @Test
    void crudRoundTrip() throws Exception {
        try (HikariDataSource ds = ds()) {
            new SchemaInstaller(ds, settings()).install();
            LanguageLine line = new LanguageLine();
            line.setGroup("front-end");
            line.setKey("general.confirm");
            line.setTranslation("zh_CN", "确定");
            line.save(ds, "Asia/Shanghai", this::flushSink);

            LanguageLine found = LanguageLine.find(ds, line.getId());
            assertEquals("front-end", found.getGroup());
            assertEquals("general.confirm", found.getKey());
            assertEquals("确定", found.getText().get("zh_CN"));
            // created_at / updated_at 都写了（Y-m-d H:i:s）
            assertTrue(found.getCreatedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
            assertEquals(found.getCreatedAt(), found.getUpdatedAt());

            // setTranslation：已有 locale 覆盖、新 locale 追加
            found.setTranslation("zh_CN", "好的");
            found.setTranslation("en", "OK");
            found.save(ds, "Asia/Shanghai", this::flushSink);
            assertEquals("好的", found.getTranslation("zh_CN", "en"));
            assertEquals("OK", found.getText().get("en"));
            // update 后 updated_at 单独维护（两表字段仍按 PHP 形态）
            assertTrue(found.getUpdatedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));

            // fallback：locale 不在场时用 fallback
            LanguageLine other = new LanguageLine();
            other.setGroup("auth");
            other.setKey("check.banned");
            other.setTranslation("en", "Banned.");
            other.save(ds, "Asia/Shanghai", this::flushSink);
            assertEquals("Banned.", other.getTranslation("zh_CN", "en"));
            assertNull(other.getTranslation("zh_CN", "fr_FR"));

            found.delete(ds, this::flushSink);
            assertNull(LanguageLine.find(ds, found.getId()));
        }
    }

    @Test
    void paginationFields() throws Exception {
        try (HikariDataSource ds = ds()) {
            new SchemaInstaller(ds, settings()).install();
            for (int i = 1; i <= 25; i++) {
                LanguageLine line = new LanguageLine();
                line.setGroup("g" + i);
                line.setKey("k" + i);
                line.setTranslation("en", "v" + i);
                line.save(ds, "Asia/Shanghai", this::flushSink);
            }

            LanguageLine.Page first = LanguageLine.list(ds, 1);
            assertEquals(25, first.total());
            assertEquals(10, first.perPage());
            assertEquals(3, first.lastPage());
            assertEquals(1, first.from());
            assertEquals(10, first.to());
            assertEquals(1, first.rows().get(0).getId());

            LanguageLine.Page last = LanguageLine.list(ds, 3);
            assertEquals(21, last.from());
            assertEquals(25, last.to());
            assertEquals(5, last.rows().size());

            LanguageLine.Page empty = LanguageLine.list(ds, 4);
            assertEquals(0, empty.rows().size());
        }
    }

    /** flush 钩子打通 DbTranslationLoader：写入立刻可见，整组失效生效。 */
    @Test
    void flushFeedsDbLoader() throws Exception {
        HikariDataSource ds = ds();
        try {
            new SchemaInstaller(ds, settings()).install();
            AppPaths paths = appPaths();
            Files.createDirectories(paths.storage());
            Files.createFile(paths.installLock());

            DbTranslationLoader loader = new DbTranslationLoader(ds, paths, "en");
            // 空组：缓存一张空表
            assertEquals(Map.of(), loader.loadTranslations("zh_CN", "front-end"));

            // 写一行带 group 的行 → flush → loader 缓存重建后能读到
            LanguageLine line = new LanguageLine();
            line.setGroup("front-end");
            line.setKey("general.confirm");
            line.setTranslation("zh_CN", "确定");
            line.save(ds, "Asia/Shanghai", loader::invalidateGroup);

            Map<String, Object> group = loader.loadTranslations("zh_CN", "front-end");
            assertEquals("确定",
                    ((Map<?, ?>) group.get("general")).get("confirm"));

            // 删除 → flush → 缓存里也跟着消失
            line.delete(ds, loader::invalidateGroup);
            assertEquals(Map.of(), loader.loadTranslations("zh_CN", "front-end"));
        } finally {
            ds.close();
        }
    }

    private NeoSkinSettings settings() {
        NeoSkinSettings s = new NeoSkinSettings();
        s.getDatabase().setConnection("sqlite");
        s.getDatabase().setName(dir.resolve("test.sqlite").toString());
        return s;
    }

    /** created_at 必须按应用时区、落在 save 前后的时间窗内（分钟精度宽松窗口）。 */
    @Test
    void timestampsUseAppTimezone() throws Exception {
        try (HikariDataSource ds = ds()) {
            new SchemaInstaller(ds, settings()).install();
            String before = PhpDate.now("Asia/Shanghai");
            LanguageLine line = new LanguageLine();
            line.setGroup("auth");
            line.setKey("check");
            line.setTranslation("en", "x");
            line.save(ds, "Asia/Shanghai", this::flushSink);
            String after = PhpDate.now("Asia/Shanghai");

            LanguageLine reloaded = LanguageLine.find(ds, line.getId());
            // Y-m-d H:i:s 是字典序可比的
            assertTrue(before.compareTo(reloaded.getCreatedAt()) <= 0,
                    "created_at [早于 save 开始时间]: " + reloaded.getCreatedAt() + " < " + before);
            assertTrue(after.compareTo(reloaded.getCreatedAt()) >= 0,
                    "created_at [晚于 save 结束时间]: " + reloaded.getCreatedAt() + " > " + after);
        }
    }
}
