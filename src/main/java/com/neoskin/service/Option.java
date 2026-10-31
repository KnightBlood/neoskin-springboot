package com.neoskin.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neoskin.config.AppPaths;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.support.PhpVarExport;

/**
 * 站点选项服务，对应源项目 {@code App\Services\Option}。
 *
 * <p>行为必须逐条复刻，否则迁移后会「设置项突然变了」：
 * <ol>
 *   <li>优先读 {@code storage/options.php} 缓存（PHP 版 {@code options:cache} 生成的）；
 *   <li>没有缓存且 {@code storage/install.lock} 不存在（未安装）→ 全部为空；
 *   <li>否则从 {@code options} 表加载全部键值；
 *   <li>{@link #get} 在缺失时回退到 {@link OptionDefaults}，<b>并把默认值写回数据库</b>；
 *   <li>{@link #get} 会把 {@code 'true'} / {@code '(true)'} / {@code 'false'} /
 *       {@code '(false)'} / {@code 'null'} / {@code '(null)'} 强制转换成布尔/空值，
 *       {@code raw = true} 时不做转换；
 *   <li>{@link #set} 同时更新内存和数据库；数据库写入失败（例如值为 null 触发
 *       NOT NULL 约束）只吞掉异常，内存仍然生效——这是 PHP 版的原始行为。
 * </ol>
 *
 * <p>线程安全：PHP 是单请求进程，Java 是多线程。这里用 {@link ConcurrentHashMap} 保证
 * 读写安全；{@code set} 的「先内存后数据库」顺序与 PHP 一致，因此并发下最多出现
 * 短暂的内存/数据库不一致，与 PHP 的最终一致性等价。
 */
@Service
public class Option {

    private static final Logger log = LoggerFactory.getLogger(Option.class);

    private final DataSource dataSource;
    private final SchemaInstaller schema;
    private final AppPaths paths;

    /** 与 PHP 的 {@code $items} 对应，保持插入顺序以便 options:cache 输出稳定。 */
    private final Map<String, String> items = new ConcurrentHashMap<>();

    /** 是否已从缓存/数据库装载过。PHP 在构造函数里一次性装载，这里延迟到首次使用。 */
    private volatile boolean loaded;

