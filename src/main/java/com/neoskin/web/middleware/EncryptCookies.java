package com.neoskin.web.middleware;

import java.util.ArrayList;
import java.util.List;

import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.support.PhpJson;
import com.neoskin.web.cookie.CookieValuePrefix;

/**
 * Laravel {@code Illuminate\Cookie\Middleware\EncryptCookies} 的移植（值层版）。
 *
 * <p>语料：{@code conformance/data/cookie.json} 的 {@code encryptcookies_*}。
 *
 * <p>这里的 "值层" 是指：源通过 Symfony Request/Response 传值，本工程 web 层是
 * Vert.x，没有那套对象——所以把中间件里<b>只跟值有关的逻辑</b>（decrypt 的
 * try/catch 语义、数组按键下标递归、响应端加前缀）提出来，等 Vert.x 管道接上后
 * 由它直接调这两个方法，里层不再依赖容器。
 *
 * <h2>行为要点（全部用例实测过）</h2>
 * <ul>
 *   <li>解密抛 {@link PhpEncrypter.DecryptException} → 该 cookie 置 {@code null}，
 *       <b>不影响其他 cookie</b>；数组 cookie 只要有一个子元素失败就整个置 null；</li>
 *   <li>解出来的值要过 {@link CookieValuePrefix#validate}：前缀对不上也是 null
 *       （例如用别的名字/ShSession 名字建的密文）；</li>
 *   <li>响应端加密值 = {@code create(name, key) + value}，serialize 依
 *       {@link #serialized}（本应用恒为 false）。</li>
 * </ul>
 */
public final class EncryptCookies {

    /** 解密后的 cookie 在 {@code RoutingContext} 里的存取键。 */
    public static final String CTX_KEY = "decrypted_cookies";

    /** 全局忽略清单（{@code EncryptCookies::except()}）。 */
    private static final List<String> NEVER_ENCRYPT = new ArrayList<>();

    /** 本应用 cookie 不序列化（{@code config('encrypt_cookies.serialize') = false}）。 */
    private static boolean SERIALIZE = false;

    private final PhpEncrypter encrypter;

    private final List<String> except = new ArrayList<>();

    public EncryptCookies(PhpEncrypter encrypter) {
        this.encrypter = encrypter;
    }

    /** {@code disableFor()}: 在本实例里给某个 cookie 关加密。 */
    public void disableFor(String name) {
        except.add(name);
    }

    /** {@code isDisabled()}: 实例 except ∪ 全局 neverEncrypt。 */
    public boolean isDisabled(String name) {
        return except.contains(name) || NEVER_ENCRYPT.contains(name);
    }

    /** {@code EncryptCookies::except()}: 全局永加密名单。 */
    public static void except(List<String> cookies) {
        for (String cookie : cookies) {
            if (!NEVER_ENCRYPT.contains(cookie)) {
                NEVER_ENCRYPT.add(cookie);
            }
        }
    }

    /** {@code serialized()}: 本应用恒 false。 */
    public static boolean serialized(String name) {
        return SERIALIZE;
    }

    /** 加密器（CSRF 的 X-XSRF-TOKEN 解密要用同一把钥匙）。 */
    public PhpEncrypter encrypter() {
        return encrypter;
    }

    /** {@code flushState()}: 静态状态复位（测试切用例用）。 */
    public static void flushState() {
        NEVER_ENCRYPT.clear();
        SERIALIZE = false;
    }

    // ================================================================== 请求侧

    /**
     * {@code decrypt()}: 逐个 cookie 尝试解密+验前缀；任何子步骤抛
     * {@code DecryptException} 就整个置 {@code null}，别的 cookie 照常。
     */
    public PhpJson.Array decrypt(PhpJson.Array cookies) {
        PhpJson.Array result = new PhpJson.Array();

        for (var entry : cookies.entrySet()) {
            String name = String.valueOf(entry.getKey());

            if (isDisabled(name)) {
                continue;
            }

            try {
                result.put(name, validateValue(name, decryptCookie(name, entry.getValue())));
            } catch (PhpEncrypter.DecryptException e) {
                result.put(name, null);
            }
        }

        return result;
    }

    /** {@code validateValue()}: 数组按键下标（PHP 的 {@code "$key[$index]"}）逐项递归。 */
    private Object validateValue(String key, Object value) {
        if (value instanceof PhpJson.Array array) {
            PhpJson.Array validated = new PhpJson.Array();

            for (var entry : array.entrySet()) {
                validated.put(entry.getKey(),
                        validateValue(key + "[" + entry.getKey() + "]", entry.getValue()));
            }

            return validated;
        }

        return CookieValuePrefix.validate(key, (String) value, encrypter.getAllKeys());
    }

    /** {@code decryptCookie()}: 数组 cookie 逐项解密（名字里的下标不影响 decrypt 算法）。 */
    private Object decryptCookie(String name, Object cookie) {
        if (cookie instanceof PhpJson.Array array) {
            PhpJson.Array decrypted = new PhpJson.Array();

            for (var entry : array.entrySet()) {
                String subKey = String.valueOf(entry.getKey());

                if (entry.getValue() instanceof String s) {
                    decrypted.put(subKey, encrypter.decrypt(s, serialized(subKey)));
                } else if (entry.getValue() instanceof PhpJson.Array nested) {
                    decrypted.put(subKey, decryptCookie(subKey, nested));
                }
            }

            return decrypted;
        }

        return encrypter.decrypt((String) cookie, serialized(name));
    }

    // ================================================================== 响应侧

    /**
     * {@code encrypt()}（响应阶段）：加密 {@code create(name, key) + value}。
     * 返回的是放进 cookie 的整体字符串（base64 payload）。
     */
    public String encrypt(String name, String value) {
        String prefixed = CookieValuePrefix.create(name, encrypter.getKey()) + value;

        return encrypter.encrypt(prefixed, serialized(name));
    }
}
