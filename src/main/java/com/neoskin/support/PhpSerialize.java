package com.neoskin.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * PHP {@code serialize()} 的移植。
 *
 * <p>语料：{@code conformance/data/cookie.json} 的 {@code serialize.*}（含
 * {@code serialize-rec}，输入 {@code rec2}：自引用数组）。
 *
 * <p>与 {@code json_encode()} 的关键差别（都是「差一个字节就全错」的地方）：
 * <ul>
 *   <li>字符串长度按<b>字节</b>算：{@code "密码"} 是 {@code s:6:"密码";}，
 *       不是 {@code s:2:}；</li>
 *   <li>字符串内容<b>完全不转义</b>：引号、反斜杠、NUL 原样写进去
 *       （{@code a"b} → {@code s:3:"a"b";}）；</li>
 *   <li>{@code true} → {@code b:1;}、{@code false} → {@code b:0;}、{@code null} → {@code N;}
 *       —— 注意这是 {@code serialize()}，不是 {@code var_export()} 的 {@code true}/{@code NULL}；</li>
 *   <li>浮点用 {@code serialize_precision=-1} 的<b>最短往返</b>表示（与
 *       {@code json_encode()} 同一套数字串），只是指数符号是<b>大写 {@code E}</b>；
 *       {@code 1.0} → {@code d:1;}、{@code -0.0} → {@code d:-0;}；
 *       NAN / INF 直接写 {@code d:NAN;} / {@code d:INF;} / {@code d:-INF;}；</li>
 *   <li>数组的键按 PHP 规则归一化：{@code "0"} 变 int 键、{@code "01"} 保持字符串键。</li>
 * </ul>
 *
 * <h2>引用发射（{@code R:} / {@code r:}）</h2>
 *
 * <p>PHP 的 ref-zval 语义在 Java 树里无法完整复原（refcount 区分不了「同一实例出现
 * 两次」和「var 绑定」），这里按 php-src {@code php_add_var_hash} 的树级投影实现：</p>
 * <ul>
 *   <li>槽号 {@code n} 对每个<b>值</b>（含标量与根）在进入时 +1；键不计；
 *       根是槽 1；</li>
 *   <li>对象在第一次完整序列化时记下槽号、永不注销；再次出现（含作为祖先出现）→
 *       {@code r:<槽号>}；</li>
 *   <li>数组不在表里登记，但进行中的容器记在其自己的槽上：某值是<b>正被打开的
 *       祖先数组</b> → {@code R:<该祖先槽号>}（{@code rec2} 重建为
 *       {@code outer=[1,2,X]; X=[1,2,X]}，帧内的 {@code X} 就是自己的槽 4）；</li>
 *   <li>{@code Iterable} 仍先拷成数组再写，拷贝每层新建、天然共享不了实例，
 *       自引用 {@code Iterable} 会递归到底（应用路径不会产生它；《对应 PHP 的
 *       refcount-1 unwrap → {@code N;}》同样无法表达，已记入 PORT-NOTES 偏差）。</li>
 * </ul>
 *
 * <p>会话（{@code config/session.php} 的 {@code serialization = 'php'}）走的就是这条路径，
 * 所以它必须与 PHP 逐字节一致，否则重启后所有在线用户掉登录。
 */
public final class PhpSerialize {

    private PhpSerialize() {
    }

    /** 等价于 {@code serialize($value)}。 */
    public static String serialize(Object value) {
        return new Serializer().run(value);
    }

    /**
     * 有状态序列化器。槽号会计对应 {@code php_add_var_hash}：本值进入时
     * {@code data->n += 1}（标量也算；键不算）；重现时分开——按引用还原的
     * {@code R:} 回吐一个号，对象重现的 {@code r:} 已按 PHP 语义消耗一个号。
     */
    private static final class Serializer {

        /** 预分配的槽号计数（{@code data->n}）。 */
        private int n;

        /** 对象槽表，键为身份，永不注销（PHP 的 data->ht）。 */
        private final IdentityHashMap<Object, Integer> objectSlots = new IdentityHashMap<>();

        /** 正在写入的容器栈（数组与对象），与槽号配对。 */
        private final ArrayDeque<Object> openStack = new ArrayDeque<>();

        String run(Object root) {
            StringBuilder sb = new StringBuilder();
            write(sb, root);
            return sb.toString();
        }

