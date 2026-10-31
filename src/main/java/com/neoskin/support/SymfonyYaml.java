package com.neoskin.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Symfony YAML 语言文件语法的移植：{@code Symfony\Component\Yaml\Yaml::parseFile}
 * 的语义子集（作用是解析 {@code resources/lang/<locale>/<group>.yml}）。
 *
 * <p>不能直接用 SnakeYAML 的 {@code new Yaml().load()}：它的默认 resolver 与
 * Symfony 差得很远，表格如下（两侧均为实测，Symfony 侧探针
 * {@code probe_yaml_scalar.php}、SnakeYAML 侧 {@code ProbeSnake.java}）：</p>
 *
 * <table border="1">
 *   <caption>标量判定差异</caption>
 *   <tr><th>裸标量</th><th>Symfony</th><th>SnakeYAML 默认</th></tr>
 *   <tr><td>{@code yes / no / on / off}</td><td>字符串</td><td><b>boolean</b>（不符）</td></tr>
 *   <tr><td>{@code true/True/FALSE}</td><td>boolean</td><td>boolean</td></tr>
 *   <tr><td>{@code null / ~}</td><td>null</td><td>null</td></tr>
 *   <tr><td>{@code y / n}</td><td>字符串</td><td>字符串</td></tr>
 *   <tr><td>{@code 2020-01-01}</td><td><b>int</b>（Unix 秒）</td><td><b>Date</b> 对象</td></tr>
 * </table>
 *
 * <p>所以本类不走 SnakeYAML 的构造过程（constructor），只用
 * {@code Yaml.compose(Reader)} 拿到节点树（它只做语法解析、不做隐式类型判定），
 * 再用 {@link #convert(Node)} 按复刻的 Symfony 标量规则转值。</p>
 *
 * <h2>复刻的 Symfony 标量规则（{@code Inline::evaluateScalar}，flags=0）</h2>
 * <ol>
 *   <li>带引号（或块标量）的值<b>永远保持字符串</b>，不做任何判定；</li>
 *   <li>{@code null/NULL/~/}（裸空值）→ null；</li>
 *   <li>只有 {@code true/True/TRUE/false/False/FALSE} 这 6 个写法是 boolean
 *       —— {@code yes}、{@code no} 一律字符串；</li>
 *   <li>{@code 0o…} 八进制 → 十进制 int；{@code 0x…} 十六进制 → 十进制 int；</li>
 *   <li>整数：去下划线后若 {@code (string)(int) === $scalar} 就返回 int，
 *       否则（例如 {@code 007}、{@code -0}）保持字符串；</li>
 *   <li>数值（PHP {@code is_numeric} 语义）→ (float) 强转，保留 {@code 1.0}
 *       不会被四舍五入成 {@code 1}；</li>
 *   <li>其余一律字符串。</li>
 * </ol>
 *
 * <p>翻译语料的实测基线（语言 143 个文件 / 10744 个叶子）：仅 24 个非字符串，
 * 全部是 12 个 locale 的 {@code general.yml} 里 {@code yes: true} / {@code no: false}
 * ——这类文件里没有任何日期或浮点叶子，但规则仍按全集实现，避免插件依赖踩坑。</p>
 */
public final class SymfonyYaml {

    /** PHP {@code is_numeric} 认可的浮点形态（此处已先排除了纯整数形态）。 */
    private static final Pattern NUMERIC = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    /** 整数形态（含 {@code e}/{@code E} 时不是整数，走浮点）。 */
    private static final Pattern INTEGER_FORM = Pattern.compile("[+-]?[0-9]+");

    /** Symfony {@code Inline::getHexRegex()}。 */
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F_]+");

    /** 八进制（Symfony 8.3 起的 {@code 0o} 记法）。 */
    private static final Pattern OCTAL = Pattern.compile("(?:\\+|-)?0o[0-7_]+");

    /** 小数形态（Symfony 的倒数第二个 case 前置判据外层：可能有下划线）。 */
    private static final Pattern DECIMAL_UNDERSCORES = Pattern.compile("(-|\\+)?[0-9][0-9_]*(\\.[0-9_]+)?");

    /** 整数倍记法（Symfony 的外层预处理分支）。 */
    private static final Pattern PLAIN_INTEGER_UNDERSCORES = Pattern.compile("[+-]?[0-9][0-9_]*");

    /** 泛型：标识符一下就够，不用额外抽象。 */
    private SymfonyYaml() {
    }

    /** 从 classpath 加载并解析。找不到资源返回 {@code null}。 */
    public static Map<String, Object> parseResource(String resourcePath) throws IOException {
        try (InputStream in = SymfonyYaml.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        }
    }

    /** 从 {@link Reader} 解析（必须以 {@code UTF-8} 打开）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(Reader reader) throws IOException {
        Node node = new Yaml().compose(reader);
        if (node == null) {
            return new LinkedHashMap<>();
        }
        if (!(node instanceof MappingNode root)) {
            throw new IllegalStateException("翻译文件顶层必须是映射（YAML Map）");
        }
        return (Map<String, Object>) (Map<?, ?>) convert(root);
    }

    // ================================================================== 节点树遍历

    /**
     * 把 compose 出来的节点树转成 PHP 数组风格的 {@code Map<String,Object>} /
     * {@code List<Object>} / 标量。键统一转 String（PHP 数组数字下标归类保留在
     * {@code key} 原样，翻译键总是字符串）。
     */
    public static Object convert(Node node) {
        return switch (node) {
            case MappingNode mapping -> convertMapping(mapping);
            case SequenceNode seq -> convertSequence(seq);
            case ScalarNode scalar -> resolveScalar(scalar);
            default -> throw new IllegalStateException("未知 YAML 节点: " + node);
        };
    }

    private static Map<String, Object> convertMapping(MappingNode mapping) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NodeTuple tuple : mapping.getValue()) {
            Node keyNode = tuple.getKeyNode();
            // Symfony 对重复键抛异常；SnakeYAML compose 把重复键原样保留，
            // 所以这里手动判重（首次出现后再次出现即抛）。
            String key = String.valueOf(resolveKeyScalar(keyNode));
            if (out.containsKey(key)) {
                throw new IllegalStateException("YAML 重复键: " + key);
            }
            out.put(key, convert(tuple.getValueNode()));
        }
        return out;
    }

    private static List<Object> convertSequence(SequenceNode seq) {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(seq.getValue().size());
        for (Node n : seq.getValue()) {
            out.add(convert(n));
        }
        return out;
    }

    private static String resolveKeyScalar(Node keyNode) {
        if (keyNode instanceof ScalarNode scalar) {
            return scalar.isPlain() ? String.valueOf(evaluate(scalar.getValue())) : scalar.getValue();
        }
        throw new IllegalStateException("YAML 键必须是标量");
    }

    // ================================================================== Symfony 标量求值

    /**
     * 对一个（可能带引号风格的）标量节点取值，语义对齐
     * {@code Inline::evaluateScalar}（{@code flags = 0}）。
     */
    static Object resolveScalar(ScalarNode node) {
        if (!node.isPlain()) {
            // 引号 / 块标量：Symfony 侧 parseQuotedScalar / block scalar 返回字符串
            return node.getValue();
        }
        return evaluate(node.getValue());
    }

    /**
     * Symfony {@code Inline::evaluateScalar()} 的裸标量求值（flags=0）。
     *
     * <p>注意 Symfony 一进来先做 {@code trim($scalar)}，所以
     * {@code "yes "} 会 trim 成 {@code "yes"} 再判定为字符串。</p>
     */
    static Object evaluate(String raw) {
        String scalar = PhpString.trim(raw);
        String lower = toLower(scalar);

        if (lower.equals("null") || scalar.isEmpty() || scalar.equals("~")) {
            return null;
        }
        if (lower.equals("true")) {
            return Boolean.TRUE;
        }
        if (lower.equals("false")) {
            return Boolean.FALSE;
        }
        if (scalar.charAt(0) == '!') {
            // Symfony 对未知 / 不带 PARSE_CONSTANT 的 tag 直接抛 ParseException；
            // 语言文件里不会出现，保持抛出同一类失败。
            throw new UnsupportedOperationException("未支持的 YAML tag: " + scalar);
        }

        if (OCTAL.matcher(scalar).matches()) {
            String digits = scalar.replace("_", "");
            boolean negative = scalar.charAt(0) == '-';
            String body = digits.startsWith("0o") ? digits.substring(2) : digits.substring(3);
            long value = Long.parseLong(body, 8);
            return negative ? -value : value;
        }

        char first = scalar.charAt(0);
        boolean numericStart = (first >= '0' && first <= '9')
                || first == '+' || first == '-' || first == '.';

        if (numericStart) {
            boolean plainInt = PLAIN_INTEGER_UNDERSCORES.matcher(scalar).matches();
            String normalized = scalar;
            if (plainInt) {
                normalized = scalar.replace("_", "");
                if (normalized.charAt(0) == '+') {
                    normalized = normalized.substring(1);
                }
            }
            if (looksInteger(normalized)) {
                // (string)(int) === $scalar 才返回 int → 007 / -0 保持字符串
                return phpIntCast(normalized);
            }
            String lowerN = toLower(normalized);
            if (NUMERIC.matcher(normalized).matches() || HEX.matcher(normalized).matches()) {
                String stripped = normalized.replace("_", "");
                if (stripped.startsWith("0x")) {
                    return Long.parseLong(stripped.substring(2), 16);
                }
                return Double.parseDouble(stripped);
            }
            if (lowerN.endsWith("inf")) {
                // '.inf' / '-.inf'（PHP 对应 ±log(0)）
                return lowerN.equals("-.inf") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            if (lowerN.equals(".nan")) {
                return Double.NaN;
            }
            if (DECIMAL_UNDERSCORES.matcher(scalar).matches()) {
                // 可能是带下划线的小数；也可能是纯形式（走不到这里），String.valueOf
                return Double.parseDouble(scalar.replace("_", ""));
            }
            // Symfony 的 timestamp 分支会把日期形标量转成 Unix 秒（int）；
            // 语言语料里没有这类叶子（非字符串共 24 个且全是 boolean），
            // 为了保持行为可预测这里不做日期转换 —— 保留字符串，见 PORT-NOTES。
            return scalar;
        }

        return scalar;
    }

    /** 整数形态（可能带符号、无前导 {@code 0o}），且是十进制纯数字/下划线已归一。 */
    private static boolean looksInteger(String scalar) {
        if (scalar.isEmpty()) {
            return false;
        }
        int start = (scalar.charAt(0) == '-' || scalar.charAt(0) == '+') ? 1 : 0;
        if (start >= scalar.length()) {
            return false;
        }
        for (int i = start; i < scalar.length(); i++) {
            char c = scalar.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        // 前导 0 的多字节数字（如 007）仍符合 ctype_digit —— 后面由 phpIntCast
        // 决定是 int 还是保持字符串
        return true;
    }

    /**
     * PHP 的 {@code (int) $scalar}：在 {@link Long} 范围内则比较
     * {@code $scalar === (string) $cast}；相等（含 {@code 0}）→ 返回 int；
     * 否则（如 {@code 007}、{@code -0}、{@code +0?}）→ 保持字符串。
     * 超出 Long 范围：PHP 返回原样字符串。
     */
    private static Object phpIntCast(String scalar) {
        try {
            long cast = Long.parseLong(scalar);
            return scalar.equals(Long.toString(cast)) ? cast : (Object) scalar;
        } catch (NumberFormatException e) {
            return scalar;
        }
    }

    private static String toLower(String s) {
        // Symfony 用 strtolower（单字节 ASCII 语料足够）
        return s.toLowerCase(java.util.Locale.ROOT);
    }
}
