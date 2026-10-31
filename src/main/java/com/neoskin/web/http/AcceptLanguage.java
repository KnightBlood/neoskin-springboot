package com.neoskin.web.http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Symfony {@code Request::getLanguages()} / {@code getPreferredLanguage()} /
 * {@code formatLocale()} 的移植。
 *
 * <p>{@code DetectLanguagePrefer} 只用到 {@code getPreferredLanguage()}（不带参数），
 * 也就是「{@code Accept-Language} 里 q 值最高的那一项，规整成 {@code zh_CN} 形式」。
 * 看起来只取第一个就够了，但必须连同 {@code AcceptHeader} 的<b>去重与排序</b>一起搬：
 * {@code "en;q=0.9, en;q=0.8"} 在原版里只会剩下一个 {@code en}，
 * 而 {@code "zh-CN;q=0, en"} 会因为 q=0 排到后面而返回 {@code en}。
 *
 * <p>{@code formatLocale()} 的正则与分支必须逐字对齐，边界已用 PHP 8.5 实测
 * （{@code probe_locale2.php}）：
 * <pre>
 * "zh-CN"      → zh_CN
 * "zh-Hans-CN" → zh_Hans_CN
 * "zh-CN-"     → zh            ← 尾部裸连字符匹配 (?:-(.+))? 之外的残留，只剩语言码
 * "i-cherokee" → cherokee      ← i- 前缀被剥掉
 * "en-GB-oed"  → en_GB         ← 变体后缀被丢弃
 * "a"          → a             ← 不匹配正则，整串原样（已转小写）
 * ""           → ""
 * </pre>
 */
public final class AcceptLanguage {

    /**
     * {@code getLanguageComponents()} 用的正则。
     *
     * <p>对应 PHP 的
     * {@code ^([a-zA-Z]{2,3}|i-[a-zA-Z]{5,})(?:-([a-zA-Z]{4}))?(?:-([a-zA-Z]{2}))?(?:-(.+))?$}。
     * Java 的 {@code Matcher.matches()} 自带 {@code ^...$} 语义，这里保留锚点便于逐字对照。
     */
    private static final Pattern LOCALE_PATTERN = Pattern.compile(
            "^(?<language>[a-zA-Z]{2,3}|i-[a-zA-Z]{5,})(?:-(?<script>[a-zA-Z]{4}))?"
                    + "(?:-(?<region>[a-zA-Z]{2}))?(?:-(?<variant>.+))?$");

    /**
     * Symfony {@code Request::create()} 给缺失的 {@code Accept-Language} 填的默认值。
     *
     * <p>语料用例 {@code accept.absent} 的期望值（{@code [en_US, en]}）就是这个默认
     * 头算出来的，<b>不是</b>「没有头 → 空列表」。真实 HTTP 请求里没有这个头时，
     * 适配器仍应传 {@code null}（即空列表），否则会把「客户端没说要什么语言」
     * 误判成「客户端要美式英语」。这个常量只用于复现 oracle 的构造方式。
     */
    public static final String DEFAULT_ACCEPT_LANGUAGE = "en-us,en;q=0.5";

    private AcceptLanguage() {
    }

    /**
     * {@code Request::getLanguages()}：按 q 值降序的语言列表，每项已 {@code formatLocale}。
     *
     * @param acceptLanguage 头值；{@code null} 表示「没有这个头」。
     *                       注意 Symfony 的 {@code Request::create()} 会给缺失的头
     *                       填 {@code en-us,en;q=0.5}，调用方要复现那个默认值
     *                       （见 {@link #DEFAULT_ACCEPT_LANGUAGE}）。
     */
    public static List<String> getLanguages(String acceptLanguage) {
        List<String> languages = new ArrayList<>();
        for (AcceptHeaderItem item : AcceptHeader.fromString(acceptLanguage).all()) {
            languages.add(formatLocale(item.getValue()));
        }
        return HeaderUtils.unique(languages);
    }

    /** {@code Request::getPreferredLanguage()}（不带候选列表）：{@code getLanguages()} 的第一项。 */
    public static String getPreferredLanguage(String acceptLanguage) {
        List<String> languages = getLanguages(acceptLanguage);
        return languages.isEmpty() ? null : languages.get(0);
    }

    /**
     * {@code Request::getPreferredLanguage($locales)}：在给定候选里挑最匹配的一个。
     *
     * <p>虽然 {@code DetectLanguagePrefer} 没用这个重载，但语言下拉菜单与
     * {@code MarketController} 后续会用到，这里一并移植并用语料锁定。
     */
    public static String getPreferredLanguage(String acceptLanguage, List<String> locales) {
        List<String> preferred = getLanguages(acceptLanguage);
        if (locales == null || locales.isEmpty()) {
            return preferred.isEmpty() ? null : preferred.get(0);
        }
        List<String> normalized = new ArrayList<>();
        for (String locale : locales) {
            normalized.add(formatLocale(locale));
        }
        if (preferred.isEmpty()) {
            return normalized.get(0);
        }

        List<String> combinations = new ArrayList<>();
        for (String locale : preferred) {
            combinations.addAll(getLanguageCombinations(locale));
        }
        for (String combination : combinations) {
            for (String locale : normalized) {
                if (locale.startsWith(combination)) {
                    return locale;
                }
            }
        }
        return normalized.get(0);
    }

    /**
     * {@code Request::formatLocale()}：把任意语言标签规整成 {@code language_Script_REGION}。
     *
     * @param locale 原始标签，{@code null} 按空串处理
     */
    public static String formatLocale(String locale) {
        String[] parts = getLanguageComponents(locale);
        List<String> kept = new ArrayList<>(3);
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                kept.add(part);
            }
        }
        return String.join("_", kept);
    }

    /**
     * {@code getLanguageComponents()}：返回 {@code [language, script, region]}。
     *
     * <p>不匹配正则时返回 {@code [整串小写, null, null]}——这正是 {@code "a"} → {@code "a"}、
     * {@code "*"} → {@code "*"} 的来源。
     */
    static String[] getLanguageComponents(String locale) {
        String normalized = (locale == null ? "" : locale).replace('_', '-').toLowerCase(Locale.ROOT);
        Matcher m = LOCALE_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return new String[] {normalized, null, null};
        }
        String language = m.group("language");
        if (language.startsWith("i-")) {
            // 不在 ISO 639 里、也不是任何语言的变体，用 i- 前缀注册，例如 i-cherokee
            language = language.substring(2);
        }
        String script = m.group("script");
        if (script != null) {
            script = script.substring(0, 1).toUpperCase(Locale.ROOT)
                    + script.substring(1).toLowerCase(Locale.ROOT);
        }
        String region = m.group("region");
        if (region != null) {
            region = region.toUpperCase(Locale.ROOT);
        }
        return new String[] {language, script, region};
    }

    /**
     * {@code getLanguageCombinations()}：{@code fr_Latn_FR} 展开成
     * {@code fr_Latn_FR}、{@code fr_Latn}、{@code fr_FR}、{@code fr}。
     */
    static List<String> getLanguageCombinations(String locale) {
        String[] parts = getLanguageComponents(locale);
        String language = parts[0];
        String script = parts[1];
        String region = parts[2];

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(join(language, script, region));
        out.add(join(language, script, null));
        out.add(join(language, null, region));
        out.add(language);
        out.remove(null);
        return new ArrayList<>(out);
    }

    private static String join(String language, String script, String region) {
        List<String> kept = new ArrayList<>(3);
        for (String part : new String[] {language, script, region}) {
            if (part != null && !part.isEmpty()) {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? null : String.join("_", kept);
    }
}
