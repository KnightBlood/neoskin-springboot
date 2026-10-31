package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PhpString} 的回归测试。
 *
 * <p>期望值全部由 PHP 8.5.12 实测得到（{@code precision=14}、
 * {@code serialize_precision=-1}），不是推测。这四组差异都很容易在
 * 「用 Java 习惯写法」时悄悄出错：
 * <ul>
 *   <li>{@code trim()} 的字符集：含 {@code \x0B}、<b>不含</b> {@code \x0C} 和 NBSP；</li>
 *   <li>{@code mb_substr()} 按码点（emoji 不会被劈开）；</li>
 *   <li>{@code substr()} 按字节；</li>
 *   <li>{@code (string)} 的类型转换与浮点格式化。</li>
 * </ul>
 */
class PhpStringTest {

    // ================================================================== trim

    @Test
    @DisplayName("trim：PHP 去掉空格 / \\t \\n \\r \\0 \\x0B")
    void trimRemovesPhpWhitespace() {
        assertEquals("x", PhpString.trim(" \t\n\r\0\u000Bx\u000B\0\r\n\t "));
        assertEquals("", PhpString.trim("   "));
        assertEquals("", PhpString.trim(""));
        assertEquals("a b", PhpString.trim(" a b "));
    }

    @Test
    @DisplayName("trim：\\x0C（换页）与 NBSP 都不去掉（与 Java trim/strip 不同）")
    void trimKeepsFormFeedAndNbsp() {
        // Java 的 String.trim() 会去掉 \x0C（因为它 <= ' '）
        assertEquals("\fabc", PhpString.trim("\fabc"));
        assertEquals("abc\f", PhpString.trim("abc\f"));
        // Java 的 String.strip() 会去掉 NBSP（因为它算 Unicode 空白）
        assertEquals("\u00A0abc\u00A0", PhpString.trim("\u00A0abc\u00A0"));
        // 全角空格同理
        assertEquals("\u3000abc", PhpString.trim("\u3000abc"));
    }

    @Test
    @DisplayName("trim(null) 返回空串（对应 (string) null）")
    void trimNull() {
        assertEquals("", PhpString.trim(null));
    }

    // ================================================================== mb_substr

    @Test
    @DisplayName("mbSubstr：按码点截断，emoji 不会被劈成半个代理项")
    void mbSubstrCountsCodePoints() {
        String emoji = "😀";
        // 60 个 emoji = 120 个 UTF-16 码元、60 个码点
        String s = emoji.repeat(60);
        assertEquals(120, s.length());
        String cut = PhpString.mbSubstr(s, 0, 50);
        assertEquals(50, cut.codePointCount(0, cut.length()));
        assertEquals(100, cut.length());
        // 每个码点都还是完整的代理对
        for (int i = 0; i < cut.length(); i += 2) {
            assertEquals(Character.isHighSurrogate(cut.charAt(i)), true);
        }
    }

    @Test
    @DisplayName("mbSubstr：100 个汉字的标题按码点截断")
    void mbSubstrCjk() {
        String s = "中".repeat(120);
        assertEquals(100, PhpString.mbSubstr(s, 0, 100).length());
        assertEquals(s, PhpString.mbSubstr(s, 0, 200));
        assertEquals("", PhpString.mbSubstr(s, 0, 0));
    }

    @Test
    @DisplayName("mbSubstr：负 start / 负 length / 越界")
    void mbSubstrNegativeAndOverflow() {
        assertEquals("de", PhpString.mbSubstr("abcde", -2, 2));
        assertEquals("abc", PhpString.mbSubstr("abcde", 0, -2));
        assertEquals("", PhpString.mbSubstr("abcde", 10, 2));
        assertEquals("", PhpString.mbSubstr("abcde", 5, 2));
        assertEquals("", PhpString.mbSubstr("", 0, 5));
        assertEquals("", PhpString.mbSubstr(null, 0, 5));
        assertEquals("e", PhpString.mbSubstr("abcde", -1, 1));
        assertEquals("abcde", PhpString.mbSubstr("abcde", -100, 100));
    }

    // ================================================================== substr（按字节）

