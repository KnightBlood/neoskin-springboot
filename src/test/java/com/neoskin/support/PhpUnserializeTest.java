package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.neoskin.conformance.Corpus;

import tools.jackson.databind.JsonNode;

/**
 * {@link PhpUnserialize} 的语料驱动测试。
 *
 * <p>数据源 {@code conformance/data/cookie.json} 的 {@code unserialize.*}。
 * 测的不只是「能解析出值」，还有<b>三条对外可观察的行为</b>：
 * <ol>
 *   <li>值：标量、数组、对象的还原结果；</li>
 *   <li>失败与否：Laravel 靠它决定抛不抛 {@code DecryptException}；</li>
 *   <li>offset：错误处理器把它转成 {@code ErrorException} 消息，错一位消息就不同。</li>
 * </ol>
 */
class PhpUnserializeTest {

    // ------------------------------------------------------------------ 语料

    /** {@code unserialize()}: 11 条 —— 值 / failed / warning 三样全对。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusUnserialize() {
        Corpus corpus = Corpus.require("cookie");
        return corpus.cases("unserialize").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> assertCase(c)));
    }

    private static void assertCase(JsonNode c) {
        String id = Corpus.text(c, "id");        PhpUnserialize.Result result = PhpUnserialize.parse(Corpus.textOrEmpty(c, "input"));

        // PHP 侧：「失败」= 有警告且值退化成 false（b:0; 虽是 false 但不算失败）。
        assertEquals(Corpus.bool(c, "failed", false),
                !result.ok() && !result.warnings().isEmpty(), id + " failed");

        // 语料记录的是 PHP 的最终格式化消息：docref 警告带 unserialize(): 前缀，
        // 裸 zend_error(E_WARNING)（如 C: 的 has no unserializer）没有前缀。
        String expectedWarning = Corpus.text(c, "warning");
        String prefixed = result.firstWarning() == null
                ? null
                : "unserialize(): " + result.firstWarning();

        if (expectedWarning != null && !expectedWarning.equals(prefixed)
                && result.firstWarning() != null
                && result.firstWarning().equals(expectedWarning)) {
            // 裸警告：逐字匹配。
            assertEquals(expectedWarning, result.firstWarning(), id + " warning");
        } else {
            assertEquals(expectedWarning, prefixed, id + " warning");
        }

        JsonNode expected = c.get("expected");
        if ("RECRECURSION".equals(Corpus.textOrEmpty(c, "php_type"))) {
            // PHP 侧 json_encode($value) === false ⇔ 自引用结构；expected 没法用
            // JSON 表达，改验身份自环（PHP 的 *RECURSION* 语义）。
            // recursion_count：数组记 count($value)（这里核对元素数），对象记 null。
            // （消息里绝不拼值：自引用结构的 toString 无限递归。）
            assertTrue(isSelfRecursive(result.value()), id + ": 期望自引用结构（*RECURSION*）");
            if (result.value() instanceof PhpJson.Array array) {
                int rc = Corpus.integer(c, "recursion_count", -1);
                if (rc >= 0) {
                    assertEquals(rc, array.size(), id + " recursion_count");
                }
            }
            return;
        }
        if ("__PHP_Incomplete_Class".equals(Corpus.textOrEmpty(c, "php_type"))) {
            // 未知类：PHP 退化成 __PHP_Incomplete_Class，保留成员里带回原名。
            PhpUnserialize.PhpObject object = assertInstanceOf(PhpUnserialize.PhpObject.class,
                    result.value(), id + " class");
            assertTrue("__PHP_Incomplete_Class".equals(object.className()), id + " class name");
            assertTrue(sameJson(expected, object.properties()), id + ": IC 属性不符");
            return;
        }
        if (expected == null || expected.isNull()) {
            // expected 是 JSON null：PHP 里是 false 哨兵或真正的 null
            assertTrue(result.value() == null || Boolean.FALSE.equals(result.value()),
                    id + " value: " + result.value());
        } else {
            assertTrue(matches(expected, result.value()), id + " value: " + result.value());
        }
    }

    /** 语料期望值 ↔ 解析结果：数组对数组、标量对标量。 */
    private static boolean matches(JsonNode expected, Object actual) {
        if (expected.isNumber()) {
            return actual instanceof Number n && n.doubleValue() == expected.doubleValue();
        }
        if (expected.isBoolean()) {
            return Boolean.valueOf(expected.asBoolean()).equals(actual);
        }
        if (expected.isString()) {
            return expected.asString().equals(actual);
        }
        return actual instanceof PhpJson.Array array && sameJson(expected, array);
    }

