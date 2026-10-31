package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link PhpVarExport} 的回归测试。
 *
 * <p>测试输入 {@code src/test/resources/options-sample.php} 是<b>真正的 PHP 8.5.12</b>
 * 用 {@code var_export()} 生成的（与 {@code php artisan options:cache} 同样的拼装方式），
 * 因此这里验证的是「Java 能不能读懂 PHP 写出来的文件」，而不是自己编的格式。
 */
class PhpVarExportTest {

    private static String sample() throws IOException {
        try (InputStream in = PhpVarExportTest.class.getResourceAsStream("/options-sample.php")) {
            assertTrue(in != null, "缺少测试资源 options-sample.php");
            // Windows 上 core.autocrlf=true 会把资源文件 checkout 成 CRLF，
            // 而 PHP 里的多行字符串是 \n；归一化后再解析（同 ErrorPagesTest）。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }

    @Test
    void parsesRealPhpOptionsCache() throws IOException {
        Map<String, Object> parsed = PhpVarExport.parseArray(sample());

        assertEquals("https://skin.example.com", parsed.get("site_url"));
        assertEquals("Blessing Skin", parsed.get("site_name"));
        assertEquals("6.0.11_patch", parsed.get("version"));
        assertEquals("", parsed.get("plugins_enabled"));
        assertEquals(
                "<b>Copyright &copy; {year} <a href=\"{site_url}\">{site_name}</a>.</b> All rights reserved.",
                parsed.get("copyright_text"));
        assertEquals("中文选项值 — 测试", parsed.get("unicode"));
        // 单引号串里的 \' 与 \\ 必须还原成 ' 与 \
        assertEquals("it's a \"test\" with \\ backslash", parsed.get("quoted"));
        // var_export 会把换行原样写在单引号里
        assertEquals("line1\nline2", parsed.get("multi"));
        // 数字形式的选项值仍是字符串（PHP 里本来就是 string）
        assertEquals("1000", parsed.get("numeric_string"));
        assertEquals(10, parsed.size());
    }

    @Test
    void roundTripsThroughPhpFormat() throws IOException {
        Map<String, Object> original = PhpVarExport.parseArray(sample());
        String written = PhpVarExport.toPhpFile(original);
        Map<String, Object> reparsed = PhpVarExport.parseArray(written);
        assertEquals(original, reparsed);
    }

    @Test
    void writesSameHeaderAsArtisanCommand() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("site_name", "Blessing Skin");
        String out = PhpVarExport.toPhpFile(items);

        // OptionsCacheCommand 的拼装：'<?php'.PHP_EOL.$notice.'return '.$content.';'
        assertTrue(out.startsWith("<?php\n"), "必须能被 PHP 直接 require");
        assertTrue(out.contains("// This is auto-generated. DO NOT edit manually.\n"));
        assertTrue(out.startsWith("<?php\n// This is auto-generated. DO NOT edit manually.\nreturn array (\n"));
        assertTrue(out.endsWith(");"), "PHP 语句必须以分号结尾");
        assertTrue(out.contains("  'site_name' => 'Blessing Skin',\n"));
    }

    @Test
    void exportsNullAndBooleansLikeVarExport() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("a_null", null);
        items.put("a_true", Boolean.TRUE);
        items.put("a_false", Boolean.FALSE);
        items.put("an_int", 42L);
        String out = PhpVarExport.toPhpFile(items);

        assertTrue(out.contains("'a_null' => NULL,"));
        assertTrue(out.contains("'a_true' => true,"));
        assertTrue(out.contains("'a_false' => false,"));
        assertTrue(out.contains("'an_int' => 42,"));

        Map<String, Object> reparsed = PhpVarExport.parseArray(out);
        assertTrue(reparsed.containsKey("a_null"));
        assertNull(reparsed.get("a_null"));
        assertEquals(Boolean.TRUE, reparsed.get("a_true"));
        assertEquals(Boolean.FALSE, reparsed.get("a_false"));
        assertEquals(42L, reparsed.get("an_int"));
    }

    @Test
    void rejectsNonArrayInput() {
        assertThrows(IllegalArgumentException.class, () -> PhpVarExport.parseArray("<?php return 'hello';"));
        assertThrows(IllegalArgumentException.class, () -> PhpVarExport.parseArray("<?php return 42;"));
    }
}
