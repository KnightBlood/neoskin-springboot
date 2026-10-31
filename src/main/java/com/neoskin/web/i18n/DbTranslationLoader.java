package com.neoskin.web.i18n;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.neoskin.config.AppPaths;
import com.neoskin.support.PhpJson;

/**
 * Spatie 的 {@code TranslationLoaders\Db} + {@code LanguageLine} 的移植：
 * 从 {@code language_lines} 表读数据库翻译覆盖层。
 *
 * <p>源语义（{@code LanguageLine::getTranslationsForGroup}，Spatie 包了
 * {@code rememberForever} 缓存；Java 侧用 {@link ConcurrentHashMap} 起到同一作用）：</p>
 *
 * <ol>
 *   <li>{@code where('group', $group)->get()}；</li>
 *   <li>逐行 {@code getTranslation($locale)} ——
 *       {@code text[$locale] ?? text[$fallback] ?? null}；{@code text} 列是
 *       JSON 对象（Eloquent {@code 'array'} cast）；</li>
 *   <li>{@code group === '*'} → 平铺 {@code lines[$key] = $translation}
 *       （供 {@code __($key)} 的 JSON 翻译路径）；
 *       否则 {@key} 是 {@code a.b.c} 记法 → {@code Arr::set} 嵌套。</li>
 * </ol>
 *
 * <p>两个边界按源 preserved：</p>
 * <ul>
 *   <li>{@code TranslationLoaderManager} 对 {@code QueryException} 容错 —— 这里
 *       对 {@code SQLException}（表不存在等）同样吞成空表；</li>
 *   <li>{@code RedirectToSetup} 在未安装时把 loader 列表清空 —— 这里改成
 *       「{@code storage/install.lock} 不存在 → 直接空表」，连查库都不用。</li>
 * </ul>
 */
public final class DbTranslationLoader {

    /** {@code (group, locale) → 归并后的行}（平铺或嵌套取决于 group）。 */
    private final Map<GroupLocale, Map<String, Object>> cache = new ConcurrentHashMap<>();

    private final DataSource dataSource;

    private final AppPaths paths;

    /** {@code app.fallback_locale}。 */
    private final String fallbackLocale;

    private record GroupLocale(String group, String locale) {
    }

    public DbTranslationLoader(DataSource dataSource, AppPaths paths, String fallbackLocale) {
        this.dataSource = dataSource;
        this.paths = paths;
        this.fallbackLocale = fallbackLocale;
    }

    /** {@code LanguageLine::getTranslationsForGroup($locale, $group)}。 */
    public Map<String, Object> loadTranslations(String locale, String group) {
        if (!Files.exists(paths.installLock())) {
            return Map.of();
        }
        return cache.computeIfAbsent(new GroupLocale(group, locale), key -> loadGroup(key.group(), key.locale()));
    }

    /**
     * {@code LanguageLine::flushGroupCache()} 的等价实现：整组失效。
     * 源是对该行 text 里出现过的每个 locale 逐个 {@code Cache::forget}，
     * 这里做成 group 级超集 —— 效果一致（下次按组读取时重建缓存），
     * 而且不需要模型记住「哪些 locale 在缓存里」。
     */
    public void invalidateGroup(String group) {
        cache.keySet().removeIf(key -> key.group().equals(group));
    }

    // ================================================================= 内部

    private Map<String, Object> loadGroup(String group, String locale) {
        Map<String, Object> lines = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            // 列名是 MySQL 保留字（key/text/group），用方言 quote 跨库转义
            var dialect = com.neoskin.db.Paging.dialectOf(conn);
            String sql = "SELECT " + dialect.quote("key") + ", " + dialect.quote("text")
                    + " FROM language_lines WHERE " + dialect.quote("group") + " = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, group);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        Map<String, String> text = decodeText(rs.getString(2));
                        if (text == null) {
                            continue;
                        }
                        String value = getTranslation(text, locale);
                        if (value != null) {
                            if ("*".equals(group)) {
                                lines.put(key, value);
                            } else {
                                setNested(lines, key, value);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // TranslationLoaderManager 的 QueryException 分支 → 空表
            return Map.of();
        }
        return lines;
    }

    /** {@code getTranslation($locale)}：text[$locale] ?? text[fallbackLocale] ?? null。 */
    String getTranslation(Map<String, String> text, String locale) {
        if (text.containsKey(locale)) {
            return text.get(locale);
        }
        return text.get(fallbackLocale);
    }

    /** 非法 JSON / 顶层不是对象 → null（模型层数据错误，跳过行）。 */
    Map<String, String> decodeText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Object decoded;
        try {
            decoded = PhpJson.decode(text);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(decoded instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() != null) {
                out.put(String.valueOf(e.getKey()),
                        e.getValue() instanceof String s ? s : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** {@code Arr::set($lines, $key, $value)}：{@code a.b.c} → 嵌套 Map。 */
    static void setNested(Map<String, Object> target, String key, Object value) {
        java.util.List<String> segments = new java.util.ArrayList<>();
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                segments.add(seg.toString());
                seg.setLength(0);
            } else {
                seg.append(c);
            }
        }
        segments.add(seg.toString());

        Map<String, Object> cursor = target;
        for (int i = 0; i < segments.size() - 1; i++) {
            String s = segments.get(i);
            Object next = cursor.get(s);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(s, next);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) next;
            cursor = casted;
        }
        cursor.put(segments.get(segments.size() - 1), value);
    }

    /** Locale 数组 {@code localeArray}：[$locale, $fallback] 去重（默认回退顺序）。 */
    static List<String> localeArray(String locale, String fallbackLocale) {
        List<String> locales = new java.util.ArrayList<>();        if (locale != null && !locale.isEmpty()) {
            locales.add(locale);
        }
        if (fallbackLocale != null && !fallbackLocale.isEmpty() && !locales.contains(fallbackLocale)) {
            locales.add(fallbackLocale);
        }
        return locales;
    }
}
