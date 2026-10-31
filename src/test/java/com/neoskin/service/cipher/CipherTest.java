package com.neoskin.service.cipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.neoskin.conformance.Corpus;
import com.neoskin.config.NeoSkinSettings;

import tools.jackson.databind.JsonNode;

/**
 * 密码算法移植的回归测试。
 *
 * <p>分两层：
 * <ol>
 *   <li><b>手写用例</b>（{@code @Test}）：少量固定样本，便于快速定位；</li>
 *   <li><b>语料驱动用例</b>（{@code @TestFactory}）：直接吃
 *       {@code conformance/data/cipher.json} 的 144 条用例。语料由 PHP 侧
 *       {@code conformance/oracle/gen-cipher.php} 实例化源项目真实类生成，
 *       是四个移植项目<b>共用</b>的权威期望值，Java 侧不再自行推导。</li>
 * </ol>
 *
 * <p>语料缺失时整组用例 skip（{@link Corpus#require(String)} 抛
 * {@code TestAbortedException}），不会误报成代码 bug。
 */
class CipherTest {

    private static final String VALUE = "BlessingSkin#2026";
    private static final String SALT = "salt-xyz";

    // ---------------------------------------------------------------- 摘要类

    @Test
    void md5MatchesPhp() {
        Cipher c = new DigestCipher.Md5();
        assertEquals("b716d5e2a85e05ac097a044c2ce3f954", c.hash(VALUE, SALT));
        assertTrue(c.verify(VALUE, "b716d5e2a85e05ac097a044c2ce3f954", SALT));
        assertFalse(c.verify("wrong", "b716d5e2a85e05ac097a044c2ce3f954", SALT));
        // PHP md5('') = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", c.hash(null, null));
    }

    @Test
    void salted2Md5MatchesPhp() {
        Cipher c = new DigestCipher.Salted2Md5();
        assertEquals("ed8e9cd1c0a0993e91dc50f741c02782", c.hash(VALUE, SALT));
        assertTrue(c.verify(VALUE, "ed8e9cd1c0a0993e91dc50f741c02782", SALT));
        // salt 参与计算：换个 salt 结果必须不同
        assertFalse(c.verify(VALUE, "ed8e9cd1c0a0993e91dc50f741c02782", "other-salt"));
    }

    @Test
    void sha256MatchesPhp() {
        Cipher c = new DigestCipher.Sha256();
        assertEquals("827c67126f3c28202389de713bb60842543fce8445cfc050115989b190b41f49",
                c.hash(VALUE, SALT));
    }

    @Test
    void salted2Sha256MatchesPhp() {
        Cipher c = new DigestCipher.Salted2Sha256();
        assertEquals("e13150c80ecb308647c93ed7071e40101e13b5e1a16ae3d604b6dcee4e8017db",
                c.hash(VALUE, SALT));
    }

    @Test
    void sha512MatchesPhp() {
        Cipher c = new DigestCipher.Sha512();
        assertEquals("1448a51ed4d0e19d12265f2a98d0fc03bba952ecf9e9aacf3fee2e9e8aa94973"
                + "2adf0a8f21da25c2821132aa82133a248ddcaa599a44b6062a4924d7885da165",
                c.hash(VALUE, SALT));
    }

    @Test
    void salted2Sha512MatchesPhp() {
        Cipher c = new DigestCipher.Salted2Sha512();
        assertEquals("22c7ae80a47e03937a153044b1e9b36132de2adf0ca47a43ae82f8ddfdfce41"
                + "48bff8e288606a087cd938cf875e87c7666ea94d8805cd5fc64445b4484d5b105",
                c.hash(VALUE, SALT));
    }

    // ---------------------------------------------------------------- bcrypt

    @Test
    void bcryptProducesPhpCompatibleHash() {
        Cipher c = new BcryptCipher(12);
        String hash = c.hash(VALUE, SALT);
        // PHP 8.5 password_hash(PASSWORD_BCRYPT) 产出 $2y$12$...
        assertTrue(hash.startsWith("$2y$12$"), "实际: " + hash);
        assertEquals(60, hash.length());
        assertTrue(c.verify(VALUE, hash, SALT));
        assertFalse(c.verify("wrong", hash, SALT));
    }

    @Test
    void bcryptVerifiesExistingPhpHash() {
        // 由 PHP 8.5.12 生成：password_hash('test', PASSWORD_BCRYPT)
        String phpHash = "$2y$12$LH3qUjkbX5qhoVATr5VGtuUFENUXy6y82uDr3tTsEsrl0b1H8V2XK";
        Cipher c = new BcryptCipher(12);
        assertTrue(c.verify("test", phpHash, SALT));
        assertFalse(c.verify("Test", phpHash, SALT));
    }

