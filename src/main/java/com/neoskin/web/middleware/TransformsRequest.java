package com.neoskin.web.middleware;

import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpJson;
import com.neoskin.support.Str;

/**
 * {@code Illuminate\Foundation\Http\Middleware\TransformsRequest} 的移植基类。
 *
 * <p>源逻辑：对 query 参数（以及 JSON body 或表单 body）递归逐项调用
 * {@code transform($key, $value)}，键路径用 {@code .} 拼接（数组就是这样
 * {@code items.0.name}），并累计键长度，超过 20 MiB 抛
 * {@code PostTooLargeException}。</p>
 *
 * <p>本移植把「请求参数的三种来源」摊平成 {@link Bag} 的列表，中间件只读
 * {@code query / form / json}；写入侧由调用方决定（Vert.x 没有 ParameterBag）。</p>
 */
public abstract class TransformsRequest {

    /** {@code TransformsRequest::$maxKeyBytes}。 */
    protected static final long MAX_KEY_BYTES = 20L * 1024 * 1024;

    /** 一个参数包：来源名（{@code "query"} / {@code "form"} / {@code "json"}）+ 数据。 */
    public record Bag(String source, PhpJson.Array data) {
    }

    /** 键累计超限（≡ {@code PostTooLargeException}）。 */
    public static final class PostTooLargeException extends RuntimeException {
        public PostTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * {@code clean()}: 依序清洗 query →（json 或 form）。
     *
     * <p>源里的 {@code $request->request !== $request->query} 判断是「同一个
     * ParameterBag 就不重复洗」；Vert.x 侧两者天然不同对象，等价于「都洗」。</p>
     */
    protected void clean(List<Bag> bags) {
        long[] keyBytes = {0};

        for (Bag bag : bags) {
            if (bag.data() == null) {
                continue;
            }

            PhpJson.Array cleaned = cleanArray(bag.data(), "", keyBytes);
            bag.data().clear();
            bag.data().putAll(cleaned);
        }
    }

    /** {@code cleanArray($data, $keyPrefix)}。 */
    private PhpJson.Array cleanArray(PhpJson.Array data, String keyPrefix, long[] keyBytes) {
        PhpJson.Array out = new PhpJson.Array();

        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            String key = String.valueOf(entry.getKey());

            keyBytes[0] += keyPrefix.length() + keyBytesOf(key);

            if (keyBytes[0] > MAX_KEY_BYTES) {
                throw new PostTooLargeException("The cumulative size of the input keys is too large.");
            }

            out.put(entry.getKey(), cleanValue(keyPrefix + key, entry.getValue(), keyBytes));
        }

        return out;
    }

    /** {@code cleanValue()}: 数组递归（键前缀加 {@code .}），标量走 transform。 */
    private Object cleanValue(String key, Object value, long[] keyBytes) {
        if (value instanceof PhpJson.Array array) {
            return cleanArray(array, key + ".", keyBytes);
        }

        return transform(key, value);
    }

    /** {@code mb_strlen}-等价的字节数（PHP 的 {@code strlen} 是字节数）。 */
    private static long keyBytesOf(String key) {
        return key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** {@code transform($key, $value)}：子类实现具体变换。 */
    protected abstract Object transform(String key, Object value);

    /**
     * {@code TrimStrings::transform()}：键不命中 except 且值是字符串才裁；
     * 裁的是 Laravel 的 {@code Str::trim}（含不可见字符），不是 PHP 的 {@code trim}。
     */
    public static final class TrimStrings extends TransformsRequest {

        /** {@code TrimStrings::$except}。 */
        private static final List<String> EXCEPT = List.of(
                "current_password", "password", "password_confirmation");

        /** {@code TrimStrings::$neverTrim}（{@code except()} 注册的全局名单）。 */
        private static final java.util.List<String> NEVER_TRIM = new java.util.ArrayList<>();

        /** {@code TrimStrings::except()}：追加全局不裁名单。 */
        public static void except(List<String> attributes) {
            for (String attribute : attributes) {
                if (!NEVER_TRIM.contains(attribute)) {
                    NEVER_TRIM.add(attribute);
                }
            }
        }

        /** {@code flushState()}（测试用）。 */
        public static void flushState() {
            NEVER_TRIM.clear();
        }

        @Override
        protected Object transform(String key, Object value) {
            java.util.List<String> all = new java.util.ArrayList<>(EXCEPT);
            all.addAll(NEVER_TRIM);

            if (Str.is(all, key) || !(value instanceof String s)) {
                return value;
            }

            return Str.trim(s);
        }
    }

    /** {@code ConvertEmptyStringsToNull::transform()}：空串 → null。 */
    public static final class ConvertEmptyStringsToNull extends TransformsRequest {

        @Override
        protected Object transform(String key, Object value) {
            return "".equals(value) ? null : value;
        }
    }
}
