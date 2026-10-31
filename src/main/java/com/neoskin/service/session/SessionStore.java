package com.neoskin.service.session;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;
/**
 * Laravel {@code Illuminate\Session\Store} 的移植（值层）。
 *
 * <p>本应用会话载荷 {@code serialization=php}（见 {@code config/session.php} 的注释：
 * 改 json 会作废存量会话），所以读路径是 {@code @unserialize}（警告吞掉、解析失败
 * 就当空包）、写路径是 {@code serialize($attributes)}（逐字节走
 * {@link PhpSerialize}——它就是为此对齐的语料）。</p>
 *
 * <h2>对齐的 Store 语义</h2>
 * <ul>
 *   <li>{@code start()}：读 handler → {@code array_replace} 合并 → 没有
 *       {@code _token} 就生成（{@code Str::random(40)}）；</li>
 *   <li>{@code isValidId()}：{@code ctype_alnum} 且长度恰 40，非法 id 直接换新；</li>
 *   <li>{@code save()}：先 {@code ageFlashData()}（旧闪存/新闪存轮转）再写 handler；</li>
 *   <li>{@code regenerate($destroy)}：可选删旧文件、换新 id、重新生成 CSRF token。</li>
 * </ul>
 *
 * <p>flash 语义（{@code _flash.new} / {@code _flash.old}）原样保留，因为
 * 重定向后的消息提示依赖它。</p>
 */
public final class SessionStore {

    /** {@code SESSION_ID_LENGTH}。 */
    public static final int SESSION_ID_LENGTH = 40;

    /** CSRF token 的键名 */
    public static final String TOKEN_KEY = "_token";

    /** {@code ctype_alnum} 的字符集（id/随机串共用）。 */
    private static final String ALNUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 当前会话 id。 */
    private String id;

    /** {@code session.cookie}。 */
    private final String name;

    private final SessionHandler handler;

    /** 属性包（键序有语义：serialize 输出按它走，LinkedHashMap 直写）。 */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    private boolean started;

    public SessionStore(String name, SessionHandler handler) {
        this.name = name;
        this.handler = handler;
    }

    // ------------------------------------------------------------- 生命周期

    /** {@code start()}: 载入 + 保底 CSRF token，标记 started。 */
    public SessionStore start() {
        loadSession();

        if (!has(TOKEN_KEY)) {
            regenerateToken();
        }

        started = true;

        return this;
    }

    /** {@code loadSession()}: array_replace(attributes, readFromHandler)。 */
    private void loadSession() {
        Map<String, Object> loaded = readFromHandler();

        attributes.putAll(loaded);
    }

    /**
     * {@code readFromHandler()}: 读出串 → {@code @unserialize}（本移植用
     * {@link PhpUnserialize#parse}——warning 被吞的地方是 PHP 的 {@code @}，
     * 所以失败与否都走「空包」）→ {@code false || 非数组} 一律空包。
     */
    private Map<String, Object> readFromHandler() {
        String data = handler.read(id);

        if (!data.isEmpty()) {
            PhpUnserialize.Result result = PhpUnserialize.parse(data);

            if (result.value() instanceof PhpJson.Array array) {
                // PHP 会话属性键恒为字符串（_token、_flash…）；数字键按字符串收。
                Map<String, Object> out = new LinkedHashMap<>();

                array.forEach((k, v) -> out.put(String.valueOf(k), v));

                return out;
            }
        }

        return Map.of();
    }

    /** {@code save()}: 闪存轮转 → serialize → handler.write。 */
    public void save() {
        ageFlashData();
        handler.write(id, PhpSerialize.serialize(toPhpArray()));

        started = false;
    }

    /** {@code ageFlashData()}: forget(_flash.old) → old←new → new←[]。 */
    @SuppressWarnings("unchecked")
    private void ageFlashData() {
        Object old = attributes.getOrDefault("_flash.old", new PhpJson.Array());
        Object fresh = attributes.getOrDefault("_flash.new", new PhpJson.Array());

        if (old instanceof PhpJson.Array oldKeys) {
            for (Object key : oldKeys.values()) {
                attributes.remove(String.valueOf(key));
            }
        }
        if (fresh instanceof PhpJson.Array freshKeys) {
            attributes.put("_flash.old", freshKeys);
        }
        attributes.put("_flash.new", new PhpJson.Array());
    }

    // ------------------------------------------------------------- id 与 token

    /** {@code setId()}: 非法 id 一律换新（{@code ctype_alnum} + 长度 40）。 */
    public SessionStore setId(String newId) {
        this.id = isValidId(newId) ? newId : generateSessionId();

        return this;
    }

