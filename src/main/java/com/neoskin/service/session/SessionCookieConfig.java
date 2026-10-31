package com.neoskin.service.session;

/**
 * 会话 cookie 配置（源 {@code config/session.php} 的 cookie / lifetime / path /
 * same_site / secure）。
 *
 * @param name            {@code SESSION_COOKIE}（默认 {@code BS_SESSION}）
 * @param lifetimeMinutes {@code SESSION_LIFETIME}（默认 120）
 * @param path            cookie path（默认 {@code /}）
 * @param sameSite        {@code lax} / {@code strict} / {@code none}
 * @param secure          {@code SESSION_SECURE_COOKIE}
 */
public record SessionCookieConfig(String name, long lifetimeMinutes, String path,
        String sameSite, boolean secure) {

    public static final SessionCookieConfig DEFAULT =
            new SessionCookieConfig("BS_SESSION", 120, "/", "lax", false);

    public SessionCookieConfig {
        if (name == null || name.isBlank()) {
            name = DEFAULT.name;
        }
        if (lifetimeMinutes <= 0) {
            lifetimeMinutes = DEFAULT.lifetimeMinutes;
        }
        if (path == null || path.isBlank()) {
            path = DEFAULT.path;
        }
        if (sameSite == null || sameSite.isBlank()) {
            sameSite = DEFAULT.sameSite;
        }
    }

    /** {@code session.cookie} 的 Max-Age（秒）。 */
    public long maxAgeSeconds() {
        return lifetimeMinutes * 60;
    }
}
