package com.neoskin.web.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpUrl;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * 请求参数视图 + 全局栈归一化中间件的 Vert.x 接线。
 *
 * <p>Laravel 的 {@code Request} 有 query / request(form) / json 三个 ParameterBag，
 * 全局栈对它们做 {@code TrimStrings} 与 {@code ConvertEmptyStringsToNull}。
 * Vert.x 侧没有这些对象，本类负责：</p>
 * <ol>
 *   <li>把 Vert.x 的 query 字符串与 body（表单或 JSON）解析成 {@link PhpJson.Array}；</li>
 *   <li>按 Laravel 顺序调用 {@link TransformsRequest} 子类（Trim → EmptyToNull）；</li>
 *   <li>把归一化后的参数挂到 {@code ctx["query"]} / {@code ctx["input"]}，
 *       供控制器与后续中间件读取。</li>
 * </ol>
 *
 * <p>顺序敏感：源全局栈是 {@code ValidatePostSize → TrimStrings →
 * ConvertEmptyStringsToNull → DetectLanguagePrefer}，所以先裁后转 null
 * （否则「全是空格」的字段会变成 {@code ""}，而不是 {@code null}）。</p>
 */
public final class RequestInput {

    /** query 参数在 ctx 里的键。 */
    public static final String QUERY_KEY = "query";

    /** body 参数（表单 / JSON，已归一化）在 ctx 里的键。 */
    public static final String INPUT_KEY = "input";

    /** multipart 的文件部件在 ctx 里的键（PHP 的 {@code $_FILES}）。 */
    public static final String FILES_KEY = "input_files";

    /** {@code $_FILES[$key]}。 */
    public static com.neoskin.web.http.UploadedFile file(RoutingContext ctx, String key) {
        Object files = ctx.get(FILES_KEY);
        Object value = null;

        if (files instanceof PhpJson.Array arr) {
            value = arr.get(key);
        }

        return value instanceof com.neoskin.web.http.UploadedFile f ? f : null;
    }

    /** {@code ValidatePostSize} 的配置：post_max_size 字节数（0 = 不限制）。 */
    private static final long POST_MAX_SIZE = parsePostMaxSize(System.getProperty(
            "neoskin.post_max_size", "8M"));

    private RequestInput() {
    }

