package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.neoskin.conformance.Corpus;

import tools.jackson.databind.JsonNode;

/**
 * {@link PhpJson} 的语料驱动测试。
 *
 * <p>数据源是 {@code conformance/data/json.json}（83 条），其中：
 * <ul>
 *   <li>73 条 {@code encode.*} —— 同时比对 {@code raw}（{@code json_encode($v)}）
 *       与 {@code laravel}（{@code response()->json($v)}）；</li>
 *   <li>{@code writeback.*} —— 迁移回写用的两种形状；</li>
 *   <li>{@code const.encoding_options} —— 锁定 Laravel 13 的编码选项是 <b>0</b>，
 *       即 {@code response()->json()} <b>不</b>开启 {@code JSON_UNESCAPED_UNICODE}。</li>
 * </ul>
 *
 * <p>{@code helper} / {@code response} 两组的 {@code expected_body} 也在这里校验：
 * 它们虽然由 {@code json()} / {@code JsonResponse} 产出，但字节内容完全由
 * {@code json_encode} 决定，是「Laravel 默认选项」的第二组证据。
 *
 * <p>另外用手写用例覆盖 {@code json_decode($json, true)} 的边界——PHP 的这几种行为
 * 直接决定迁移回填的逻辑分支（{@code {} } 与 {@code []} 不可区分、
 * 超出 int64 的整数变 float、孤立代理项报错）。
 */
class PhpJsonTest {

    // ================================================================== 编码（语料）

    @Test
    @DisplayName("json.json 的 encode 用例：raw 与 laravel 两种选项都要逐字节一致")
    void encodeMatchesCorpus() {
        Corpus corpus = Corpus.require("json");
        int checked = 0;
        for (JsonNode c : corpus.cases("encode")) {
            String id = Corpus.textOrEmpty(c, "id");
            if (id.startsWith("writeback.")) {
                // 这两条的 laravel 字段是「迁移回写」的期望，单独由
                // writebackUsesUnescapedUnicodeOnly 校验
                continue;
            }
            Object value = toPhpValue(c.get("value"), Corpus.text(c, "php_type"));
            if ("encode.float.negzero".equals(id)) {
                // Jackson 解析语料时把 -0 归一成 0，JSON 层面无法保留符号位
                value = -0.0;
            }
            assertEquals(Corpus.textOrEmpty(c, "raw"), PhpJson.encode(value), id + " [raw]");
            assertEquals(Corpus.textOrEmpty(c, "laravel"),
                    PhpJson.encode(value, encodingOptions(corpus)), id + " [laravel]");
            checked++;
        }
        assertEquals(71, checked, "encode 用例数量变了？请重新生成语料");
    }

    @Test
    @DisplayName("helper / response 两组：json() 与 JsonResponse 的响应体是裸 json_encode")
    void responseBodiesMatchCorpus() {
        Corpus corpus = Corpus.require("json");
        int checked = 0;
        for (String op : List.of("helper", "response")) {
            for (JsonNode c : corpus.cases(op)) {
                String id = Corpus.textOrEmpty(c, "id");
                Object value;
                if ("helper".equals(op)) {
                    value = jsonHelper(c.get("args"));
                } else {
                    value = toPhpValue(c.get("value"), null);
                }
                assertEquals(Corpus.textOrEmpty(c, "expected_body"),
                        PhpJson.encode(value, encodingOptions(corpus)), id);
                assertFalse(Corpus.textOrEmpty(c, "expected_content_type").isEmpty(), id);
                checked++;
            }
        }
        assertEquals(9, checked, "helper + response 用例数量变了？");
    }

    @Test
    @DisplayName("const.encoding_options：Laravel 13 用 flags=0，不要凭记忆加 UNESCAPED_UNICODE")
    void laravelUsesBareDefaults() {
        JsonNode c = Corpus.require("json").caseById("const.encoding_options");
        assertEquals(0, c.path("value").asInt());
        assertEquals(0, encodingOptions(Corpus.require("json")),
                "语料说 flags=0；若改为 UNESCAPED_UNICODE 中文响应会多出原始字节");
        assertFalse(c.path("expected_flags").path("JSON_UNESCAPED_UNICODE").asBoolean());
        assertFalse(c.path("expected_flags").path("JSON_UNESCAPED_SLASHES").asBoolean());
    }

