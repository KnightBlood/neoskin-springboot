package com.neoskin.service.session;

/**
 * 会话存储 handler（Laravel {@code Illuminate\Session\SessionHandlerInterface}）。
 *
 * <p>载荷的 序列化/反序列化由 {@link SessionStore} 负责（见 {@code config/session.php}
 * 锁定的 {@code serialization=php}），这里只搬「字符串载荷」。</p>
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@link FileSessionHandler}（默认，{@code SESSION_DRIVER=file}，
 *       {@code storage/framework/sessions/<id>}，与 PHP 版共用目录）；</li>
 *   <li>{@link CacheBasedSessionHandler}（{@code SESSION_DRIVER=redis} / {@code array}，
 *       走 {@link com.neoskin.service.cache.CacheStore}）。</li>
 * </ul>
 */
public interface SessionHandler {

    /**
     * {@code read($id)}：返回载荷；无会话/已过期返回空串
     * （Laravel 的上层把 {@code ''} 与「没有」同等对待）。
     */
    String read(String id);

    /** {@code write($id, $data)}：覆盖写。 */
    boolean write(String id, String data);

    /** {@code destroy($id)}：静默删除（不存在也算成功）。 */
    boolean destroy(String id);

    /**
     * {@code gc($lifetime)}：清理过期会话。文件驱动实现；缓存驱动由 TTL 兜底
     * （源 {@code CacheBasedSessionHandler::gc()} 也是空实现）。
     *
     * @return 清理条数（诊断用）
     */
    default long gc(long lifetimeSeconds) {
        return 0;
    }
}
