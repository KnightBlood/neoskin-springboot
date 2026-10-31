package com.neoskin.service.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.neoskin.conformance.Corpus;
import com.neoskin.service.cipher.PhpPasswordInfo;
import com.neoskin.service.cipher.PhpPasswordVerify;

import tools.jackson.databind.JsonNode;

/**
 * {@link LaravelHash}（{@code Hash} 门面的 bcrypt 驱动）的语料驱动测试。
 *
 * <p>数据源 {@code conformance/data/hash.json}，共 219 条，分四组：
 * <ul>
 *   <li>{@code get_info}（132）：{@code password_get_info()} 的 {@code algo} /
 *       {@code algoName} / {@code options} / {@code isHashed}；其中
 *       {@code costParse.*}（20）与 {@code structure.*}（6）与
 *       {@code argon2Params.*}（78）是<b>规格的真正核心</b>——它们钉死
 *       「cost 用 C 的 {@code sscanf("%ld")} 解析」和「argon2 参数逐段扫描」两条规则；</li>
 *   <li>{@code needs_rehash}（54）：{@code password_needs_rehash()}，含同样一批
 *       {@code costParse.*} 串在 cost=10/12 两个目标下的结果；</li>
 *   <li>{@code verify}（24）：{@code password_verify()} 的接受面；</li>
 *   <li>{@code const}（9）：PHP 常量与实现里的默认值必须一致。</li>
 * </ul>
 *
 * <p>为什么这些畸形串值得单独测：源项目 {@code 2026_10_25_000001} 迁移脚本用
 * {@code Hash::needsRehash()} 决定要不要重写 {@code oauth_clients.secret}。
 * 一个「畸形但 60 字节、以 {@code $2y$} 开头」的串可能被判成「无需重算」而被跳过。
 * 这是 PHP 的真实行为，四个移植项目必须逐字一致，否则同一份数据库在不同实现间
 * 迁移会得到不同的结果。
 */
class LaravelHashTest {

    // ------------------------------------------------------------------ 手写冒烟

    @Test
    void makeProducesPhpCompatibleHash() {
        LaravelHash hash = new LaravelHash(10);
        String made = hash.make("plain-secret");
        assertTrue(made.startsWith("$2y$10$"), "实际: " + made);
        assertEquals(60, made.length());
        assertTrue(hash.check("plain-secret", made));
        assertFalse(hash.check("plain-secretX", made));
        assertTrue(LaravelHash.isHashed(made));
        assertFalse(hash.needsRehash(made));
    }

    @Test
    void roundsAreClampedToPhpRange() {
        assertEquals(4, new LaravelHash(0).rounds());
        assertEquals(31, new LaravelHash(99).rounds());
    }

    @Test
    void checkRejectsEmptyHash() {
        LaravelHash hash = new LaravelHash(10);
        assertFalse(hash.check("x", null));
        assertFalse(hash.check("x", ""));
    }

    // ------------------------------------------------------------------ get_info

