package com.neoskin.service.cipher;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.neoskin.config.NeoSkinSettings;

/**
 * 密码算法工厂，对应源项目 {@code App\Providers\AppServiceProvider} 的
 * {@code $this->app->singleton('cipher', 'App\Services\Cipher\\'.config('secure.cipher'))}。
 *
 * <p>源项目用「配置值直接拼成类名」的方式选择算法，因此配置值大小写必须与文件名一致
 * （{@code BCRYPT}、{@code SALTED2MD5} …）。本移植为了容错，统一按大写匹配，
 * 但未知值仍然直接报错而不是静默降级——静默降级会让用户在迁移后无法登录，
 * 而且很难排查。
 *
 * <p>{@code PHP_PASSWORD_HASH} 在 PHP 8.5 上等价于 bcrypt（{@code PASSWORD_DEFAULT}
 * 目前仍是 bcrypt），但历史数据库里可能同时存在 {@code $2y$} 与 {@code $argon2i$}，
 * 因此校验时两种格式都接受，新哈希仍按 PHP 的默认算法生成 bcrypt。
 */
@Configuration
public class CipherConfig {

    /** 支持的全部 PWD_METHOD 取值，与 {@code app/Services/Cipher/} 下的文件名一一对应。 */
    public static final java.util.Set<String> SUPPORTED = java.util.Set.of(
            "BCRYPT", "ARGON2I", "PHP_PASSWORD_HASH",
            "MD5", "SALTED2MD5", "SHA256", "SALTED2SHA256", "SHA512", "SALTED2SHA512");

    /**
     * 按 {@code PWD_METHOD} 创建单例 cipher。
     *
     * <p>用 {@code @Bean} 而不是 {@code @Component}，是因为需要读取配置并且只允许一个实例
     * （bcrypt 的 cost 在构造时固定）。
     */
    @Bean
    public Cipher cipher(NeoSkinSettings settings) {
        String method = settings.getSecure().getCipher();
        String normalized = method == null ? "BCRYPT" : method.trim().toUpperCase(Locale.ROOT);
        int rounds = settings.getHashing().getPasswordRounds();
        return switch (normalized) {
            case "BCRYPT" -> new BcryptCipher(rounds);
            case "ARGON2I" -> new Argon2Cipher(false);
            case "PHP_PASSWORD_HASH" -> new PhpPasswordHashCipher(rounds);
            case "MD5" -> new DigestCipher.Md5();
            case "SALTED2MD5" -> new DigestCipher.Salted2Md5();
            case "SHA256" -> new DigestCipher.Sha256();
            case "SALTED2SHA256" -> new DigestCipher.Salted2Sha256();
            case "SHA512" -> new DigestCipher.Sha512();
            case "SALTED2SHA512" -> new DigestCipher.Salted2Sha512();
            default -> throw new IllegalArgumentException(
                    "Unsupported PWD_METHOD: " + method + " (expected one of " + SUPPORTED + ")");
        };
    }
}
