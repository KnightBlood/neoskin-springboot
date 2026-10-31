package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link SymfonyYaml} 的标量求值回归，全部对齐 Symfony YAML（PHP 侧以
 * {@code probe_yaml_scalar.php} / {@code probe_yaml.php} 实测）。
 */
class SymfonyYamlTest {

    /** 纯裸标量（confirm digest of probe_yaml_scalar.php）。 */
    @Test
    void scalarCases() {
        assertEquals("yes", SymfonyYaml.evaluate("yes"));
        assertEquals("no", SymfonyYaml.evaluate("no"));
        assertEquals("Yes", SymfonyYaml.evaluate("Yes"));
        assertEquals("ON", SymfonyYaml.evaluate("ON"));
        assertEquals("y", SymfonyYaml.evaluate("y"));
        assertEquals("n", SymfonyYaml.evaluate("n"));

        assertEquals(Boolean.TRUE, SymfonyYaml.evaluate("true"));
        assertEquals(Boolean.TRUE, SymfonyYaml.evaluate("True"));
        assertEquals(Boolean.TRUE, SymfonyYaml.evaluate("TRUE"));
        assertEquals(Boolean.FALSE, SymfonyYaml.evaluate("false"));
        assertEquals(Boolean.FALSE, SymfonyYaml.evaluate("False"));
        assertEquals(Boolean.FALSE, SymfonyYaml.evaluate("FALSE"));

        assertNull(SymfonyYaml.evaluate("null"));
        assertNull(SymfonyYaml.evaluate("Null"));
        assertNull(SymfonyYaml.evaluate("NULL"));
        assertNull(SymfonyYaml.evaluate("~"));
        // 裸空值在 Symfony 里是 null（带引号的 '' 已在 resolveScalar 走字符串分支）
        assertNull(SymfonyYaml.evaluate(""));

        // 行尾 / 先后空格：evaluate 先 trim（plain 标量的 Symfony 语言形态）
        assertEquals(Boolean.TRUE, SymfonyYaml.evaluate(" true "));
    }

    /** 数字形态。 */
    @Test
    void numericCases() {
        assertEquals(1, ((Number) SymfonyYaml.evaluate("1")).intValue());
        assertEquals(-5, ((Number) SymfonyYaml.evaluate("-5")).intValue());
        // 下划线记法在 Symfony 里是整数预处理
        assertEquals(1000, ((Number) SymfonyYaml.evaluate("1_000")).intValue());
        // (string)(int) !== $scalar → 保持字符串：007 / -0 / +2（外层才去 + 号）
        assertEquals("007", SymfonyYaml.evaluate("007"));
        assertEquals("-0", SymfonyYaml.evaluate("-0"));
        assertEquals(2, ((Number) SymfonyYaml.evaluate("+2")).intValue());

        assertEquals(1.5, ((Number) SymfonyYaml.evaluate("1.5")).doubleValue());
        assertEquals(0.5, ((Number) SymfonyYaml.evaluate(".5")).doubleValue());

        // 十六进制 → 十进制 int
        assertEquals(26, ((Number) SymfonyYaml.evaluate("0x1A")).intValue());

        // 指数 → float
        assertEquals(100000.0, ((Number) SymfonyYaml.evaluate("1e5")).doubleValue());
    }

    /** 文档结构（map / 嵌套 / 重复键检查）。 */
    @Test
    void mappingCases() throws Exception {
        Map<String, Object> doc = SymfonyYaml.parse(new java.io.StringReader(
                "key1: \"value1\"\n"
                        + "nested:\n"
                        + "  inner: deep\n"
                        + "list:\n"
                        + "  - a\n"
                        + "  - b\n"));
        assertEquals("value1", doc.get("key1"));
        assertEquals("deep", ((Map<?, ?>) doc.get("nested")).get("inner"));
        // 裸 `inner: deep` → 字符串（不是数值判定）
        assertEquals("deep", ((Map<?, ?>) doc.get("nested")).get("inner"));
        assertEquals(2, ((java.util.List<?>) doc.get("list")).size());

        // Symfony 语义：重复键 → ParseException
        assertThrows(IllegalStateException.class, () -> SymfonyYaml.parse(
                new java.io.StringReader("a: 1\na: 2\n")));
    }

    /** 引号值原样保留（不做隐式判定）。 */
    @Test
    void quotedStaysString() throws Exception {
        Map<String, Object> doc = SymfonyYaml.parse(new java.io.StringReader(
                "a: \"true\"\nb: 'null'\nc: '007'\nd: ' true '\n"));
        assertEquals("true", doc.get("a"));
        assertEquals("null", doc.get("b"));
        assertEquals("007", doc.get("c"));
        assertEquals(" true ", doc.get("d")); // 引号内空格不裁
    }

    /** 类 PHP 布尔（yes/no）的语料验证：de_DE general.yml 的 yes 是 boolean。 */
    @Test
    void overridesYesIsBoolean() throws Exception {
        Map<String, Object> doc = SymfonyYaml.parseResource("/lang/de_DE/general.yml");
        assertEquals(Boolean.TRUE, doc.get("yes"));
        assertEquals(Boolean.FALSE, doc.get("no"));

        Map<String, Object> en = SymfonyYaml.parseResource("/lang/en/general.yml");
        assertEquals("Yes", en.get("yes"));
        assertEquals("No", en.get("no"));
    }

    /** 未知 tag 抛出。 */
    @Test
    void unknownTagThrows() throws Exception {
        // SnakeYAML compose 对未知 !! 吃自己的 ComposerException（与 Symfony
        // ParseException 对应）；两个未知 tag 都禁止静默吞掉标量。
        assertThrows(Exception.class, () -> SymfonyYaml.parse(
                new java.io.StringReader("a: !!weird 1\n")));
        assertEquals("no", SymfonyYaml.evaluate("no"));
    }
}