        private void write(StringBuilder sb, Object value) {
            int slot = ++n;

            // 值是当前打开链上的某个容器（数组 or 对象在其自己的属性里出现）
            if (!openStack.isEmpty()) {
                for (Object open : openStack) {
                    if (open == value) {
                        // 引用重现：PHP 在 add_var_hash 命中时回吐 data->n。
                        n--;

                        if (open instanceof PhpUnserialize.PhpObject) {
                            // & 绑定的对象重现发 R:；普通 handle 共享是 r:。
                            // Java 树表达不了 & 绑定，对象一律走 r: 兜底——
                            // 若该对象从未记录过（不可能，自己是祖先必已记录），
                            // 保险起见也用自身槽号。
                            Integer objSlot = objectSlots.get(value);

                            sb.append("r:").append(objSlot != null ? objSlot : slot).append(';');
                        } else {
                            sb.append("R:").append(slot).append(';');
                        }
                        return;
                    }
                }
            }

            // ... 原有类型分发
            if (value == null) {
                sb.append("N;");
                return;
            }
            if (value instanceof Boolean b) {
                sb.append(b ? "b:1;" : "b:0;");
                return;
            }
            if (value instanceof Double || value instanceof Float) {
                sb.append("d:").append(doubleLiteral(((Number) value).doubleValue())).append(';');
                return;
            }
            if (value instanceof Number num) {
                sb.append("i:").append(num.longValue()).append(';');
                return;
            }
            if (value instanceof String s) {
                writeString(sb, s);
                return;
            }
            if (value instanceof PhpUnserialize.PhpObject obj) {
                writeObject(sb, obj, slot);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                writeArray(sb, map);
                return;
            }
            if (value instanceof Iterable<?> list) {
                PhpJson.Array array = new PhpJson.Array();
                long i = 0;
                for (Object item : list) {
                    array.put(i++, item);
                }
                writeArray(sb, array);
                return;
            }
            writeString(sb, PhpString.cast(value));
        }

        /** {@code O:<len>:"<class>":<n>:{props}}，对象槽号记在进入时。 */
        private void writeObject(StringBuilder sb, PhpUnserialize.PhpObject obj, int slot) {
            Integer seen = objectSlots.get(obj);

            if (seen != null) {
                // 已完成的 handle 重现消耗一个新号（PHP 不回吐）。
                sb.append("r:").append(seen).append(';');
                return;
            }

            objectSlots.put(obj, slot);
            sb.append("O:").append(obj.className().getBytes(StandardCharsets.UTF_8).length)
                    .append(":\"").append(obj.className()).append("\":");
            openStack.addLast(obj);
            writeProps(sb, obj.properties());
            openStack.removeLast();
        }

        private void writeArray(StringBuilder sb, Map<?, ?> raw) {
            sb.append("a:").append(raw.size()).append(":{");
            openStack.addLast(raw);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                Object key = PhpJson.normalizeKey(e.getKey());
                if (key instanceof Number num) {
                    sb.append("i:").append(num.longValue()).append(';');
                } else {
                    writeString(sb, PhpString.cast(key));
                }
                write(sb, e.getValue());
            }
            openStack.removeLast();
            sb.append('}');
        }

        private void writeProps(StringBuilder sb, Map<?, ?> props) {
            sb.append(props.size()).append(":{");
            for (Map.Entry<?, ?> e : props.entrySet()) {
                Object key = e.getKey();
                writeString(sb, PhpString.cast(key));
                write(sb, e.getValue());
            }
            sb.append('}');
        }

        /** {@code s:<字节长度>:"<原样内容>";} —— 内容不转义任何字符。 */
        private void writeString(StringBuilder sb, String s) {
            int bytes = s.getBytes(StandardCharsets.UTF_8).length;
            sb.append("s:").append(bytes).append(":\"").append(s).append("\";");
        }

        /**
         * {@code d:} 后面的数字串。
         *
         * <p>复用 {@link PhpJson#number(double)}（同一套 {@code serialize_precision=-1} 最短往返），
         * 只把指数字母换成大写。
         */
        private String doubleLiteral(double d) {
            if (Double.isNaN(d)) {
                return "NAN";
            }
            if (d == Double.POSITIVE_INFINITY) {
                return "INF";
            }
            if (d == Double.NEGATIVE_INFINITY) {
                return "-INF";
            }
            return PhpJson.number(d).replace('e', 'E');
        }
    }
}