    @Test
    @DisplayName("writeback：迁移回写只开 UNESCAPED_UNICODE，斜杠仍然转义")
    void writebackUsesUnescapedUnicodeOnly() {
        Corpus corpus = Corpus.require("json");
        for (String id : List.of("writeback.notification_data", "writeback.communication_audience")) {
            JsonNode c = corpus.caseById(id);
            Object value = toPhpValue(c.get("value"), "array");
            assertEquals(Corpus.textOrEmpty(c, "raw"), PhpJson.encode(value), id + " [raw]");
            assertEquals(Corpus.textOrEmpty(c, "laravel"),
                    PhpJson.encode(value, PhpJson.JSON_UNESCAPED_UNICODE), id + " [laravel]");
        }
    }

    // ================================================================== 解码（手写）

    @Test
    @DisplayName("数字字符串键「从 0 连续」解成 list，否则保持对象")
    void decodeNumericKeys() {
        Object sequential = PhpJson.decode("{\"0\":\"a\",\"1\":\"b\"}");
        assertTrue(sequential instanceof PhpJson.Array);
        assertEquals("[\"a\",\"b\"]", PhpJson.encode(sequential));

        for (String json : List.of(
                "{\"0\":\"a\",\"2\":\"c\"}",
                "{\"-1\":\"a\"}",
                "{\"01\":\"a\"}",
                "{\"1.0\":\"a\"}",
                "{\"\":\"a\"}",
                "{\"1\":\"a\",\"0\":\"b\"}")) {
            Object decoded = PhpJson.decode(json);
            assertTrue(decoded instanceof PhpJson.Array, json);
            assertEquals('{', PhpJson.encode(decoded).charAt(0), json + " 应保持对象");
        }
    }

    @Test
    @DisplayName("{} 与 [] 解出来都是空数组，重新编码都变成 []")
    void decodeEmptyObjectBecomesArray() {
        assertEquals("[]", PhpJson.encode(PhpJson.decode("{}")));
        assertEquals("[]", PhpJson.encode(PhpJson.decode("[]")));
    }

    @Test
    @DisplayName("1.0 解成 float（而不是 int），所以重新编码仍是 1")
    void decodeFloatKeepsType() {
        assertTrue(PhpJson.decode("1.0") instanceof Double);
        assertEquals("1", PhpJson.encode(PhpJson.decode("1.0")));
        assertTrue(PhpJson.decode("1") instanceof Long);
        assertEquals("1", PhpJson.encode(PhpJson.decode("1")));
    }

    @Test
    @DisplayName("超出 int64 的整数变成 float（PHP 的真实行为）")
    void decodeInt64OverflowBecomesFloat() {
        assertTrue(PhpJson.decode("9223372036854775807") instanceof Long);
        assertTrue(PhpJson.decode("9223372036854775808") instanceof Double);
        assertEquals("9.223372036854776e+18", PhpJson.encode(PhpJson.decode("9223372036854775808")));
    }

