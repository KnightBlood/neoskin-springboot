package com.neoskin.service.cache;

import java.net.URI;

/**
 * {@code REDIS_URL} 解析（源 {@code config/database.php} 的 {@code url} 优先）：
 * {@code redis://[user[:password]@]host[:port][/db]}。
 *
 * <p>{@code rediss://}（TLS）未支持（Jedis 需要额外的 SSL 配置）；解析时按普通
 * 连接处理并记录偏差。</p>
 */
public record RedisUrl(String host, int port, String username, String password,
        int database) {

    /** 解析结果是否可用（非空且是 redis://）。 */
    public static RedisUrl parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url.trim();
        if (value.startsWith("rediss://")) {
            value = "redis://" + value.substring("rediss://".length());
        }
        if (!value.startsWith("redis://")) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null || uri.getHost().isBlank()
                    ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort() <= 0 ? 6379 : uri.getPort();
            String user = null;
            String password = null;
            if (uri.getUserInfo() != null) {
                String info = uri.getUserInfo();
                int colon = info.indexOf(':');
                if (colon >= 0) {
                    user = info.substring(0, colon);
                    password = info.substring(colon + 1);
                } else {
                    password = info;
                }
            }
            String path = uri.getPath();
            int database = 0;
            if (path != null && path.length() > 1) {
                database = Integer.parseInt(path.substring(1));
            }

            return new RedisUrl(host, port, user, password, database);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
