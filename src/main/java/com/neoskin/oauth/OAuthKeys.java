package com.neoskin.oauth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.neoskin.config.AppPaths;

/**
 * Passport 的 RSA 密钥（{@code php artisan passport:keys} 的等价物）。
 *
 * <p>源用 {@code openssl} 生成 PKCS#1 私钥；Java 标准库只能读 PKCS#8，
 * 因此这里统一用 PKCS#8 + X.509 PEM。已存在的 PHP 密钥无法解析时会重新生成
 * （旧 access token 失效），偏差记录在 PORT-NOTES §34。</p>
 */
public final class OAuthKeys {

    private final AppPaths paths;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    public OAuthKeys(AppPaths paths) {
        this.paths = paths;
    }

    private Path privatePath() {
        return paths.storage().resolve("oauth-private.key");
    }

    private Path publicPath() {
        return paths.storage().resolve("oauth-public.key");
    }

    /** 确保密钥存在（安装时与首次使用 token 端点时调用）。 */
    public synchronized void ensureGenerated() {
        if (privateKey != null && publicKey != null) {
            return;
        }
        try {
            if (Files.isRegularFile(privatePath()) && Files.isRegularFile(publicPath())) {
                privateKey = parsePrivate(Files.readString(privatePath()));
                publicKey = parsePublic(Files.readString(publicPath()));
                if (privateKey != null && publicKey != null) {
                    return;
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.createDirectories(privatePath().getParent());
            Files.writeString(privatePath(), pem("PRIVATE KEY",
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(pair.getPrivate().getEncoded())));
            Files.writeString(publicPath(), pem("PUBLIC KEY",
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(pair.getPublic().getEncoded())));
            privateKey = pair.getPrivate();
            publicKey = pair.getPublic();
        } catch (Exception e) {
            throw new IllegalStateException("无法准备 OAuth 密钥", e);
        }
    }

    public PrivateKey privateKey() {
        ensureGenerated();

        return privateKey;
    }

    public PublicKey publicKey() {
        ensureGenerated();

        return publicKey;
    }

    private static String pem(String type, String body) {
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static PrivateKey parsePrivate(String pem) {
        try {
            String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            return null;
        }
    }

    private static PublicKey parsePublic(String pem) {
        try {
            String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            return null;
        }
    }

    /** RS256 签名。 */
    public byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(data);

            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RS256 验签。 */
    public boolean verify(byte[] data, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey());
            signature.update(data);

            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