    /** {@code password_get_info()}：132 条。 */
    @TestFactory
    Stream<DynamicTest> corpusGetInfo() {
        Corpus corpus = Corpus.require("hash");
        return corpus.cases("get_info").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String hash = Corpus.textOrEmpty(c, "hash");
                    String id = Corpus.text(c, "id");

                    assertEquals(Corpus.text(c, "expected_algo"),
                            PhpPasswordInfo.algorithm(hash), id + " algo");
                    assertEquals(Corpus.text(c, "expected_algo_name"),
                            PhpPasswordInfo.algorithmName(hash), id + " algoName");
                    assertEquals(Corpus.bool(c, "expected_is_hashed", false),
                            PhpPasswordInfo.isHashed(hash), id + " isHashed");
                    assertEquals(Corpus.bool(c, "expected_is_hashed", false),
                            LaravelHash.isHashed(hash), id + " Hash::isHashed");
                    assertOptions(c, hash, id);
                }));
    }

    /**
     * 比对 {@code expected_options}。
     *
     * <p>语料里它有三种形状：
     * <ul>
     *   <li>{@code []}（JSON 数组）：算法无法识别，PHP 返回空数组；</li>
     *   <li>{@code {"cost": N}}：bcrypt，N 可能是 int64 的极值（溢出用例），
     *       所以必须用 {@link Corpus#longValue} 而不是 {@code asInt()}；</li>
     *   <li>{@code {"memory_cost":…,"time_cost":…,"threads":…}}：argon2。</li>
     * </ul>
     */
    private static void assertOptions(JsonNode c, String hash, String id) {
        JsonNode options = c.get("expected_options");
        assertTrue(options != null, id + " 缺少 expected_options");

        if (options.isArray()) {
            // 未知算法：PHP 的 options 是空数组
            assertTrue(PhpPasswordInfo.algorithm(hash) == null, id + " 预期无法识别");
            return;
        }
        if (options.has("cost")) {
            assertEquals(Corpus.longValue(options, "cost", Long.MIN_VALUE),
                    PhpPasswordInfo.bcryptCost(hash), id + " cost");
            return;
        }
        PhpPasswordInfo.Argon2Options actual = PhpPasswordInfo.argon2Options(hash);
        assertEquals(Corpus.longValue(options, "memory_cost", Long.MIN_VALUE),
                actual.memoryCost(), id + " memory_cost");
        assertEquals(Corpus.longValue(options, "time_cost", Long.MIN_VALUE),
                actual.timeCost(), id + " time_cost");
        assertEquals(Corpus.longValue(options, "threads", Long.MIN_VALUE),
                actual.threads(), id + " threads");
    }

    // ------------------------------------------------------------------ needs_rehash

    /** {@code password_needs_rehash()}：54 条，cost 目标为 10 与 12。 */
    @TestFactory
    Stream<DynamicTest> corpusNeedsRehash() {
        Corpus corpus = Corpus.require("hash");
        return corpus.cases("needs_rehash").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String hash = Corpus.textOrEmpty(c, "hash");
                    int cost = Corpus.integer(c, "cost", -1);
                    boolean expected = Corpus.bool(c, "expected", false);
                    // 只有 10 / 12，都落在 PHP 合法的 4..31 内，clamp 不会改变行为
                    assertEquals(expected, new LaravelHash(cost).needsRehash(hash));
                    assertEquals(expected, PhpPasswordInfo.needsRehash(hash, cost));
                }));
    }

    // ------------------------------------------------------------------ verify

    /** {@code password_verify()}：24 条，含 59/61/62 字节长度边界与 {@code $2z$}/{@code $2Y$}。 */
    @TestFactory
    Stream<DynamicTest> corpusVerify() {
        Corpus corpus = Corpus.require("hash");
        return corpus.cases("verify").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String value = Corpus.textOrEmpty(c, "value");
                    String hash = Corpus.textOrEmpty(c, "hash");
                    boolean expected = Corpus.bool(c, "expected", false);
                    assertEquals(expected, PhpPasswordVerify.verify(value, hash));
                    assertEquals(expected, new LaravelHash(12).check(value, hash));
                }));
    }

    // ------------------------------------------------------------------ const

    /** PHP 常量必须与 Java 侧常量逐一相等，防止某天「顺手调一下默认值」。 */
    @TestFactory
    Stream<DynamicTest> corpusConstants() {
        Corpus corpus = Corpus.require("hash");
        return corpus.cases("const").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String id = Corpus.text(c, "id");
                    switch (id) {
                        case "const.PASSWORD_BCRYPT" -> assertEquals(
                                Corpus.text(c, "value"), PhpPasswordInfo.BCRYPT);
                        case "const.PASSWORD_ARGON2I" -> assertEquals(
                                Corpus.text(c, "value"), PhpPasswordInfo.ARGON2I);
                        case "const.PASSWORD_ARGON2ID" -> assertEquals(
                                Corpus.text(c, "value"), PhpPasswordInfo.ARGON2ID);
                        // PHP 8.5 的 PASSWORD_DEFAULT 仍是 bcrypt，故与 PASSWORD_BCRYPT 同值
                        case "const.PASSWORD_DEFAULT" -> assertEquals(
                                PhpPasswordInfo.BCRYPT, Corpus.text(c, "value"));
                        case "const.bcryptLength" -> assertEquals(
                                Corpus.longValue(c, "value", -1), PhpPasswordInfo.BCRYPT_LENGTH);
                        case "const.bcryptDefaultCost" -> assertEquals(
                                Corpus.longValue(c, "value", -1),
                                PhpPasswordInfo.BCRYPT_DEFAULT_COST);
                        case "const.argon2DefaultMemory" -> assertEquals(
                                Corpus.longValue(c, "value", -1),
                                PhpPasswordInfo.ARGON2_DEFAULT_MEMORY);
                        case "const.argon2DefaultTime" -> assertEquals(
                                Corpus.longValue(c, "value", -1),
                                PhpPasswordInfo.ARGON2_DEFAULT_TIME);
                        case "const.argon2DefaultThreads" -> assertEquals(
                                Corpus.longValue(c, "value", -1),
                                PhpPasswordInfo.ARGON2_DEFAULT_THREADS);
                        default -> throw new IllegalArgumentException("未知常量用例: " + id);
                    }
                }));
    }
}
