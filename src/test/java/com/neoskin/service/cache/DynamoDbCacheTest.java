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

/**
 * {@link DynamoDbCache} 的端到端测试：使用 {@code tools/dynamodb}（DynamoDB Local）。
 *
 * <p>二进制缺失时跳过；可用 {@code -Dneoskin.dynamodb.jar=...} 覆盖路径。</p>
 */
class DynamoDbCacheTest {

    private static final Path JAR = binary("neoskin.dynamodb.jar",
            "../tools/dynamodb/DynamoDBLocal.jar");
    private static final Path LIB = binary("neoskin.dynamodb.lib",
            "../tools/dynamodb/DynamoDBLocal_lib");

    @Test
    void cacheSemantics() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(JAR), "未找到 DynamoDB Local：" + JAR);
        int port = freePort();
        Process process = start(port);
        try {
            waitUntilReady(port);
            String table = "neoskin_cache_" + port;
            try (DynamoDbCache cache = new DynamoDbCache(
                    "http://127.0.0.1:" + port, "us-east-1", "local", "local",
                    table, "neoskin_test_" + port + "_")) {
                assertTrue(cache.available(), "DynamoDB Local 未就绪");

                cache.put("value", 42, 60);
                assertEquals(42, ((Number) cache.get("value")).intValue());
                assertNull(cache.get("missing"));

                cache.put("text", "中文", 60);
                assertEquals("中文", cache.get("text"));

                cache.put("forever", "v", 0);
                assertEquals("v", cache.get("forever"));
                assertEquals(0, cache.ttlSeconds("forever"));
                assertEquals(-1, cache.ttlSeconds("missing"));

                cache.put("expired", "v", -1);
                assertNull(cache.get("expired"));

                assertEquals(1, cache.increment("counter", 1, 60));
                assertEquals(2, cache.increment("counter", 1, 60));
                long ttl = cache.ttlSeconds("counter");
                assertTrue(ttl > 0 && ttl <= 60, "ttl=" + ttl);

                cache.forget("value");
                assertNull(cache.get("value"));

                cache.put("to-flush", "v", 60);
                cache.flush();
                assertNull(cache.get("to-flush"));
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static Process start(int port) throws IOException {
        return new ProcessBuilder(List.of(
                "java",
                "-Djava.library.path=" + LIB,
                "-jar", JAR.toString(),
                "-inMemory", "-sharedDb", "-port", String.valueOf(port)))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void waitUntilReady(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("DynamoDB Local 未在 30s 内就绪（port=" + port + "）");
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
