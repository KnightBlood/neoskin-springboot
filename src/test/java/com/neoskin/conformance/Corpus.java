package com.neoskin.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.opentest4j.TestAbortedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 读取语言中立的语料库 {@code conformance/data/*.json}。
 *
 * <p>语料库由 {@code conformance/oracle/run-all.php} 在 PHP 侧生成，是
 * <b>唯一权威</b>的期望值来源：Go / Rust / Java / .NET 四个移植项目共用同一批 JSON，
 * 而不是各自再跑一遍 PHP 重新推导。这样做的原因见 {@code conformance/README.md}。
 *
 * <p>JSON <b>不得手改</b>：{@code MANIFEST.json} 记录了每个文件的 SHA-256，
 * 手改会让 {@code run-all.php --check} 失败。
 *
 * <p>本类只在测试里用。语料库不存在时（例如只 checkout 了子项目）不报失败，
 * 而是让用例 <b>skip</b> 并给出说明，避免把「没拿到语料库」误报成「代码有 bug」。
 */
public final class Corpus {

    /** 语料库根目录，允许用系统属性覆盖（CI 里可以指向别处）。 */
    private static final String DATA_PROPERTY = "neoskin.conformance.data";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String name;
    private final JsonNode root;

    private Corpus(String name, JsonNode root) {
        this.name = name;
        this.root = root;
    }

    /**
     * 定位 {@code conformance/data} 目录。
     *
     * <p>Surefire 的工作目录是模块根目录（{@code NeoSkinSpringBoot}），
     * 所以默认值 {@code ../conformance/data} 正好是同级目录。
     */
    public static Path dataDir() {
        String override = System.getProperty(DATA_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return Paths.get("..", "conformance", "data").toAbsolutePath().normalize();
    }

    /** 语料库是否可用（用于 {@code assumeTrue} 风格的跳过）。 */
    public static boolean available() {
        return Files.isDirectory(dataDir());
    }

    /**
     * 载入一份语料，例如 {@code "cipher"}、{@code "hash"}、{@code "schema"}。
     *
     * @throws TestAbortedException 语料库或该文件不存在时，让用例 skip 而不是 fail
     */
    public static Corpus require(String name) {
        Path dir = dataDir();
        if (!Files.isDirectory(dir)) {
            throw new TestAbortedException(
                    "未找到语料库目录 " + dir + "；跳过。生成方式见 conformance/README.md §4。");
        }
        Path file = dir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            throw new TestAbortedException("未找到语料文件 " + file + "；跳过。");
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return new Corpus(name, MAPPER.readTree(text));
        } catch (IOException e) {
            throw new UncheckedIOException("读取语料失败: " + file, e);
        }
    }

    /** 语料文件名（不含扩展名）。 */
    public String name() {
        return name;
    }

    /** 全部用例，顺序与 JSON 一致。 */
    public List<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        root.path("cases").forEach(out::add);
        return out;
    }

    /** 用例总数。 */
    public int size() {
        return root.path("cases").size();
    }

    /** 按 {@code op} 过滤，例如 {@code "hash"} / {@code "verify"} / {@code "get_info"}。 */
    public List<JsonNode> cases(String op) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : cases()) {
            if (op.equals(text(c, "op"))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 按 id 取单条用例。
     *
     * @throws IllegalArgumentException id 不存在时抛出（写错 id 必须立刻暴露，不能静默 skip）
     */
    public JsonNode caseById(String id) {
        for (JsonNode c : cases()) {
            if (id.equals(text(c, "id"))) {
                return c;
            }
        }
        throw new IllegalArgumentException("语料 " + name + ".json 里没有用例: " + id);
    }

    // ------------------------------------------------------------------ 取值辅助

    /** 取字符串字段，缺失或 {@code null} 返回 {@code null}（区别于 {@code ""}）。 */
    public static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** 取字符串字段，缺失或 {@code null} 返回空串。 */
    public static String textOrEmpty(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? "" : v;
    }

    /** 取布尔字段，缺失时用 {@code fallback}。 */
    public static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asBoolean();
    }

    /** 取整数字段，缺失时用 {@code fallback}。 */
    public static int integer(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asInt();
    }

    /**
     * 取 {@code long} 字段，缺失时用 {@code fallback}。
     *
     * <p>不能复用 {@link #integer}：PHP 的 cost 溢出用例会返回
     * {@code 9223372036854775807} / {@code -9223372036854775808}，
     * 用 {@code asInt()} 会静默截断，测试反而「通过」。
     */
    public static long longValue(JsonNode node, String field, long fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asLong();
    }

    /** 取数组字段的字符串列表，缺失时返回空列表。 */
    public static List<String> texts(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        node.path(field).forEach(n -> out.add(n.asString()));
        return out;
    }

    @Override
    public String toString() {
        return "Corpus[" + name + ", cases=" + size() + "]";
    }
}
