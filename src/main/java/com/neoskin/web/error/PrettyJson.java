package com.neoskin.web.error;

import java.util.Map;

import com.neoskin.support.PhpJson;

/**
 * {@code json_encode($v, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)} 的等价实现。
 *
 * <p>只服务一件事：Laravel 异常处理器 {@code prepareJsonResponse()} 的 4 空格
 * 缩进输出。{@link PhpJson#encode} 不支持 pretty 标志，而 pretty 的空白规则又必须
 * 逐字节对齐（前端与测试都直接比对响应体），所以单独实现。</p>
 *
 * <p>规则来自 PHP 的 {@code php_json_encode}：</p>
 * <ul>
 *   <li>缩进固定 <b>4 空格</b>；</li>
 *   <li>对象/数组的每个元素单独一行，冒号后一个空格 {@code ": "}；</li>
 *   <li>空数组输出 {@code []}、空对象输出 {@code {}}，都<b>不换行</b>；</li>
 *   <li>字符串转义与压缩模式完全一致（复用 {@link PhpJson} 的 {@code /} 与
 *       unicode 处理，故这里只处理标量之外的排版）。</li>
 * </ul>
 *
 * <p>实测基准（{@code /nope} 的 404 JSON，共 95 字节）：
 * <pre>
 * {
 *     "message": "The route nope could not be found.",
 *     "exception": true,
 *     "trace": []
 * }
 * </pre>
 */
public final class PrettyJson {

    /** PHP 的 {@code JSON_PRETTY_PRINT} 固定用 4 个空格。 */
    private static final String INDENT = "    ";

    private PrettyJson() {
    }

    /** {@code json_encode($value, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)}。 */
    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            // PHP 的空数组恒为 []（"数组还是对象" 只看键是否 0..n-1，空表符合），
            // 与压缩模式的口径一致 —— 空对象在 PHP 里也是空数组。
            if (map.isEmpty()) {
                sb.append("[]");
                return;
            }
            // 键归一化交给 Array 的 put（"1" → int 键 1），与压缩模式口径一致
            PhpJson.Array normalized = new PhpJson.Array();
            map.forEach(normalized::put);

            // PHP 的「数组还是对象」只看键是否 0..n-1 连续
            if (PhpJson.isList(normalized)) {
                sb.append('[');
                boolean first = true;
                for (Object item : normalized.values()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    newline(sb, depth + 1);
                    write(sb, item, depth + 1);
                }
                newline(sb, depth);
                sb.append(']');
                return;
            }

            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : normalized.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, depth + 1);
                sb.append(PhpJson.encode(String.valueOf(e.getKey()),
                        PhpJson.JSON_UNESCAPED_SLASHES));
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
            }
            newline(sb, depth);
            sb.append('}');
            return;
        }

        if (value instanceof Iterable<?> list) {
            PhpJson.Array array = new PhpJson.Array();
            long i = 0;
            for (Object item : list) {
                array.put(i++, item);
            }
            write(sb, array, depth);
            return;
        }

        // 标量：与压缩模式完全一致
        sb.append(PhpJson.encode(value, PhpJson.JSON_UNESCAPED_SLASHES));
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n');
        sb.append(INDENT.repeat(depth));
    }
}
