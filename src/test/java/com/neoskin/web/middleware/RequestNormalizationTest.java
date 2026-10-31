package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.neoskin.support.PhpJson;
import com.neoskin.support.Str;

/**
 * 全局栈归一化的值层测试：{@code Str::trim} 的不可见字符集、
 * {@code TrimStrings} 的 except 通配、{@code ConvertEmptyStringsToNull} 的
 * 空串转换、以及两者的先后顺序（先裁后转 null）。
 */
class RequestNormalizationTest {

    // ---------------------------------------------------------------- Str::trim

    @Test
    void strTrimDropsPhpWhitespaceSet() {
        assertEquals("abc", Str.trim(" \t\n\r\u000B\u0000abc"));
        assertEquals("abc", Str.trim("abc \t\n\r\u000B\u0000"));
        // \f 在 PHP 的默认 trim 字符集里没有，但 Laravel 的 Str::trim 走的是
        // \s（PCRE 的 \s 含 \f）——所以它会被裁掉。
        assertEquals("abc", Str.trim("\fabc\f"));
    }

    @Test
    void strTrimDropsInvisibleCharacters() {
        // NBSP、ZWSP、IDEOGRAPHIC SPACE、BOM、OGHAM SPACE MARK、LINE SEPARATOR
        String[] invisibles = {"\u00A0", "\u200B", "\u3000", "\uFEFF", "\u1680", "\u2028",
                "\u205F", "\u2009", "\u200E", "\u00AD"};

        for (String c : invisibles) {
            assertEquals("x", Str.trim(c + "x" + c), "char U+" + Integer.toHexString(c.charAt(0)));
        }
    }

    @Test
    void strTrimHandlesSupplementaryPlane() {
        // U+1D159 MUSICAL SYMBOL NULL NOTEHEAD（补充平面，代理对）
        String musical = "\uD834\uDD59";

        assertEquals("x", Str.trim(musical + "x" + musical));
        assertEquals("", Str.trim(musical));
    }

    @Test
    void strTrimKeepsNonInvisibleNonAscii() {
        // U+3200（带括号的表意文字）不在不可见表里 → 保留（与探针一致）
        assertEquals("\u3200x", Str.trim("\u3200x"));
        // 俄文字母、中文都不裁
        assertEquals("密码", Str.trim("密码"));
        assertEquals("привет", Str.trim("  привет  "));
    }

    // ------------------------------------------------------------ Str::is 通配

    @Test
    void strIsWildcardMatching() {
        assertTrue(Str.is("password", "password"));
        assertTrue(Str.is("*", "anything"));
        assertTrue(Str.is("admin/*", "admin/options"));
        assertTrue(Str.is("admin/*", "admin/score"));
        // 单层通配也能跨斜杠（PHP 的 .* 行为）
        assertTrue(Str.is("a*z", "a/b/c/z"));
        assertEquals(false, Str.is("password", "password_confirmation"));
        assertEquals(false, Str.is("admin/*", "administrator"));
    }

    // ------------------------------------------------------ TrimStrings / Empty

    @Test
    void trimThenConvertEmptyToNullInStackOrder() {
        PhpJson.Array data = new PhpJson.Array();
        data.put("name", "  abc  ");
        data.put("blank", "   ");
        data.put("password", "  secret  ");
        data.put("nested", new PhpJson.Array().with("deep", "\t x \t"));

        List<TransformsRequest.Bag> bags = List.of(new TransformsRequest.Bag("input", data));

        new TransformsRequest.TrimStrings().clean(bags);
        // 裁完：name=abc；blank 变成空串（还没转 null）；password 不裁
        assertEquals("abc", data.get("name"));
        assertEquals("", data.get("blank"));
        assertEquals("  secret  ", data.get("password"));
        assertEquals("x", ((PhpJson.Array) data.get("nested")).get("deep"));

        new TransformsRequest.ConvertEmptyStringsToNull().clean(bags);
        assertNull(data.get("blank"));
        assertEquals("abc", data.get("name"));
        assertEquals("  secret  ", data.get("password"));
    }

    @Test
    void trimRespectsExceptAndNeverTrim() {
        TransformsRequest.TrimStrings.flushState();

        try {
            TransformsRequest.TrimStrings.except(List.of("custom_field"));

            PhpJson.Array data = new PhpJson.Array();
            data.put("custom_field", "  keep  ");
            data.put("password_confirmation", "  keep  ");
            data.put("other", "  trim  ");

            new TransformsRequest.TrimStrings().clean(
                    List.of(new TransformsRequest.Bag("input", data)));

            assertEquals("  keep  ", data.get("custom_field"));
            assertEquals("  keep  ", data.get("password_confirmation"));
            assertEquals("trim", data.get("other"));
        } finally {
            TransformsRequest.TrimStrings.flushState();
        }
    }

    @Test
    void nestedArraysAreWalkedDepthFirst() {
        PhpJson.Array inner = new PhpJson.Array();
        inner.put("a", new PhpJson.Array().with("b", "  c  "));
        PhpJson.Array root = new PhpJson.Array();
        root.put("outer", inner);

        new TransformsRequest.TrimStrings().clean(
                List.of(new TransformsRequest.Bag("input", root)));

        Object outer = root.get("outer");
        Object a = ((PhpJson.Array) outer).get("a");

        assertEquals("c", ((PhpJson.Array) a).get("b"));
    }

    @Test
    void postMaxSizeUsesIniUnits() {
        assertEquals(8388608L, RequestInput.parsePostMaxSize("8M"));
        assertEquals(524288L, RequestInput.parsePostMaxSize("512K"));
        assertEquals(1073741824L, RequestInput.parsePostMaxSize("1G"));
        assertEquals(100L, RequestInput.parsePostMaxSize("100"));
        assertEquals(0L, RequestInput.parsePostMaxSize("0"));
        assertEquals(true, RequestInput.postTooLarge(8388609L));
        assertEquals(false, RequestInput.postTooLarge(8388608L));
    }
}
