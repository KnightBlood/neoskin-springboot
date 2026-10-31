package com.neoskin.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.neoskin.db.UserRepository;
import com.neoskin.service.session.SessionStore;

/**
 * Laravel {@code SessionGuard} 的会话键读写（{@code Auth::login()} / {@code Auth::user()}）。
 *
 * <h2>会话键必须逐字一致</h2>
 * <p>{@code SessionGuard::getName()} 返回</p>
 * <pre>
 * 'login_'.$this-&gt;name.'_'.sha1(static::class)
 * </pre>
 * <p>本应用 {@code config/auth.php} 的默认 guard 是 {@code web}，provider 是
 * {@code App\Models\User}（{@code Illuminate\Foundation\Auth\User} 的子类），
 * 实际 guard 类是 {@code Illuminate\Auth\SessionGuard}，所以键是</p>
 * <pre>
 * login_web_59ba36addc2b2f9401580f014c7f58ea4e30989d
 * </pre>
 * <p>（{@code php -r 'echo sha1("Illuminate\Auth\SessionGuard");'} 实测值）。
 * <b>写错一个字符，PHP 版与 Java 版之间的已登录状态就不互通。</b></p>
 *
 * <h2>登录流程（{@code Auth::login($user, $remember)}）</h2>
 * <pre>
 * $this-&gt;updateSession($user-&gt;getAuthIdentifier());
 *   $this-&gt;session-&gt;put($this-&gt;getName(), $id);
 *   $this-&gt;session-&gt;migrate(true);        // 防会话固定：换 id + 销毁旧文件
 * </pre>
 * <p>{@code $remember} 为真时另写 {@code remember_web_<sha1>} cookie —— 该部分
 * 尚未移植（见 PORT-NOTES）。</p>
 */
public final class SessionGuard {

    /** {@code Illuminate\Auth\SessionGuard} 的 sha1（实测值）。 */
    private static final String GUARD_HASH = "59ba36addc2b2f9401580f014c7f58ea4e30989d";

    /** {@code login_web_<sha1>}：会话里存放用户主键的键名。 */
    public static final String SESSION_KEY = "login_web_" + GUARD_HASH;

    /** {@code Session::forget('login_fails')} 用的会话键。 */
    public static final String LOGIN_FAILS_KEY = "login_fails";

    private final SessionStore session;

    private final UserRepository users;

    public SessionGuard(SessionStore session, UserRepository users) {
        this.session = session;
        this.users = users;
    }

    /**
     * {@code Auth::login($user)}。
     *
     * @param uid 用户主键
     */
    public void login(int uid) {
        session.put(SESSION_KEY, uid);
        // migrate(true)：换 id + 销毁旧会话文件 + 重新生成 CSRF token
        session.migrate(true);
        // migrate() 会清空属性，登录态必须重新写入
        session.put(SESSION_KEY, uid);
    }

    /** {@code Auth::logout()}。 */
    public void logout() {
        session.forget(SESSION_KEY);
        session.migrate(true);
    }

    /** {@code Auth::id()}。 */
    public Integer id() {
        Object value = session.get(SESSION_KEY);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** {@code Auth::user()}：没有登录态返回 {@code null}。 */
    public UserRepository.UserRow user() throws java.sql.SQLException {
        Integer uid = id();
        return uid == null ? null : users.findById(uid);
    }

    /** {@code Auth::check()}。 */
    public boolean check() {
        return id() != null;
    }

    /** 计算某个 guard 名的会话键（测试/诊断用）。 */
    public static String sessionKeyFor(String guardName) {
        return "login_" + guardName + "_" + sha1("Illuminate\\Auth\\SessionGuard");
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
