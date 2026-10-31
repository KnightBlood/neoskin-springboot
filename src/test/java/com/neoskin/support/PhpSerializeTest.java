package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.neoskin.conformance.Corpus;

import tools.jackson.databind.JsonNode;

/**
 * {@link PhpSerialize} 的语料驱动测试。
 *
 * <p>数据源 {@code conformance/data/cookie.json} 的 {@code serialize.*}（13 条常规
 * 加 {@code serialize-rec}）。输入用 {@code input_json}（JSON 文本）经
 * {@link PhpJson#decode} 水化；{@code serialize-rec} 用 {@code input_repr} 选择
 * 特殊构建器（Java 树表达不了 PHP ref-zval，自引用数组重建为
 * {@code outer=[1,2,X]; X=[1,2,X]}），期望值 {@code expected} 是 PHP 8.5
 * {@code serialize()} 的逐字节输出——语料库由
 * {@code conformance/oracle/gen-cookie.php} 生成，不得手改。
 *
 * <p>这条链路是 Laravel 会话（{@code serialization = 'php'}）写入 cookie 的起点：
 * 字节长度的计算、不转义的字符串内容、{@code d:} 的最短往返表示，错任何一处，
 * 服务端写入的会话 cookie 换到 PHP 侧就解不开。
 */
class PhpSerializeTest {

    /** {@code serialize()}: 13 条常规 + {@code serialize-rec}，输入从语料构建。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusSerialize() {
        Corpus corpus = Corpus.require("cookie");
        return corpus.cases("serialize").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            Object input = buildInput(c);
                            assertEquals(Corpus.textOrEmpty(c, "expected"),
                                    PhpSerialize.serialize(input), Corpus.text(c, "id"));
                        }));
    }

    /**
     * {@code input_repr: rec2} → 自引用数组重建：{@code outer=[1,2,X]; X=[1,2,X]}。
     * PHP 原型是 {@code $x=[1,2]; $x[2]=&$x;}（ref-zval 在 Java 树里由
     * 「内层自环」表达），逐字节等价（见 {@link PhpSerialize} 引用发射说明）。
     */
    private static Object buildInput(JsonNode c) {
        if ("rec2".equals(Corpus.textOrEmpty(c, "input_repr"))) {
            PhpJson.Array inner = new PhpJson.Array();
            inner.put(0L, 1);
            inner.put(1L, 2);
            inner.put(2L, inner);

            PhpJson.Array outer = new PhpJson.Array();
            outer.put(0L, 1);
            outer.put(1L, 2);
            outer.put(2L, inner);

            return outer;
        }

        return PhpJson.decode(Corpus.textOrEmpty(c, "input_json"));
    }

    // ------------------------------------------------------- 逐字节行为冒烟

    @Test
    void stringContentIsNotEscapedAndLengthIsBytes() {
        // 引号、反斜杠、NUL 原样写入
        assertEquals("s:3:\"a\"b\";", PhpSerialize.serialize("a\"b"));
        assertEquals("s:2:\"a\\\";", PhpSerialize.serialize("a\\"));
        // UTF-8 按字节算
        assertEquals("s:6:\"密码\";", PhpSerialize.serialize("密码"));
        // 一个变体字符 4 字节
        assertEquals("s:4:\"\uD83D\uDD10\";", PhpSerialize.serialize("\uD83D\uDD10"));
        assertEquals("s:0:\"\";", PhpSerialize.serialize(""));
        // NUL 字节
        assertEquals("s:1:\"\u0000\";", PhpSerialize.serialize("\u0000"));
    }

    @Test
    void scalarsMatchPhp() {
        assertEquals("N;", PhpSerialize.serialize(null));
        assertEquals("b:1;", PhpSerialize.serialize(true));
        assertEquals("b:0;", PhpSerialize.serialize(false));
        assertEquals("i:42;", PhpSerialize.serialize(42));
        assertEquals("i:-7;", PhpSerialize.serialize(-7));
        assertEquals("i:9223372036854775807;", PhpSerialize.serialize(Long.MAX_VALUE));
        assertEquals("i:-9223372036854775808;", PhpSerialize.serialize(Long.MIN_VALUE));
    }

    @Test
    void doublesUseShortestRoundTripWithUpperE() {
        assertEquals("d:1.5;", PhpSerialize.serialize(1.5));
        assertEquals("d:1;", PhpSerialize.serialize(1.0));
        assertEquals("d:-0;", PhpSerialize.serialize(-0.0));
        assertEquals("d:INF;", PhpSerialize.serialize(Double.POSITIVE_INFINITY));
        assertEquals("d:-INF;", PhpSerialize.serialize(Double.NEGATIVE_INFINITY));
        assertEquals("d:NAN;", PhpSerialize.serialize(Double.NaN));
        // 指数大写
        assertEquals("d:1.0E-5;", PhpSerialize.serialize(0.00001));
    }

    @Test
    void arraysAreNormalizedAndNested() {
        PhpJson.Array list = new PhpJson.Array();
        list.put(0L, 1);
        list.put(1L, "a");
        assertEquals("a:2:{i:0;i:1;i:1;s:1:\"a\";}", PhpSerialize.serialize(list));

        PhpJson.Array map = new PhpJson.Array();
        map.put("a", 1);
        map.put("b", "x");
        assertEquals("a:2:{s:1:\"a\";i:1;s:1:\"b\";s:1:\"x\";}", PhpSerialize.serialize(map));

        PhpJson.Array nested = new PhpJson.Array();
        PhpJson.Array inner = new PhpJson.Array();
        inner.put("y", true);
        nested.put("x", inner);
        assertEquals("a:1:{s:1:\"x\";a:1:{s:1:\"y\";b:1;}}", PhpSerialize.serialize(nested));

        assertEquals("a:0:{}", PhpSerialize.serialize(new PhpJson.Array()));
    }

    @Test
    void numericStringKeysAreNormalized() {
        PhpJson.Array map = new PhpJson.Array();
        map.put("0", "a");
        map.put("01", "b");
        assertEquals("a:2:{i:0;s:1:\"a\";s:2:\"01\";s:1:\"b\";}", PhpSerialize.serialize(map));
    }

    @Test
    void iterableBecomesList() {
        assertEquals("a:2:{i:0;i:1;i:1;s:1:\"a\";}", PhpSerialize.serialize(List.of(1, "a")));
    }
}