    @Test
    void bcryptAcceptsLegacyCost10Hashes() {
        // 老库（PHP 7.x / 8.0 ~ 8.3 时期）里的 $2y$10$ 必须还能登录。
        // 该串由 PHP 8.5.12 生成：password_hash('test', PASSWORD_BCRYPT, ['cost' => 10])
        String legacy = "$2y$10$UmsfrTKZrlhhDfcwJIiUz.jH7/OFdrzVL8RWDmU2u7gXdt/JLj0W.";
        Cipher c = new BcryptCipher(12);
        assertTrue(c.verify("test", legacy, SALT));
        assertFalse(c.verify("Test", legacy, SALT));
        // 新哈希用当前默认 cost 12
        assertTrue(c.hash("test", SALT).startsWith("$2y$12$"));
    }

    @Test
    void bcryptRejectsMalformedHash() {
        Cipher c = new BcryptCipher(12);
        assertFalse(c.verify("test", "", SALT));
        assertFalse(c.verify("test", "not-a-hash", SALT));
        assertFalse(c.verify("test", null, SALT));
    }

    /**
     * 超过 72 字节的密码必须被<b>静默截断</b>，而不是抛异常。
     *
     * <p>{@code BCryptPasswordEncoder.encode()} 对这种输入直接抛
     * {@code IllegalArgumentException("password cannot be more than 72 bytes")}，
     * 而 PHP 的 {@code password_hash()} 截断后正常返回。{@code oauth_clients.secret}
     * 里存着 97 字节的 argon2 哈希串，迁移脚本重新哈希时正好踩到这条差异。
     */
    @Test
    void bcryptTruncatesLongPasswordsAt72Bytes() {
        Cipher c = new BcryptCipher(10);
        String longPassword = "a".repeat(100);
        String hash = c.hash(longPassword, SALT);

        assertEquals(60, hash.length());
        // 100 个 'a' 与 72 个 'a' 落到同一个等价类
        assertTrue(c.verify("a".repeat(72), hash, SALT));
        assertTrue(c.verify(longPassword, hash, SALT));
        // 71 个 'a' 不是同一个密钥
        assertFalse(c.verify("a".repeat(71), hash, SALT));
    }

    /** 含 NUL 的密码：生成必须抛异常，校验则按 C 字符串截断。 */
    @Test
    void bcryptHandlesNulLikePhp() {
        Cipher c = new BcryptCipher(10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.hash("ab\0cd", SALT));
        assertEquals("Bcrypt password must not contain null character", e.getMessage());

        // verify 侧容忍 NUL：PHP 的 password_verify 底层是 crypt()，收到的是 C 字符串
        String hashOfAb = c.hash("ab", SALT);
        assertTrue(c.verify("ab\0cd", hashOfAb, SALT));
        assertTrue(c.verify("ab\0" + "z".repeat(40), hashOfAb, SALT));
    }

    // ---------------------------------------------------------------- argon2

    @Test
    void argon2iVerifiesExistingPhpHash() {
        // 由 PHP 8.5.12 生成：password_hash('test', PASSWORD_ARGON2I)
        String phpHash = "$argon2i$v=19$m=65536,t=4,p=1$N0RxSzRoNnppRkVEMFBmVQ"
                + "$PMwPMEd/ygPzi1yecvHu3gGOIca3LRuApt3gclSbEzs";
        Cipher c = new Argon2Cipher(false);
        assertTrue(c.verify("test", phpHash, SALT));
        assertFalse(c.verify("Test", phpHash, SALT));
    }

    @Test
    void argon2iProducesPhpFormat() {
        Cipher c = new Argon2Cipher(false);
        String hash = c.hash(VALUE, SALT);
        assertTrue(hash.startsWith("$argon2i$v=19$m=65536,t=4,p=1$"), "实际: " + hash);
        // 自己生成的哈希必须能被自己校验（salt 段是随机的，只能 round-trip）
        assertTrue(c.verify(VALUE, hash, SALT));
        assertFalse(c.verify("wrong", hash, SALT));
    }

