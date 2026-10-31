package com.neoskin.web.middleware;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Illuminate\Routing\Middleware\ThrottleRequests} 的进程内移植。
 *
 * <p>源用缓存键 {@code sha1(domain|ip)} 计数；有 {@link com.neoskin.service.cache.CacheStore}
 * （file/redis）时与源一致地持久化计数（多进程共享），否则退回进程内固定窗口：</p>
 * <ul>
 *   <li>首次命中写入过期时间 {@code now + decay*60}；</li>
 *   <li>窗口内命中数 {@code >= maxAttempts} → 429 + {@code Retry-After}；</li>
 *   <li>窗口过期后重置。</li>
 * </ul>
 */
public final class ThrottleRequests implements ParameterizedHandler, Handler<RoutingContext> {

    private static final class Bucket {
        long hits;
        long resetAt;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final com.neoskin.service.cache.CacheStore cache;

    public ThrottleRequests() {
        this(null);
    }

    /** 传 {@link com.neoskin.service.cache.CacheStore} 时计数持久化（file/redis 共享）。 */
    public ThrottleRequests(com.neoskin.service.cache.CacheStore cache) {
        this.cache = cache;
    }

    @Override
    public void handle(RoutingContext ctx) {
        handle(ctx, null);
    }

    @Override
    public void handle(RoutingContext ctx, String params) {
        int maxAttempts = 60;
        double decayMinutes = 1;
        String[] parts = params == null ? new String[0] : params.split(",");
        try {
            if (parts.length > 0) maxAttempts = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) decayMinutes = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException ignored) {
            // 与源一致：非法参数按默认值继续
        }

        String key = signature(ctx);
        long now = System.currentTimeMillis();
        int windowSeconds = Math.max(1, (int) Math.round(decayMinutes * 60));
        long hits;
        long retryAfter;

        if (cache != null) {
            // 与源一致：Cache::add(key, 0, decay) + increment；窗口由首次写入决定。
            hits = cache.increment(key, 1, windowSeconds);
            long ttl = cache.ttlSeconds(key);
            retryAfter = Math.max(1, ttl <= 0 ? windowSeconds : ttl);
        } else {
            long window = (long) (decayMinutes * 60_000);
            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
            synchronized (bucket) {
                if (bucket.resetAt == 0 || now >= bucket.resetAt) {
                    bucket.hits = 0;
                    bucket.resetAt = now + window;
                }
                bucket.hits++;
            }
            hits = bucket.hits;
            retryAfter = Math.max(1, (bucket.resetAt - now + 999) / 1000);
        }

        long remaining = Math.max(0, maxAttempts - hits);
        ctx.response()
                .putHeader("X-RateLimit-Limit", String.valueOf(maxAttempts))
                .putHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (hits > maxAttempts) {
            ctx.response().setStatusCode(429)
                    .putHeader("Retry-After", String.valueOf(retryAfter))
                    .putHeader("Cache-Control", "no-cache, private")
                    .end("<html><head><title>Too Many Requests</title></head>"
                            + "<body><h1>Too Many Requests</h1></body></html>");
            return;
        }

        ctx.next();
    }

    /**
     * {@code resolveRequestSignature}：登录用户按 {@code sha1(user id)} 计数，
     * 游客按 {@code sha1(domain.'|'.ip)} 计数（源项目同样如此）。
     */
    private static String signature(RoutingContext ctx) {
        com.neoskin.db.UserRepository.UserRow user = Authenticate.currentUser(ctx);
        if (user != null) {
            return sha1(String.valueOf(user.uid()));
        }

        String ip = "127.0.0.1";
        try {
            if (ctx.request().remoteAddress() != null) {
                ip = ctx.request().remoteAddress().hostAddress();
            }
        } catch (Exception ignored) {
            // 保留默认值
        }
        return sha1("|" + ip);
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
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}