    @Test
    @DisplayName("substrBytes：announcement_<locale> 取 locale 部分")
    void substrBytesAnnouncementPrefix() {
        assertEquals("", PhpString.substrBytes("announcement", 13));
        assertEquals("zh_CN", PhpString.substrBytes("announcement_zh_CN", 13));
        assertEquals("en", PhpString.substrBytes("announcement_en", 13));
    }

    @Test
    @DisplayName("substrBytes：起点越界返回空串")
    void substrBytesOutOfRange() {
        assertEquals("", PhpString.substrBytes("abc", 3));
        assertEquals("", PhpString.substrBytes("abc", 10));
        assertEquals("", PhpString.substrBytes(null, 0));
        assertEquals("bc", PhpString.substrBytes("abc", 1));
    }

    // ================================================================== (string)

    @Test
    @DisplayName("cast：PHP 的 (string) 对 true/false/null/数组的处理")
    void castScalars() {
        assertEquals("1", PhpString.cast(Boolean.TRUE));
        assertEquals("", PhpString.cast(Boolean.FALSE));
        assertEquals("", PhpString.cast(null));
        assertEquals("Array", PhpString.cast(java.util.Map.of("a", 1)));
        assertEquals("Array", PhpString.cast(java.util.List.of(1, 2)));
        assertEquals("42", PhpString.cast(42));
        assertEquals("42", PhpString.cast(42L));
        assertEquals("abc", PhpString.cast("abc"));
        // PHP: (string) '' 就是 ''
        assertEquals("", PhpString.cast(""));
    }

    @Test
    @DisplayName("cast：浮点用 precision=14，与 json_encode 的最短往返不同")
    void castFloats() {
        assertEquals("1", PhpString.cast(1.0d));
        assertEquals("1.5", PhpString.cast(1.5d));
        assertEquals("0.1", PhpString.cast(0.1d));
        assertEquals("0.33333333333333", PhpString.cast(1.0d / 3));
        assertEquals("0.66666666666667", PhpString.cast(2.0d / 3));
        assertEquals("-0.33333333333333", PhpString.cast(-1.0d / 3));
        assertEquals("0.3", PhpString.cast(0.1d + 0.2d));
        assertEquals("-0", PhpString.cast(-0.0d));
        assertEquals("100", PhpString.cast(100.0d));
        assertEquals("1234.5", PhpString.cast(1234.5d));
    }

    @Test
    @DisplayName("cast：decpt > 14 或 < -3 时用科学计数法，至少保留一位小数")
    void castFloatScientific() {
        assertEquals("12345678901234", PhpString.cast(12345678901234.0d));
        assertEquals("1.0E+14", PhpString.cast(1e14));
        assertEquals("1.0E+15", PhpString.cast(1e15));
        assertEquals("1.0E+16", PhpString.cast(1e16));
        assertEquals("1.0E+20", PhpString.cast(1e20));
        assertEquals("-1.0E+20", PhpString.cast(-1e20));
        assertEquals("1.0E+15", PhpString.cast(999999999999999.0d));
        assertEquals("1.2345678901234E+14", PhpString.cast(123456789012345.0d));
        assertEquals("1.2345678901235E+15", PhpString.cast(1234567890123456.0d));
        // decpt = -3 仍用定点
        assertEquals("0.0001", PhpString.cast(1e-4));
        assertEquals("0.001", PhpString.cast(1e-3));
        // decpt = -4 起用科学计数法
        assertEquals("1.0E-5", PhpString.cast(1e-5));
        assertEquals("1.0E-7", PhpString.cast(1e-7));
        assertEquals("1.0E-14", PhpString.cast(1e-14));
        assertEquals("4.9406564584125E-324", PhpString.cast(Double.MIN_VALUE));
        assertEquals("1.7976931348623E+308", PhpString.cast(Double.MAX_VALUE));
    }

    @Test
    @DisplayName("cast：14 位有效数字的舍入是 HALF_EVEN")
    void castFloatRounding() {
        assertEquals("1.2345678901235", PhpString.cast(1.2345678901234567d));
        assertEquals("3.1415926535898", PhpString.cast(Math.PI));
        assertEquals("0.0001234", PhpString.cast(0.0001234d));
        assertEquals("1234567890123.5", PhpString.cast(1234567890123.5d));
    }
}