    @Test
    void argon2idVerifiesPhpHash() {
        // PHP 8.5.12：password_hash('test', PASSWORD_ARGON2ID)
        String phpHash = "$argon2id$v=19$m=65536,t=4,p=1$LmxPcldJMUtFTTF6Y05vSA"
                + "$mcs1ooqCHuec8Nttv1pZE7rsxnkyQGAlSKKUlSathPo";
        Cipher id = new Argon2Cipher(true);
        assertTrue(id.verify("test", phpHash, SALT));
        assertFalse(id.verify("Test", phpHash, SALT));
        // 关键：argon2i 实例<b>必须</b>接受 argon2id 哈希。源项目的 ARGON2I::verify()
        // 调用的是 password_verify()，它只看哈希串格式、不看配置的算法；语料库
        // cipher.json 的 ARGON2I.cross.argon2id 就是这个规格。早期实现按配置过滤，
        // 会导致 PWD_METHOD 从 ARGON2ID 改成 ARGON2I 后全员登录失败。
        assertTrue(new Argon2Cipher(false).verify("test", phpHash, SALT));
        // 生成的格式也必须是 argon2id
        assertTrue(id.hash(VALUE, SALT).startsWith("$argon2id$v=19$"));
    }

    // ---------------------------------------------------------------- PHP_PASSWORD_HASH

    @Test
    void phpPasswordHashHashesWithBcryptButVerifiesBoth() {
        Cipher c = new PhpPasswordHashCipher(12);
        String hash = c.hash(VALUE, SALT);
        // PHP 8.5 的 PASSWORD_DEFAULT 仍是 bcrypt
        assertTrue(hash.startsWith("$2y$12$"), "实际: " + hash);

        assertTrue(c.verify(VALUE, hash, SALT));
        // 历史 argon2i 数据同样能校验
        String argon = "$argon2i$v=19$m=65536,t=4,p=1$N0RxSzRoNnppRkVEMFBmVQ"
                + "$PMwPMEd/ygPzi1yecvHu3gGOIca3LRuApt3gclSbEzs";
        assertTrue(c.verify("test", argon, SALT));
        assertFalse(c.verify("wrong", argon, SALT));
    }

    // ---------------------------------------------------------------- 语料驱动

