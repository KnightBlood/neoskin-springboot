package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;

/**
 * {@link EnforceEverGreen} 的判定表 —— 全部取自 PHP 实测。
 *
 * <p>源判定是 {@code preg_match('/Chrome\/(\d+)/')} + {@code < 55} 与
 * {@code Str::contains($ua, ['Trident', 'MSIE'])}。这里的用例覆盖：</p>
 * <ul>
 *   <li>老 Chrome / 新 Chrome 的分界（54 拦、55 放）；</li>
 *   <li>{@code Trident} 与 {@code MSIE}（IE 的两种写法）；</li>
 *   <li>大小写敏感（{@code trident} 不拦——{@code Str::contains} 不忽略大小写）；</li>
 *   <li>没有 UA 时不拦；</li>
 *   <li>Edge/Chromium 的新 UA 里也含 {@code Chrome/xxx}，必须按版本号判定。</li>
 * </ul>
 */
class EnforceEverGreenTest {

    private static final String OLD_CHROME_52 = "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/52.0.2743.116 Safari/537.36";
    private static final String CHROME_55 = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36";
    private static final String CHROME_100 = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36";
    private static final String IE11 = "Mozilla/5.0 (Windows NT 6.1; Trident/7.0; rv:11.0) like Gecko";
    private static final String IE9 = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0)";

    /** 老 Chrome（&lt; 55）拦。 */
    @Test
    void rejectsOldChrome() {
        assertTrue(EnforceEverGreen.isRejected(OLD_CHROME_52));
        assertTrue(EnforceEverGreen.isRejected(
                "Mozilla/5.0 Chrome/54.0.2840.99 Safari/537.36"));
        assertTrue(EnforceEverGreen.isRejected(
                "Mozilla/5.0 Chrome/5.0.0 Safari/537.36"));
    }

    /** 55 是分界值：`&lt; 55` 拦，所以 55 本身放行。 */
    @Test
    void chrome55IsAllowed() {
        assertFalse(EnforceEverGreen.isRejected(CHROME_55));
        assertFalse(EnforceEverGreen.isRejected(CHROME_100));
    }

    /** IE 的两种写法都拦。 */
    @Test
    void rejectsInternetExplorer() {
        assertTrue(EnforceEverGreen.isRejected(IE11));
        assertTrue(EnforceEverGreen.isRejected(IE9));
    }

    /** {@code Str::contains} 区分大小写：小写 trident / msie 不拦。 */
    @Test
    void isCaseSensitive() {
        assertFalse(EnforceEverGreen.isRejected("Mozilla/5.0 trident/7.0"));
        assertFalse(EnforceEverGreen.isRejected("Mozilla/5.0 msie 9.0"));
    }

    /** 没有 UA（或空串）不拦。 */
    @Test
    void toleratesMissingUserAgent() {
        assertFalse(EnforceEverGreen.isRejected(null));
        assertFalse(EnforceEverGreen.isRejected(""));
    }

    /** 版本号只取第一个数字段：{@code Chrome/100.0.0.0} 是 100，不是 1。 */
    @Test
    void parsesMajorVersionOnly() {
        assertFalse(EnforceEverGreen.isRejected(CHROME_100));
        // 若错误地解析成 "100..." 的某段（比如取到 "0"），就会被误拦
        assertTrue(EnforceEverGreen.isRejected("Chrome/4.0.0.0 Safari"));
    }

    /** 翻译文案来自 {@code errors.http.ie}（en 语料）。 */
    @Test
    void usesTranslatedMessage() {
        Translator translator = new Translator(new TranslationFileLoader(), "en", "en");
        assertEquals("Your browser isn't supported. Please switch to other modern browsers, "
                + "such as Firefox or Chrome.",
                translator.trans("errors.http.ie", Map.of(), "en"));
    }
}
