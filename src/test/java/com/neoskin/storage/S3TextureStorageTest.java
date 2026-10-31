package com.neoskin.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * {@link S3TextureStorage} 的端到端测试：Silo（MinIO fork）与 RustFS 各跑一遍。
 *
 * <p>覆盖：写/读/存在性/Last-Modified/删除。二进制缺失时跳过；可用
 * {@code -Dneoskin.silo.binary=...} / {@code -Dneoskin.rustfs.binary=...} 覆盖。</p>
 */
class S3TextureStorageTest {

    @TempDir
    Path dir;

    private static final Path SILO = binary("neoskin.silo.binary",
            "../tools/silo/silo.exe");
    private static final Path RUSTFS = binary("neoskin.rustfs.binary",
            "../tools/rustfs/rustfs.exe");

    private static final String ACCESS_KEY = "neoskin";
    private static final String SECRET_KEY = "neoskin123456";

    @Test
    void worksAgainstSilo() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SILO), "未找到 silo：" + SILO);
        int port = freePort();
        Process process = startSilo(port);
        try {
            waitUntilReady(port);
            runSuite(port);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void worksAgainstRustfs() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUSTFS), "未找到 rustfs：" + RUSTFS);
        int port = freePort();
        Process process = startRustfs(port);
        try {
            waitUntilReady(port);
            runSuite(port);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private void runSuite(int port) throws Exception {
        String bucket = "neoskin-test-" + port;
        String endpoint = "http://127.0.0.1:" + port;
        try (S3Client client = client(endpoint)) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            try (S3TextureStorage storage = new S3TextureStorage(endpoint, "us-east-1",
                    ACCESS_KEY, SECRET_KEY, bucket, "textures")) {
                byte[] payload = new byte[] { 1, 2, 3, 4 };
                assertFalse(storage.exists("hash1"));
                storage.write("hash1", payload);
                assertTrue(storage.exists("hash1"));
                assertArrayEquals(payload, storage.read("hash1"));
                assertTrue(storage.lastModifiedMillis("hash1") > 0);
                assertThrows(java.nio.file.NoSuchFileException.class,
                        () -> storage.read("missing"));
                storage.delete("hash1");
                assertFalse(storage.exists("hash1"));
                // 删除不存在的键也算成功
                storage.delete("hash1");
                assertEquals("textures/hash1", storage.key("hash1"));
            }
        }
    }

    private static S3Client client(String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }

    private Process startSilo(int port) throws IOException {
        Path data = dir.resolve("silo-" + port);
        Files.createDirectories(data);
        ProcessBuilder builder = new ProcessBuilder(List.of(
                SILO.toString(), "server", data.toString(), "--address", ":" + port));
        builder.environment().put("MINIO_ROOT_USER", ACCESS_KEY);
        builder.environment().put("MINIO_ROOT_PASSWORD", SECRET_KEY);

        return builder.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private Process startRustfs(int port) throws IOException {
        Path data = dir.resolve("rustfs-" + port);
        Files.createDirectories(data);

        return new ProcessBuilder(List.of(
                RUSTFS.toString(), "server", data.toString(), "--address", ":" + port,
                "--access-key", ACCESS_KEY, "--secret-key", SECRET_KEY))
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
        throw new IllegalStateException("S3 服务未在 30s 内就绪（port=" + port + "）");
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