    /** 递归比较：语料 JSON ↔ {@link PhpJson.Array}（int 键已归一化成 Long）。 */
    private static boolean sameJson(JsonNode expected, PhpJson.Array actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        if (expected.isArray()) {
            for (int i = 0; i < expected.size(); i++) {
                if (!compareChild(expected.get(i), actual.get((long) i))) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonNode> field : expected.properties()) {
            if (!compareChild(field.getValue(), actual.get(field.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean compareChild(JsonNode expected, Object actual) {
        if (expected.isObject() || expected.isArray()) {
            return actual instanceof PhpJson.Array child && sameJson(expected, child);
        }
        return matches(expected, actual);
    }

    /**
     * RECRECURSION 断言：从值出发存在一条身份（{@code ===}）路径回到值本身
     * ——对应 PHP {@code json_encode($value) === false} 的判定口径。
     */
    private static boolean isSelfRecursive(Object value) {
        return reachesRoot(value, value, java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static boolean reachesRoot(Object node, Object root, java.util.Set<Object> seen) {
        if (node == root) {
            return true;
        }
        if (!seen.add(node)) {
            return false;
        }
        if (node instanceof PhpJson.Array array) {
            for (Object child : array.values()) {
                if (reachesRoot(child, root, seen)) {
                    return true;
                }
            }
        } else if (node instanceof PhpUnserialize.PhpObject object) {
            for (Object child : object.properties().values()) {
                if (reachesRoot(child, root, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 值

    @Test
    void scalars() {
        assertEquals(Boolean.TRUE, PhpUnserialize.parse("b:1;").value());
        assertEquals(Boolean.FALSE, PhpUnserialize.parse("b:0;").value());
        assertEquals(42L, PhpUnserialize.parse("i:42;").value());
        assertEquals(-7L, PhpUnserialize.parse("i:-7;").value());
        assertEquals(1.5, PhpUnserialize.parse("d:1.5;").value());
        assertEquals("abc", PhpUnserialize.parse("s:3:\"abc\";").value());
        assertEquals("密码", PhpUnserialize.parse("s:6:\"密码\";").value());
        assertTrue(PhpUnserialize.parse("N;").ok());
    }

    @Test
    void intOverflowSaturatesWithWarning() {
        PhpUnserialize.Result result = PhpUnserialize.parse("i:9223372036854775808;");
        assertTrue(result.ok());
        assertEquals(Long.MAX_VALUE, result.value());
        assertEquals(List.of("Numerical result out of range"), result.warnings());
    }

    @Test
    void intNineteenDigitsIsExact() {
        assertEquals(9223372036854775807L, PhpUnserialize.parse("i:9223372036854775807;").value());
        assertEquals(-9223372036854775808L, PhpUnserialize.parse("i:-9223372036854775808;").value());
    }

    @Test
    void doublesAcceptShortForms() {
        assertEquals(0.5, PhpUnserialize.parse("d:.5;").value());
        assertEquals(1.0, PhpUnserialize.parse("d:1;").value());
        assertEquals(Double.parseDouble("-0"), PhpUnserialize.parse("d:-0;").value());
        assertFalse(PhpUnserialize.parse("d:.;").ok());
        assertTrue(PhpUnserialize.parse("d:1.;").ok());
    }

    @Test
    void arrays() {
        PhpUnserialize.Result result = PhpUnserialize.parse("a:2:{s:1:\"a\";i:1;s:1:\"b\";s:1:\"x\";}");
        assertTrue(result.ok());
        PhpJson.Array array = result.value() instanceof PhpJson.Array a ? a : null;
        assertTrue(array != null);
        assertEquals(2, array.size());
        assertEquals(1L, array.get("a"));
        assertEquals("x", array.get("b"));
    }

    @Test
    void arrayCountRejectsLeadingSign() {
        assertFalse(PhpUnserialize.parse("a:-1:{};").ok());
        assertFalse(PhpUnserialize.parse("a:+1:{i:0;i:1;}").ok());
    }

    @Test
    void fakeElementCountFails() {
        // 每个元素至少 2 字节，声明数超过剩余一半就是伪造（IS_FAKE_ELEM_COUNT）
        assertFalse(PhpUnserialize.parse("a:100:{i:0;i:1;}").ok());
    }

    @Test
    void shortArrayFailsWithUnexpectedEnd() {
        PhpUnserialize.Result result = PhpUnserialize.parse("a:2:{i:0;i:1;}");
        assertFalse(result.ok());
        assertEquals("Unexpected end of serialized data", result.warnings().get(0));
        assertEquals("Error at offset 13 of 14 bytes", result.warnings().get(1));
    }

    // ------------------------------------------------------------------ 失败与 offset

    @Test
    void trailingJunkWarnsButSucceeds() {
        PhpUnserialize.Result result = PhpUnserialize.parse("i:42;junk");
        assertTrue(result.ok());
        assertEquals(42L, result.value());
        assertEquals("Extra data starting at offset 5 of 9 bytes", result.firstWarning());
    }

    @Test
    void garbageFailsAtZero() {
        PhpUnserialize.Result result = PhpUnserialize.parse("hello");
        assertFalse(result.ok());
        assertEquals("Error at offset 0 of 5 bytes", result.firstWarning());
    }

    @Test
    void emptyInputReturnsFalseWithoutWarning() {
        PhpUnserialize.Result result = PhpUnserialize.parse(new byte[0]);
        assertFalse(result.ok());
        assertEquals(Boolean.FALSE, result.value());
        assertTrue(result.warnings().isEmpty());
        assertEquals(result.warnings(), PhpUnserialize.parse("").warnings());
    }

    @Test
    void truncatedStringOffsets() {
        // 剩余长度不够 → p 停在长度数字上（offset 2）
        PhpUnserialize.Result tooLong = PhpUnserialize.parse("s:10:\"ab\";");
        assertFalse(tooLong.ok());
        assertEquals("Error at offset 2 of 10 bytes", tooLong.firstWarning());
        // 内容按 len 个字节硬读（引号也算数据），后面不是 '"' → p 停在那里（8）
        PhpUnserialize.Result badTail = PhpUnserialize.parse("s:3:\"ab\";");
        assertFalse(badTail.ok());
        assertEquals("Error at offset 8 of 9 bytes", badTail.firstWarning());
    }

    @Test
    void missingSemicolonFallsBack() {
        // i:42 缺分号：语法不匹配，p 回到 0
        PhpUnserialize.Result result = PhpUnserialize.parse("i:42");
        assertFalse(result.ok());
        assertEquals("Error at offset 0 of 4 bytes", result.firstWarning());
    }

    // ------------------------------------------------------------------ 引用与对象

    @Test
    void referencesFailButAdvance() {
        PhpUnserialize.Result result = PhpUnserialize.parse("R:1;");
        assertFalse(result.ok());
        assertEquals("Error at offset 4 of 4 bytes", result.firstWarning());
    }

    @Test
    void objectBecomesPhpObject() {
        // 本应用不加载用户类：O: 未知类退化成 __PHP_Incomplete_Class，
        // 原名进保留成员（PHP #24020 语义）。
        PhpUnserialize.Result result =
                PhpUnserialize.parse("O:7:\"Example\":2:{s:3:\"foo\";i:1;s:3:\"bar\";b:1;}");
        assertTrue(result.ok());
        PhpUnserialize.PhpObject object = assertInstanceOf(PhpUnserialize.PhpObject.class, result.value());
        assertEquals("__PHP_Incomplete_Class", object.className());
        assertEquals("Example", object.properties().get("__PHP_Incomplete_Class_Name"));
        assertEquals(1L, object.properties().get("foo"));
        assertEquals(Boolean.TRUE, object.properties().get("bar"));
    }

    @Test
    void customObjectSkipsPayloadAndWarns() {
        PhpUnserialize.Result result = PhpUnserialize.parse("C:8:\"stdClass\":5:{hello}");
        assertTrue(result.ok());
        PhpUnserialize.PhpObject object = assertInstanceOf(PhpUnserialize.PhpObject.class, result.value());
        assertEquals("stdClass", object.className());
        assertTrue(object.properties().isEmpty());
        assertEquals("Class stdClass has no unserializer", result.firstWarning());
    }

    @Test
    void customObjectInsufficientDataWarns() {
        // (max - p) <= datalen：警告带 required / present，offset 停在 '{' 之后
        PhpUnserialize.Result short1 = PhpUnserialize.parse("C:8:\"stdClass\":6:{hello}");
        assertFalse(short1.ok());
        assertEquals("Insufficient data for unserializing - 6 required, 6 present",
                short1.warnings().get(0));
        assertEquals("Error at offset 18 of 24 bytes", short1.warnings().get(1));
    }

    @Test
    void enumFailsWithWarnings() {
        PhpUnserialize.Result result = PhpUnserialize.parse("E:9:\"Some:Case\";");
        assertFalse(result.ok());
        assertEquals("Class 'Some' not found", result.warnings().get(0));
        assertEquals("Error at offset 0 of 16 bytes", result.warnings().get(1));
    }

    @Test
    void enumLengthMismatchFailsAtLenStart() {
        PhpUnserialize.Result result = PhpUnserialize.parse("E:8:\"M:One\";");
        assertFalse(result.ok());
        assertEquals("Error at offset 2 of 12 bytes", result.firstWarning());
    }

    @Test
    void enumWithoutColonWarns() {
        PhpUnserialize.Result result = PhpUnserialize.parse("E:10:\"SomeCase\";");
        assertFalse(result.ok());
        assertEquals("Error at offset 16 of 16 bytes", result.firstWarning());
    }

    @Test
    void arrayInKeyPositionFailsAtContentStart() {
        // a:2:{i:3;i:4;} 从 5 号位置开始：'{'+1 = 10
        PhpUnserialize.Result array = PhpUnserialize.parse("a:1:{a:0:{}i:1;}");
        assertFalse(array.ok());
        assertEquals("Error at offset 10 of 16 bytes", array.firstWarning());
        // 嵌套键数组：offset 是**外层**键数组的内容起点
        PhpUnserialize.Result nested = PhpUnserialize.parse("a:1:{a:2:{i:3;i:4;}i:1;}");
        assertFalse(nested.ok());
        assertEquals("Error at offset 10 of 24 bytes", nested.firstWarning());
    }

    @Test
    void objectInKeyPositionFailsAtKeyStart() {
        PhpUnserialize.Result result = PhpUnserialize.parse("a:1:{O:1:\"X\":0:{}i:1;}");
        assertFalse(result.ok());
        assertEquals("Error at offset 5 of 22 bytes", result.firstWarning());
    }

    @Test
    void scalarInKeyPositionFailsAtValueStart() {
        PhpUnserialize.Result bool = PhpUnserialize.parse("a:1:{b:1;i:1;}");
        assertFalse(bool.ok());
        assertEquals("Error at offset 9 of 14 bytes", bool.firstWarning());
    }

    @Test
    void arrayCountBeyondInt64WarnsThenFailsAtContentStart() {
        PhpUnserialize.Result result = PhpUnserialize.parse("a:9999999999999999999:{}");
        assertFalse(result.ok());
        assertEquals("Numerical result out of range", result.warnings().get(0));
        assertEquals("Error at offset 23 of 24 bytes", result.warnings().get(1));
    }

    // ------------------------------------------------------------------ 字节层面

    @Test
    void utf8IsDecodedButByteLengthsRule() {
        byte[] bytes = "s:6:\"密码\";".getBytes(StandardCharsets.UTF_8);
        assertTrue(PhpUnserialize.parse(bytes).ok());
        assertEquals("密码", PhpUnserialize.parse(bytes).value());
    }
}
