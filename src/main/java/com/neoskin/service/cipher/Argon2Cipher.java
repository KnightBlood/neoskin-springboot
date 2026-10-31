package com.neoskin.service.cipher;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * PHP {@code PASSWORD_ARGON2I} / {@code PASSWORD_ARGON2ID} 的等价实现。
 *
 * <p>PHP 输出格式：
 * <pre>$argon2i$v=19$m=65536,t=4,p=1$&lt;salt-b64&gt;$&lt;hash-b64&gt;</pre>
 * 其中 {@code v=19} 是 Argon2 1.3，{@code m} 为内存 KiB，{@code t} 为迭代次数，
 * {@code p} 为并行度，默认 {@code 65536 / 4 / 1}（PHP 的 ini 默认值，源项目未覆盖）。
 *
 * <p>JVM 没有内置 Argon2，这里用 BouncyCastle 的 {@link Argon2BytesGenerator} 复刻同样计算。
 * 注意 PHP 的 base64 是 <b>无填充</b> 的 standard alphabet，解码时要自行补 {@code =}。
 *
 * <p><b>校验行为</b>：源项目 {@code ARGON2I::verify()} 直接调用 {@code password_verify()}，
 * 它<b>不看</b>当前配置的算法，只解析哈希串。所以本类的 {@link #verify} 统一委托给
 * {@link PhpPasswordVerify}，而不是按 {@link #argon2id} 字段过滤——否则把
 * {@code PWD_METHOD} 从 {@code BCRYPT} 切到 {@code ARGON2I} 之后，库里原有的
 * {@code $2y$} 用户会全部登不进去。语料库 {@code cipher.json} 的
 * {@code ARGON2I.cross.bcrypt2y} 等 24 条用例就是这个规格。
 *
 * <p>只有 {@link #hash} 是算法相关的：{@code argon2id=false} 产出 {@code $argon2i$}，
 * 否则产出 {@code $argon2id$}。
 */
public final class Argon2Cipher implements Cipher {

    /** 与 PHP 默认 ini 值一致：memory_cost=65536 KiB, time_cost=4, threads=1。 */
    private static final int DEFAULT_MEMORY = 65536;
    private static final int DEFAULT_TIME = 4;
    private static final int DEFAULT_PARALLELISM = 1;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean argon2id;

    public Argon2Cipher() {
        this(false);
    }

    public Argon2Cipher(boolean argon2id) {
        this.argon2id = argon2id;
    }

    @Override
    public String hash(String value, String salt) {
        // 注意：PHP 的 password_hash() 自己生成随机 salt，忽略外部 $salt 参数。
        byte[] saltBytes = new byte[SALT_LENGTH];
        RANDOM.nextBytes(saltBytes);
        byte[] raw = rawHash(value, saltBytes, DEFAULT_MEMORY, DEFAULT_TIME, DEFAULT_PARALLELISM,
                argon2id, PhpPasswordVerify.ARGON2_VERSION_13, HASH_LENGTH);
        return encode(raw, saltBytes, DEFAULT_MEMORY, DEFAULT_TIME, DEFAULT_PARALLELISM);
    }

    @Override
    public boolean verify(String password, String hash, String salt) {
        // 与 password_verify() 一致：算法无关，只看哈希串本身
        return PhpPasswordVerify.verify(password, hash);
    }

    /**
     * 计算 Argon2 原始摘要，供本类与 {@link PhpPasswordVerify} 共用。
     *
     * @param memory      内存 KiB（{@code m}）
     * @param time        迭代次数（{@code t}）
     * @param parallelism 并行度（{@code p}）
     * @param id          {@code true} 用 Argon2id，{@code false} 用 Argon2i
     * @param version     {@link Argon2Parameters#ARGON2_VERSION_13} 或 {@code ARGON2_VERSION_10}
     * @param hashLength  输出字节数，由哈希串的 base64 长度决定
     */
    static byte[] rawHash(String value, byte[] saltBytes, int memory, int time, int parallelism,
                          boolean id, int version, int hashLength) {
        Argon2Parameters params = new Argon2Parameters.Builder(
                id ? Argon2Parameters.ARGON2_id : Argon2Parameters.ARGON2_i)
                .withVersion(version)
                .withMemoryAsKB(memory)
                .withIterations(time)
                .withParallelism(parallelism)
                .withSalt(saltBytes)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[hashLength];
        generator.generateBytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8), out);
        return out;
    }

    private String encode(byte[] raw, byte[] saltBytes, int memory, int time, int parallelism) {
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$" + (argon2id ? "argon2id" : "argon2i") + "$v=19"
                + "$m=" + memory + ",t=" + time + ",p=" + parallelism
                + "$" + b64.encodeToString(saltBytes)
                + "$" + b64.encodeToString(raw);
    }
}
