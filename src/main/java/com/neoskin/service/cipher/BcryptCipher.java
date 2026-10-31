package com.neoskin.service.cipher;

/**
 * PHP {@code password_hash($value, PASSWORD_BCRYPT)} / {@code password_verify()}
 * 的等价实现。
 *
 * <p>PHP 生成的前缀是 {@code $2y$}。生成与校验都委托 {@link PhpBcrypt}，
 * 它是本项目中 bcrypt 归一化（NUL 截断 → 72 字节截断）的唯一实现。
 *
 * <p>cost 与源项目一致：源项目调用 {@code password_hash($value, PASSWORD_BCRYPT)}
 * 且没有传 options，成本因子取自 PHP 默认值——PHP 8.4 起由 10 提升到 12，
 * 源运行环境是 PHP 8.5，实测产出 {@code $2y$12$…}。校验不受 cost 影响，
 * 旧库的 {@code $2y$10$…} 依然可以登录。
 *
 * <p><b>生成不能用 {@code BCryptPasswordEncoder#encode()}</b>：它超过 72 字节直接抛异常，
 * 而 PHP 静默截断。校验也不能用 {@code matches()}：它的正则只接受
 * {@code $2a$} / {@code $2y$} / {@code $2b$}，而 PHP 的 {@code password_verify()}
 * 还接受 {@code $2x$}（连带复刻 sign-extension bug）。校验统一走
 * {@link PhpPasswordVerify}，与源项目的 {@code password_verify()} 逐条对齐。
 */
public final class BcryptCipher implements Cipher {

    private final int cost;

    public BcryptCipher(int rounds) {
        // PHP 允许 4..31，默认 12
        this.cost = Math.min(PhpBcrypt.MAX_COST, Math.max(PhpBcrypt.MIN_COST, rounds));
    }

    @Override
    public String hash(String value, String salt) {
        // 注意：bcrypt 的 salt 由算法内部生成，PHP 的 password_hash 也忽略外部 salt。
        // 源项目 BCRYPT::hash() 同样没有使用 $salt 参数。
        return PhpBcrypt.hash(value, cost);
    }

    @Override
    public boolean verify(String password, String hash, String salt) {
        // 与 BCRYPT::verify() 一致：直接 password_verify()，不限制算法
        return PhpPasswordVerify.verify(password, hash);
    }

    /** 配置的成本因子（已收敛到 4..31）。 */
    public int cost() {
        return cost;
    }
}