    /** {@code cipher.json} 的 {@code hash} 用例：48 条，全部是确定性摘要。 */
    @TestFactory
    Stream<DynamicTest> corpusHash() {
        Corpus corpus = Corpus.require("cipher");
        return corpus.cases("hash").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    Cipher cipher = cipherFor(Corpus.text(c, "algorithm"));
                    String actual = cipher.hash(
                            Corpus.textOrEmpty(c, "value"), Corpus.textOrEmpty(c, "salt"));
                    assertEquals(Corpus.text(c, "expected"), actual);
                }));
    }

    /**
     * {@code cipher.json} 的 {@code verify} 用例：69 条。
     *
     * <p>其中 24 条 {@code *.cross.*} 是<b>算法无关</b>的规格：bcrypt / argon2 三个
     * 实例互相能验通，而摘要类必须验不过。另有 6 条
     * {@code MD5|SHA*.verify.wrong-salt} 记录的是「单重摘要忽略 salt」这一既成事实
     * （{@code expected = true}，但生成器里的 {@code expected_from_php = false}
     * 表示这与「按工程直觉的预期」相反）。
     *
     * <p>另有 9 条 {@code BCRYPT.verify.*} 描述 bcrypt 的密码归一化等价类
     * （NUL 截断、72 字节截断、多字节被劈开）。
     */
    @TestFactory
    Stream<DynamicTest> corpusVerify() {
        Corpus corpus = Corpus.require("cipher");
        return corpus.cases("verify").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    Cipher cipher = cipherFor(Corpus.text(c, "algorithm"));
                    boolean actual = cipher.verify(
                            Corpus.textOrEmpty(c, "value"),
                            Corpus.textOrEmpty(c, "hash"),
                            Corpus.textOrEmpty(c, "salt"));
                    assertEquals(Corpus.bool(c, "expected", false), actual);
                }));
    }

    /** {@code cipher.json} 的 {@code roundtrip} 用例：9 条（bcrypt / argon2 各 3 个样本）。 */
    @TestFactory
    Stream<DynamicTest> corpusRoundtrip() {
        Corpus corpus = Corpus.require("cipher");
        return corpus.cases("roundtrip").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    Cipher cipher = cipherFor(Corpus.text(c, "algorithm"));
                    String value = Corpus.textOrEmpty(c, "value");
                    String salt = Corpus.textOrEmpty(c, "salt");
                    String hash = cipher.hash(value, salt);

                    // 语料里的 format 是 PHP 的定界正则 /…/，去掉定界符后语法与 Java 兼容
                    assertTrue(phpPattern(Corpus.text(c, "format")).matcher(hash).matches(),
                            "格式不符: " + hash);
                    assertEquals(Corpus.bool(c, "expected_verify", true),
                            cipher.verify(value, hash, salt));
                    assertEquals(Corpus.bool(c, "expected_verify_wrong", false),
                            cipher.verify(value + "X", hash, salt));
                }));
    }

    // ---------------------------------------------------------------- bcrypt 归一化

    /**
     * {@code cipher.json} 的 {@code normalize} 用例：11 条，全部用<b>固定盐</b>录制。
     *
     * <p>这批用例钉死的是 bcrypt 对密码做的两件事，而不是某个 Cipher 类的行为：
     * 先按第一个 NUL 字节截断，再按<b>字节</b>截断到 72（可能劈开多字节字符）。
     * 期望值等价于 PHP 的 {@code crypt($value, $salt)}，Java 侧用
     * {@link PhpBcrypt#hashWithSalt(String, String)} 对拍。
     *
     * <p>为什么必须是固定盐：普通 {@code password_hash()} 每次都换盐，只能做往返测试，
     * 无法证明「截断到 72 字节」还是「截断到 71 字节」——实测把上限改成 71，
     * 11 条里有 5 条会变，但往返测试全部照过。
     */
    @TestFactory
    Stream<DynamicTest> corpusBcryptNormalize() {
        Corpus corpus = Corpus.require("cipher");
        return corpus.cases("normalize").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String salt = Corpus.text(c, "salt");
                    String actual = PhpBcrypt.hashWithSalt(Corpus.textOrEmpty(c, "value"), salt);
                    assertEquals(Corpus.text(c, "expected"), actual);
                }));
    }

    /**
     * {@code cipher.json} 的 {@code reject} 用例：4 条，验证 {@code password_hash()}
     * 对含 NUL 密码的拒绝。
     *
     * <p>注意这与 {@code verify} 不一致，且是<b>故意的</b>：{@code password_hash()}
     * 走 {@code php_crypt} 前会先检查 NUL 并抛 {@code ValueError}，而
     * {@code password_verify()} 底层是 {@code crypt()}，收到的是 C 字符串，静默截断。
     * 源项目的 {@code BCRYPT::hash()} / {@code BCRYPT::verify()} 正好分别走这两条路径。
     */
    @TestFactory
    Stream<DynamicTest> corpusBcryptReject() {
        Corpus corpus = Corpus.require("cipher");
        return corpus.cases("reject").stream().map(c -> DynamicTest.dynamicTest(
                Corpus.text(c, "id"), () -> {
                    String value = Corpus.textOrEmpty(c, "value");
                    if (!Corpus.bool(c, "rejects", false)) {
                        // 语料若出现「不拒绝」的样本，就断言能正常算出哈希（留出扩展余地）
                        assertEquals(60, PhpBcrypt.hash(value, 4).length());
                        return;
                    }
                    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                            () -> PhpBcrypt.hash(value, 4),
                            "含 NUL 的密码必须被拒绝");
                    assertEquals(Corpus.text(c, "message"), e.getMessage());
                }));
    }

    // ---------------------------------------------------------------- 工厂

    @Test
    void factoryMapsEveryPwdMethod() {
        for (String method : CipherConfig.SUPPORTED) {
            NeoSkinSettings settings = settingsWith(method);
            Cipher cipher = new CipherConfig().cipher(settings);
            String hash = cipher.hash(VALUE, SALT);
            assertTrue(cipher.verify(VALUE, hash, SALT), method + " round-trip 失败");
        }
    }

    @Test
    void factoryIsCaseInsensitive() {
        Cipher lower = new CipherConfig().cipher(settingsWith("bcrypt"));
        assertTrue(lower.verify(VALUE, lower.hash(VALUE, SALT), SALT));
    }

    @Test
    void factoryRejectsUnknownMethod() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CipherConfig().cipher(settingsWith("NOT_A_CIPHER")));
        assertTrue(e.getMessage().contains("PWD_METHOD"));
    }

    // ---------------------------------------------------------------- 辅助

    /** 按 {@code PWD_METHOD} 标签取 cipher，保证测试走的是真实工厂而不是手搓实例。 */
    private static Cipher cipherFor(String label) {
        return new CipherConfig().cipher(settingsWith(label));
    }

    private static NeoSkinSettings settingsWith(String method) {
        NeoSkinSettings s = new NeoSkinSettings();
        s.getSecure().setCipher(method);
        return s;
    }

    /**
     * 把 PHP 的 {@code /…/} 正则转成 Java {@link Pattern}。
     *
     * <p>语料里的两个格式都只用到了通用语法（字符类、量词、转义 {@code \$}），
     * 唯一的方言差异是 {@code \/}——PHP 允许转义斜杠，Java 也允许，但直接去掉更稳。
     */
    private static Pattern phpPattern(String php) {
        if (php == null || php.length() < 2 || php.charAt(0) != '/') {
            throw new IllegalArgumentException("不是 PHP 定界正则: " + php);
        }
        int end = php.lastIndexOf('/');
        String body = php.substring(1, end).replace("\\/", "/");
        return Pattern.compile(body);
    }
}