    @Test
    @DisplayName("孤立代理项报错，合法代理对正常还原")
    void decodeSurrogates() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PhpJson.decode("\"\\ud800\""));
        assertEquals("Single unpaired UTF-16 surrogate in unicode escape", e.getMessage());
        assertEquals("🔐", PhpJson.decode("\"\\ud83d\\udd10\""));
        // 单独的（未转义）低代理项同样非法
        assertThrows(IllegalArgumentException.class, () -> PhpJson.decode("\"\\udc00\""));
    }

    @Test
    @DisplayName("BOM 开头是语法错误（PHP 不认 BOM）")
    void decodeRejectsBom() {
        assertEquals("Syntax error",
                assertThrows(IllegalArgumentException.class, () -> PhpJson.decode("\uFEFF{}"))
                        .getMessage());
    }

    @Test
    @DisplayName("重复键保持首次位置、取最后一次的值")
    void decodeDuplicateKeys() {
        Object decoded = PhpJson.decode("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals("{\"a\":3,\"b\":2}", PhpJson.encode(decoded));
    }

    @Test
    @DisplayName("语法错误的若干典型输入")
    void decodeSyntaxErrors() {
        for (String bad : List.of("01", "+1", "1.", ".5", "1e", "tru", "[1,]",
                "{\"a\":1,}", "", " ", "{", "[", "\"", "nul")) {
            assertThrows(IllegalArgumentException.class, () -> PhpJson.decode(bad),
                    "应拒绝: <" + bad + ">");
        }
    }

    @Test
    @DisplayName("decodeArray：非数组与坏 JSON 都返回 null（对应 !is_array($data)）")
    void decodeArrayContract() {
        assertNull(PhpJson.decodeArray("\"text\""));
        assertNull(PhpJson.decodeArray("42"));
        assertNull(PhpJson.decodeArray("null"));
        assertNull(PhpJson.decodeArray("{bad"));
        assertNotNull(PhpJson.decodeArray("{\"title\":\"x\"}"));
        assertNotNull(PhpJson.decodeArray("[1,2]"));
        assertEquals("x", PhpJson.decodeArray("{\"title\":\"x\"}").get("title"));
    }

    @Test
    @DisplayName("Array.with() 按键归一化规则放入")
    void arrayWithNormalizesKeys() {
        PhpJson.Array a = new PhpJson.Array();
        a.with("1", "b").with("01", "c").with(2L, "d");
        assertEquals("{\"1\":\"b\",\"01\":\"c\",\"2\":\"d\"}", PhpJson.encode(a));
        assertFalse(PhpJson.isList(a));
        assertTrue(PhpJson.isList((Map<?, ?>) PhpJson.decode("[\"x\"]")));
    }

    @Test
    @DisplayName("Float / BigDecimal / 非有限值")
    void numericEdgeCases() {
        assertEquals("0.5", PhpJson.encode(0.5f));
        assertEquals("1", PhpJson.encode(new java.math.BigDecimal("1.0")));
        assertEquals("2", PhpJson.encode(new java.math.BigDecimal("2")));
        assertEquals("1.5", PhpJson.encode(new java.math.BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class, () -> PhpJson.encode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PhpJson.encode(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("孤立代理项的 String 编码失败（对应 JSON_ERROR_UTF8）")
    void encodeRejectsLoneSurrogate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PhpJson.encode("\ud800"));
        assertEquals("Malformed UTF-8 characters, possibly incorrectly encoded", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PhpJson.encode("a\udc00b"));
    }

    // ================================================================== 辅助

    /** 从语料的 {@code const.encoding_options} 读编码选项。 */
    private static int encodingOptions(Corpus corpus) {
        return corpus.caseById("const.encoding_options").path("value").asInt();
    }

    /**
     * {@code app/helpers.php} 里 {@code json()} 的归一化逻辑。
     *
     * <p>三条分支（由语料 {@code json.*} 锁定）：
     * <ol>
     *   <li>1 个参数且是数组 → 直接当响应体；</li>
     *   <li>3 个参数且第 3 个是数组 → {@code {code,message,data}}；</li>
     *   <li>否则 → {@code {code: arg1 或 1, message: arg0}}。</li>
     * </ol>
     */
    private static Object jsonHelper(JsonNode args) {
        // 逐条对应 app/helpers.php：
        //   count === 1 && is_array($args[0])            → response()->json($args[0])
        //   count === 3 && is_array($args[2])            → {code, message, data}
        //   否则                                          → {code: Arr::get($args,1,1), message: $args[0]}
        if (args.size() == 1 && args.get(0).isContainer()) {
            return toPhpValue(args.get(0), null);
        }
        if (args.size() == 3 && args.get(2).isContainer()) {
            Map<Object, Object> out = new LinkedHashMap<>();
            out.put("code", toPhpValue(args.get(1), null));
            out.put("message", toPhpValue(args.get(0), null));
            out.put("data", toPhpValue(args.get(2), null));
            return out;
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        // Arr::get($args, 1, 1)：键不存在才用默认值 1，显式的 null 会原样返回
        out.put("code", args.size() >= 2 ? toPhpValue(args.get(1), null) : 1L);
        out.put("message", toPhpValue(args.get(0), null));
        return out;
    }

    /**
     * {@code JsonNode} → PHP 值。
     *
     * <p>必须参考 {@code php_type}：JSON 里 {@code 0} 既可能是 PHP 的 {@code int 0}
     * 也可能是 {@code float 0.0}，只看 JSON 文本无法区分，而两者的编码结果不同
     * （{@code 0} vs {@code 0}——这里恰好相同，但 {@code 1e20} 之类必须走 float 路径，
     * 否则会被当成 int 输出成 {@code 100000000000000000000}）。
     */
    private static Object toPhpValue(JsonNode node, String phpType) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isNumber()) {
            if ("float".equals(phpType)) {
                return node.doubleValue();
            }
            if (node.isIntegralNumber()) {
                return node.longValue();
            }
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(toPhpValue(n, null)));
            return list;
        }
        Map<Object, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            map.put(e.getKey(), toPhpValue(e.getValue(), null));
        }
        return map;
    }
}