    public Option(DataSource dataSource, SchemaInstaller schema, AppPaths paths) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.paths = paths;
    }

    // ------------------------------------------------------------------ 读

    /** 对应 {@code option($key)}：返回原始字符串或转换后的布尔/null。 */
    public Object get(String key) {
        return get(key, null, false);
    }

    /** 对应 {@code option($key, $default)}。 */
    public Object get(String key, Object defaultValue) {
        return get(key, defaultValue, false);
    }

    /**
     * 对应 {@code Option::get($key, $default, $raw)}。
     *
     * @param key          选项名
     * @param defaultValue 缺失时的返回值（优先于 config 默认值）
     * @param raw          true 时不做 {@code 'true'}/{@code 'null'} 等类型转换
     */
    public Object get(String key, Object defaultValue, boolean raw) {
        ensureLoaded();

        if (!items.containsKey(key) && OptionDefaults.has(key)) {
            set(key, OptionDefaults.get(key));
        }

        String value = items.containsKey(key) ? items.get(key)
                : (defaultValue == null ? null : String.valueOf(defaultValue));
        if (NULL_SENTINEL.equals(value)) {
            value = null;
        }
        if (raw) {
            return value;
        }
        return coerce(value);
    }

    /** 只取字符串，不做布尔转换；缺失时返回 {@code defaultValue}。 */
    public String getString(String key, String defaultValue) {
        Object v = get(key, defaultValue, true);
        return v == null ? null : v.toString();
    }

    /** 取布尔值，语义等同 PHP 的 {@code (bool) option($key)} 但保留 {@code 'null'} → false。 */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = get(key, defaultValue);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        return Boolean.parseBoolean(v.toString());
    }

    /** 取整数，转换失败时返回 {@code defaultValue}（PHP 会得到 0，这里更安全）。 */
    public int getInt(String key, int defaultValue) {
        Object v = get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 对应 {@code option_localized($key, $default, $raw)}：
     * {@code option($key.'_'.config('app.locale'), option($key, $default), $raw)}。
     *
     * <p>源项目的 {@code config('app.locale')} 固定为 {@code zh_CN}，但请求期间
     * {@code DetectLanguagePrefer} 会调用 {@code app()->setLocale()}，所以这里用
     * {@link #currentLocale()} 而不是常量。
     */
    public Object getLocalized(String key) {
        return getLocalized(key, null, false);
    }

    public Object getLocalized(String key, Object defaultValue) {
        return getLocalized(key, defaultValue, false);
    }

    public Object getLocalized(String key, Object defaultValue, boolean raw) {
        // 逐字对应 option($key.'_'.locale, option($key, $default), $raw)：
        // 内层 option() 不带 raw，因此外层拿到的「默认值」是已转换过的值
        // （可能是 Boolean 或 null），这一点必须保留，否则 raw 模式下返回值类型会变。
        Object fallback = get(key, defaultValue);
        String localized = key + "_" + currentLocale();
        if (!items.containsKey(localized) && OptionDefaults.has(localized)) {
            set(localized, OptionDefaults.get(localized));
        }
        Object value = items.containsKey(localized) ? items.get(localized) : fallback;
        if (NULL_SENTINEL.equals(value)) {
            value = null;
        }
        if (raw) {
            return value;
        }
        return value instanceof String s ? coerce(s) : value;
    }

    /**
     * 全部选项的原始值，对应 {@code Option::all()}。
     *
     * <p>返回值是「未转换」的原始字符串（PHP 的 {@code $items->all()} 同样返回原始值），
     * 因此这里刻意<b>不</b>做 {@code 'true'} → {@code true} 的转换。值为 {@code null}
     * 的键（PHP 里 {@code option_value = null} 写库失败但内存生效的情形）同样保留。
     */
    public Map<String, Object> all() {
        ensureLoaded();
        Map<String, Object> plain = new LinkedHashMap<>();
        items.forEach((k, v) -> plain.put(k, NULL_SENTINEL.equals(v) ? null : v));
        return plain;
    }

    // ------------------------------------------------------------------ 写

    /** 对应 {@code Option::set($key, $value)}。 */
    public void set(String key, Object value) {
        ensureLoaded();
        String text = value == null ? null : value instanceof Boolean b
                ? (b ? "true" : "false")
                : String.valueOf(value);
        if (text == null) {
            // PHP 会先写内存再写库；option_value 是 NOT NULL，写库必然失败并被吞掉。
            // ConcurrentHashMap 不允许 null value，这里用哨兵以外的方式模拟：
            // 记录为「存在但为空」，通过 get 返回 null 实现同样语义。
            items.put(key, NULL_SENTINEL);
        } else {
            items.put(key, text);
        }
        persist(key, text);
    }

    /** 批量设置，对应 {@code option([...])}。 */
    public void setAll(Map<String, ?> values) {
        values.forEach(this::set);
    }

    /** 删除某个选项（仅供测试/管理命令使用，源项目没有此方法）。 */
    public void remove(String key) {
        ensureLoaded();
        items.remove(key);
    }

    // ------------------------------------------------------------------ 缓存文件

    /** {@code storage/options.php} 的路径。 */
    public Path cachePath() {
        return paths.optionsCache();
    }

    /** {@code storage/install.lock} 的路径。 */
    public Path installLockPath() {
        return paths.installLock();
    }

    /** 是否已安装（等价于 {@code file_exists(storage/install.lock)}）。 */
    public boolean isInstalled() {
        return Files.exists(installLockPath());
    }

    /**
     * 对应 {@code php artisan options:cache}：把当前全部选项写入
     * {@code storage/options.php}，格式与 PHP 的 {@code var_export()} 完全一致。
     */
    public void writeCache() {
        ensureLoaded();
        Path path = cachePath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, PhpVarExport.toPhpFile(all()), StandardCharsets.UTF_8);
            log.info("options cached to {}", path);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write options cache: " + path, e);
        }
    }

    /** 对应 {@code php artisan options:clear}（删除缓存文件）。 */
    public void clearCache() {
        try {
            Files.deleteIfExists(cachePath());
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete options cache", e);
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 占位符：表示「键存在但值为 null」，因为 ConcurrentHashMap 不允许 null 值。 */
    private static final String NULL_SENTINEL = "\u0000__NULL__\u0000";

    /** 当前请求的语言，由 DetectLanguagePrefer 中间件设置。 */
    private static final ThreadLocal<String> LOCALE = ThreadLocal.withInitial(() -> "zh_CN");

    /** 对应 {@code config('app.locale')}（请求期间会被中间件改写）。 */
    public static String currentLocale() {
        return LOCALE.get();
    }

    public static void setCurrentLocale(String locale) {
        LOCALE.set(locale == null || locale.isBlank() ? "zh_CN" : locale);
    }

    public static void clearCurrentLocale() {
        LOCALE.remove();
    }

    private static Object coerce(String value) {
        if (value == null) {
            return null;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "true":
            case "(true)":
                return Boolean.TRUE;
            case "false":
            case "(false)":
                return Boolean.FALSE;
            case "null":
            case "(null)":
                return null;
            default:
                return value;
        }
    }

    /** 惰性装载，对应 PHP 构造函数的三段逻辑。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            load();
            loaded = true;
        }
    }

    private void load() {
        Path cache = cachePath();
        if (Files.exists(cache)) {
            try {
                String text = Files.readString(cache, StandardCharsets.UTF_8);
                PhpVarExport.parseArray(text).forEach((k, v) -> items.put(k, stringify(v)));
                log.debug("loaded {} options from cache {}", items.size(), cache);
                return;
            } catch (IOException | RuntimeException e) {
                // PHP 版遇到坏缓存会直接抛错；这里记录后回退到数据库更利于运维。
                log.warn("failed to read options cache {}, falling back to database", cache, e);
            }
        }

        if (!isInstalled()) {
            // 未安装：与 PHP 一致，保持空集合，get 时回退到 config 默认值
            return;
        }

        String sql = "SELECT " + q("option_name") + ", " + q("option_value")
                + " FROM " + q(schema.table("options"));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString(2);
                items.put(rs.getString(1), value == null ? NULL_SENTINEL : value);
            }
            log.debug("loaded {} options from database", items.size());
        } catch (SQLException e) {
            // options 表可能尚未创建（首次启动），此时等同「未安装」
            log.debug("options table not readable yet: {}", e.getMessage());
        }
    }

    private void persist(String key, String value) {
        String table = q(schema.table("options"));
        String update = "UPDATE " + table + " SET " + q("option_value") + " = ? WHERE "
                + q("option_name") + " = ?";
        String insert = "INSERT INTO " + table + " (" + q("option_name") + ", "
                + q("option_value") + ") VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, value);
                ps.setString(2, key);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // 与 PHP 一致：写库失败不影响内存中的值（典型场景是未安装、表不存在、
            // 或者值为 null 触发 NOT NULL 约束）
            log.debug("failed to persist option {}: {}", key, e.getMessage());
        }
    }

    private static String stringify(Object value) {
        if (value == null) {
            return NULL_SENTINEL;
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(value);
    }

    private String q(String identifier) {
        return schema.dialect().quote(identifier);
    }
}