    /**
     * {@code Store::migrate($destroy = false)}：换新 id（可选销毁旧会话文件），
     * 并重新生成 CSRF token。
     *
     * <p>源实现：</p>
     * <pre>
     * if ($destroy) { $this-&gt;handler-&gt;destroy($this-&gt;getId()); }
     * $this-&gt;setId($this-&gt;generateSessionId());
     * $this-&gt;start();   // 重新载入 + 保底 token
     * </pre>
     *
     * <p>登录成功后 Laravel 会调 {@code migrate(true)} 防会话固定攻击
     * （{@code Auth::login} → {@code updateSession}）。</p>
     */
    public SessionStore migrate(boolean destroy) {
        String oldId = this.id;
        if (destroy && oldId != null) {
            handler.destroy(oldId);
        }
        this.id = generateSessionId();
        this.started = false;
        attributes.clear();
        start();
        return this;
    }

    public String getId() {
        return id;
    }

    /** {@code isValidId()}: 字母数字且长度恰 40（null/短 id 都不收）。 */
    public boolean isValidId(String candidateId) {
        if (candidateId == null || candidateId.length() != SESSION_ID_LENGTH) {
            return false;
        }

        for (int i = 0; i < candidateId.length(); i++) {
            if (ALNUM.indexOf(candidateId.charAt(i)) < 0) {
                return false;
            }
        }

        return true;
    }

    /** {@code Str::random(40)}：字母数字（PHP random_bytes + base64 去码法）。 */
    public static String generateSessionId() {
        StringBuilder sb = new StringBuilder(SESSION_ID_LENGTH);

        for (int i = 0; i < SESSION_ID_LENGTH; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }

        return sb.toString();
    }

    /** {@code token()}: {@code get('_token')}。 */
    public Object token() {
        return get(TOKEN_KEY);
    }

    /** {@code regenerateToken()}: 新的 40 位随机串。 */
    public SessionStore regenerateToken() {
        put(TOKEN_KEY, generateSessionId());

        return this;
    }

    // ------------------------------------------------------------- 存取

    /** {@code put()}。 */
    public SessionStore put(String key, Object value) {
        attributes.put(key, value);

        return this;
    }

    /** {@code get()}: 默认值 {@code null}。 */
    public Object get(String key) {
        return get(key, null);
    }

    /** {@code get($key, $default)}。 */
    public Object get(String key, Object defaultValue) {
        return attributes.containsKey(key) ? attributes.get(key) : defaultValue;
    }

    /** {@code has()}: containsKey 且非 null。 */
    public boolean has(String key) {
        return attributes.containsKey(key) && attributes.get(key) != null;
    }

    /** {@code forget()}。 */
    public SessionStore forget(String key) {
        attributes.remove(key);

        return this;
    }

    /** {@code flash(key, value)}: 进 {@code _flash.new}。 */
    public SessionStore flash(String key, Object value) {
        put(key, value);
        pushInto("_flash.new", key);

        return this;
    }

    private void pushInto(String flashBag, String key) {
        Object bag = attributes.computeIfAbsent(flashBag, k -> new PhpJson.Array());

        if (bag instanceof PhpJson.Array array) {
            // PHP 的闪存键包是 int 键的普通数组（0,1,2…）。
            array.put((long) array.size(), key);
        }
    }

    // ------------------------------------------------------------- redirect

    /** {@code previousUrl()}: {@code _previous.url}。 */
    public Object previousUrl() {
        return get("_previous.url");
    }

    /** {@code setPreviousUrl()}: flash（下轮请求后过期）语义在框架里二者兼有，这里照 put）。 */
    public SessionStore setPreviousUrl(String url) {
        put("_previous.url", url);

        return this;
    }

    // ------------------------------------------------------------- 底层

    /** 载荷的 PHP 数组形态（attributes 的 LinkedHashMap → PhpJson.Array）。 */
    PhpJson.Array toPhpArray() {
        PhpJson.Array array = new PhpJson.Array();

        attributes.forEach(array::put);

        return array;
    }

    /** 测试/内部使用：当前属性包（只读视图由调用方自律）。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public String getName() {
        return name;
    }

    public boolean started() {
        return started;
    }

    /** 便捷工具：一行构造 + 从 cookie id 起步（StartSession 的 getSession）。 */
    public static SessionStore begin(String name, SessionHandler handler, String cookieId) {
        return new SessionStore(name, handler).setId(cookieId);
    }
}