    /**
     * {@code getPostMaxSize()}：PHP ini 语义（{@code 8M} / {@code 512K} / 裸字节）。
     *
     * <p>{@code is_numeric} 优先 → 直接当字节数；否则看末位单位
     * {@code K/M/G}（大小写无关，末尾单位不在其内就按裸字节）。</p>
     */
    public static long parsePostMaxSize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }

        if (isNumeric(raw)) {
            return Long.parseLong(raw);
        }

        char metric = Character.toUpperCase(raw.charAt(raw.length() - 1));
        String number = raw.substring(0, raw.length() - 1);

        long value = 0;

        try {
            value = Long.parseLong(number.trim());
        } catch (NumberFormatException e) {
            return 0;
        }

        return switch (metric) {
            case 'K' -> value * 1024;
            case 'M' -> value * 1048576;
            case 'G' -> value * 1073741824;
            default -> value;
        };
    }

    /** PHP 的 {@code is_numeric}（本用途只需十进制整数/小数形态）。 */
    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code ValidatePostSize::handle()}：CONTENT_LENGTH > post_max_size → 超限。 */
    public static boolean postTooLarge(long contentLength) {
        return POST_MAX_SIZE > 0 && contentLength > POST_MAX_SIZE;
    }

    /**
     * 归一化中间件（全局栈）：校验体积 → 解析 → Trim → EmptyToNull → 挂 ctx。
     *
     * <p>体积超限在源里抛 {@code PostTooLargeException}（渲染成 413）。</p>
     */
    public static Handler<RoutingContext> normalizer() {
        return ctx -> {
            String contentLength = ctx.request().getHeader("content-length");

            if (contentLength != null && postTooLarge(Long.parseLong(contentLength.trim()))) {
                ctx.response().setStatusCode(413).end(
                        "{\"code\":413,\"message\":\"The POST data is too large.\"}");

                return;
            }

            PhpJson.Array query = parseQuery(ctx.request().query());
            ctx.put(QUERY_KEY, query);

            // 只有请求体可能被读的动词/内容类型才取 body；GET 不读（避免挂住）。
            String contentType = ctx.request().getHeader("content-type");
            boolean hasBody = contentType != null;

            if (!hasBody) {
                ctx.put(INPUT_KEY, new PhpJson.Array());
                ctx.next();

                return;
            }

            ctx.request().body().onSuccess(buffer -> {
                byte[] raw = buffer.getBytes();

                if (contentType.toLowerCase().startsWith("multipart/form-data")) {
                    com.neoskin.web.http.MultipartCodec.Result multipart =
                            com.neoskin.web.http.MultipartCodec.parse(raw, contentType);
                    PhpJson.Array input = multipart.fields();
                    ctx.put(FILES_KEY, multipart.files());

                    PhpJson.Array queryBag = new PhpJson.Array(query);
                    List<TransformsRequest.Bag> bags = List.of(
                            new TransformsRequest.Bag("query", queryBag),
                            new TransformsRequest.Bag("input", input));

                    try {
                        new TransformsRequest.TrimStrings().clean(bags);
                        new TransformsRequest.ConvertEmptyStringsToNull().clean(bags);
                    } catch (TransformsRequest.PostTooLargeException e) {
                        ctx.response().setStatusCode(413).end(
                                "{\"code\":413,\"message\":\"The cumulative size of the input keys is too large.\"}");

                        return;
                    }

                    ctx.put(QUERY_KEY, queryBag);
                    ctx.put(INPUT_KEY, input);
                    ctx.next();

                    return;
                }

                PhpJson.Array input = parseBody(contentType, buffer.toString());

                PhpJson.Array queryBag = new PhpJson.Array(query);
                List<TransformsRequest.Bag> bags = List.of(
                        new TransformsRequest.Bag("query", queryBag),
                        new TransformsRequest.Bag("input", input));

                try {
                    new TransformsRequest.TrimStrings().clean(bags);
                    new TransformsRequest.ConvertEmptyStringsToNull().clean(bags);
                } catch (TransformsRequest.PostTooLargeException e) {
                    ctx.response().setStatusCode(413).end(
                            "{\"code\":413,\"message\":\"The cumulative size of the input keys is too large.\"}");

                    return;
                }

                ctx.put(QUERY_KEY, queryBag);
                ctx.put(INPUT_KEY, input);
                ctx.next();
            }).onFailure(err -> ctx.fail(err));
        };
    }

    /** 表单串 → 数组（{@code a[b]=1} 展开成嵌套，与 PHP 的 {@code $_POST} 一致）。 */
    static PhpJson.Array parseQuery(String raw) {
        PhpJson.Array out = new PhpJson.Array();

        if (raw == null || raw.isEmpty()) {
            return out;
        }

        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);

            putNested(out, PhpUrl.decodeComponent(key.replace('+', ' ')),
                    PhpUrl.decodeComponent(value.replace('+', ' ')));
        }

        return out;
    }

    /**
     * body → 数组：{@code application/json} 走 JSON（{@code json_decode(..., true)}），
     * 其余按表单串；解析失败得空数组（源里 {@code json()} 失败也是空参数）。
     */
    static PhpJson.Array parseBody(String contentType, String body) {
        if (body == null || body.isEmpty()) {
            return new PhpJson.Array();
        }

        if (contentType != null && contentType.toLowerCase().startsWith("application/json")) {
            try {
                Object decoded = PhpJson.decode(body);

                if (decoded instanceof PhpJson.Array array) {
                    return array;
                }

                return new PhpJson.Array();
            } catch (IllegalArgumentException e) {
                return new PhpJson.Array();
            }
        }

        if (contentType != null && contentType.toLowerCase()
                .startsWith("application/x-www-form-urlencoded")) {
            return parseQuery(body);
        }

        return new PhpJson.Array();
    }

    /** {@code a[b][]} 形态的键展开（PHP 的 parse_str 语义，够用即可）。 */
    public static void putNested(PhpJson.Array root, String key, Object value) {
        int bracket = key.indexOf('[');

        if (bracket < 0) {
            root.put(key, value);

            return;
        }

        String head = key.substring(0, bracket);

        // 拆出所有 [..] 段
        List<String> path = new ArrayList<>();
        path.add(head);

        int i = bracket;

        while (i < key.length()) {
            int open = key.indexOf('[', i);
            int close = key.indexOf(']', open + 1);

            if (open < 0 || close < 0) {
                break;
            }

            path.add(key.substring(open + 1, close));
            i = close + 1;
        }

        PhpJson.Array cursor = root;
        Object existing = root.get(head);

        if (existing instanceof PhpJson.Array array) {
            cursor = array;
        } else {
            PhpJson.Array created = new PhpJson.Array();
            root.put(head, created);
            cursor = created;
        }

        for (int index = 1; index < path.size(); index++) {
            String segment = path.get(index);
            boolean last = index == path.size() - 1;

            if (last) {
                if (segment.isEmpty()) {
                    appendPhp(cursor, value);
                } else {
                    cursor.put(segment, value);
                }

                return;
            }

            // 中间段：空段 = 追加新数组
            Object next = segment.isEmpty() ? null : cursor.get(segment);

            if (next instanceof PhpJson.Array array) {
                cursor = array;
            } else {
                PhpJson.Array created = new PhpJson.Array();

                if (segment.isEmpty()) {
                    appendPhp(cursor, created);
                } else {
                    cursor.put(segment, created);
                }

                cursor = created;
            }
        }
    }

    /** PHP {@code $arr[] = $value}：追加到最大整数键之后。 */
    private static void appendPhp(PhpJson.Array array, Object value) {
        long max = -1;

        for (Object key : array.keySet()) {
            if (key instanceof Long l && l > max) {
                max = l;
            }
        }

        array.put(max + 1, value);
    }

    /** 便捷读取：query 参数（{@code Request::query()}）。 */
    public static Object query(RoutingContext ctx, String key) {
        Object bag = ctx.get(QUERY_KEY);

        return bag instanceof PhpJson.Array array ? array.get(key) : null;
    }

    /** 便捷读取：{@code Request::input()}（body 优先，回落 query）。 */
    public static Object input(RoutingContext ctx, String key) {
        Object body = ctx.get(INPUT_KEY);

        if (body instanceof PhpJson.Array array && array.containsKey(key)) {
            return array.get(key);
        }

        return query(ctx, key);
    }

    /** {@code Request::input()} 的字符串视图（PHP 会把标量转成字符串）。 */
    public static String inputString(RoutingContext ctx, String key) {
        Object value = input(ctx, key);

        return value == null ? null : com.neoskin.support.PhpString.cast(value);
    }

    /** 便于测试：把 Map 塞成 ctx 参数包。 */
    static PhpJson.Array toArray(Map<?, ?> map) {
        PhpJson.Array array = new PhpJson.Array();
        map.forEach(array::put);

        return array;
    }
}
