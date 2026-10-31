package com.neoskin.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import redis.clients.jedis.Jedis;

/**
 * {@link RedisCache} 的端到端测试：使用 `tools/` 下自带的 Redis 与 Garnet。
 *
 * <p>二进制缺失时自动跳过；路径可用 {@code -Dneoskin.redis.binary=...} /
 * {@code -Dneoskin.garnet.binary=...} 覆盖。</p>
 */
class RedisCacheTest {

    @TempDir
    Path dir;

    private static final Path REDIS_BINARY = binary("neoskin.redis.binary",
            "../tools/redis-windows-8.10.2/redis-server.exe");
    private static final Path GARNET_BINARY = binary("neoskin.garnet.binary",
            "../tools/Garnet/GarnetServer.exe");

    @Test
    void redisSemantics() throws Exception {
        runSuite("redis", REDIS_BINARY,
                List.of("--port", "PORT", "--save", "", "--appendonly", "no"));
    }

    @Test
    void garnetSemantics() throws Exception {
        runSuite("garnet", GARNET_BINARY,
                List.of("--port", "PORT", "--bind", "127.0.0.1"));
    }

    @Test
    void redisUrlAndConnectionPrefix() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_BINARY),
                "未找到 redis：" + REDIS_BINARY);
        int port = freePort();
        Process process = start(REDIS_BINARY,
                List.of("--port", "PORT", "--save", "", "--appendonly", "no"), port);
        try {
            waitUntilReady(port);
            String cachePrefix = "cache_";
            String connectionPrefix = "conn_";
            // host/port 故意给错：REDIS_URL 应优先
            try (RedisCache cache = new RedisCache(
                    "redis://127.0.0.1:" + port + "/0", "10.255.255.1", 1,
                    "", "", 0, cachePrefix, connectionPrefix)) {
                assertTrue(cache.available());
                cache.put("k", "v", 60);
                assertEquals("v", cache.get("k"));

                try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                    assertEquals("s:1:\"v\";", jedis.get(connectionPrefix + cachePrefix + "k"));
                }
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    // ================================================================ 工具

    private void runSuite(String name, Path binary, List<String> template) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(binary), "未找到 " + name + " 二进制：" + binary);
        int port = freePort();
        Process process = start(binary, template, port);
        try {
            waitUntilReady(port);
            String prefix = "neoskin_test_" + name + "_";

            try (RedisCache cache = new RedisCache("127.0.0.1", port, "", "", 0, prefix)) {
                assertTrue(cache.available(), name + " 未能连接");

                cache.put("value", 42, 60);
                assertEquals(42, ((Number) cache.get("value")).intValue());
                assertNull(cache.get("missing"));

                // 数字按原样存储（Laravel RedisStore 语义，incrBy 可直接操作）
                try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                    assertEquals("42", jedis.get(prefix + "value"));
                }

                cache.put("forever", "v", 0);
                assertEquals("v", cache.get("forever"));
                assertEquals(0, cache.ttlSeconds("forever"));

                cache.put("expired", "v", -1);
                assertNull(cache.get("expired"));

                assertEquals(1, cache.increment("counter", 1, 60));
                assertEquals(2, cache.increment("counter", 1, 60));
                long ttl = cache.ttlSeconds("counter");
                assertTrue(ttl > 0 && ttl <= 60, "ttl=" + ttl);

                cache.put("object", "中文", 60);
                assertEquals("中文", cache.get("object"));

                cache.forget("value");
                assertNull(cache.get("value"));

                cache.flush();
                assertNull(cache.get("forever"));
                assertNull(cache.get("counter"));
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static Process start(Path binary, List<String> template, int port)
            throws IOException {
        List<String> command = new java.util.ArrayList<>();
        command.add(binary.toString());
        for (String arg : template) {
            command.add("PORT".equals(arg) ? String.valueOf(port) : arg);
        }

        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void waitUntilReady(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                jedis.ping();

                return;
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("缓存服务器未在 20s 内就绪（port=" + port + "）");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path binary(String property, String fallback) {
        String value = System.getProperty(property, fallback);

        return Path.of(value).toAbsolutePath().normalize();
    }
}
