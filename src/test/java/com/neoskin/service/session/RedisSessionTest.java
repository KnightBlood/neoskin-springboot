package com.neoskin.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.service.cache.RedisCache;

import redis.clients.jedis.Jedis;

/**
 * {@code SESSION_DRIVER=redis} 的端到端测试：用 `tools/` 下自带的
 * Redis 与 Garnet 各跑一遍「工厂选择 → 跨实例会话保留 → destroy」。
 *
 * <p>二进制缺失时自动跳过；可用 {@code -Dneoskin.redis.binary=...} /
 * {@code -Dneoskin.garnet.binary=...} 覆盖路径。</p>
 */
class RedisSessionTest {

    @TempDir
    Path dir;

    private static final Path REDIS_BINARY = binary("neoskin.redis.binary",
            "../tools/redis-windows-8.10.2/redis-server.exe");
    private static final Path GARNET_BINARY = binary("neoskin.garnet.binary",
            "../tools/Garnet/GarnetServer.exe");

    @Test
    void redisBackedSessions() throws Exception {
        runSuite("redis", REDIS_BINARY,
                List.of("--port", "PORT", "--save", "", "--appendonly", "no"));
    }

    @Test
    void garnetBackedSessions() throws Exception {
        runSuite("garnet", GARNET_BINARY,
                List.of("--port", "PORT", "--bind", "127.0.0.1"));
    }

    // ================================================================ 工具

    private void runSuite(String name, Path binary, List<String> template) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(binary), "未找到 " + name + " 二进制：" + binary);
        int port = freePort();
        Process process = start(binary, template, port);
        try {
            waitUntilReady(port);

            SettingsBundle bundle = settings(port);
            NeoSkinSettings settings = bundle.settings();
            AppPaths paths = bundle.paths();

            // 工厂按 SESSION_DRIVER=redis 建了已连接的 RedisCache
            SessionHandler handler = SessionHandlerFactory.create(settings, paths);
            RedisCache cache = extractRedisCache(handler);
            assertTrue(cache.available(), name + " 未能连接");

            String id = SessionStore.generateSessionId();
            SessionStore first = new SessionStore("BS_SESSION", handler).setId(id);
            first.start();
            first.put("login_web_" + "sha1", 7L);
            first.flash("success", "登录成功");
            first.save();

            // 新实例（相当于另一个应用节点）读同一 id → 属性包原样恢复
            SessionHandler other = SessionHandlerFactory.create(settings, paths);
            SessionStore second = new SessionStore("BS_SESSION", other).setId(id).start();
            assertEquals(7L, ((Number) second.get("login_web_" + "sha1")).longValue());
            assertEquals("登录成功", second.get("success"));
            assertEquals(40, String.valueOf(second.token()).length());

            // destroy（退出登录 → migrate(true)）后彻底消失
            assertTrue(other.destroy(id));
            assertEquals("", other.read(id));

            cache.close();
            ((RedisCache) extractRedisCache(other)).close();
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private RedisCache extractRedisCache(SessionHandler handler) {
        CacheBasedSessionHandler cacheHandler = assertInstanceOf(
                CacheBasedSessionHandler.class, handler);

        return assertInstanceOf(RedisCache.class,
                value(cacheHandler, "cache"));
    }

    private static Object value(Object owner, String fieldName) {
        try {
            var field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);

            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private record SettingsBundle(NeoSkinSettings settings, AppPaths paths) {
    }

    private SettingsBundle settings(int port) throws Exception {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(dir.resolve("t.sqlite").toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        settings.getSession().setDriver("redis");
        settings.getCache().setPrefix("neoskin_sess_test_");
        settings.getCache().setRedisHost("127.0.0.1");
        settings.getCache().setRedisPort(port);
        settings.getCache().setRedisDatabase(0);
        Files.createDirectories(dir.resolve("storage"));

        return new SettingsBundle(settings, new AppPaths(dir, settings));
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
