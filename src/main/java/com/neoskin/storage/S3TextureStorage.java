package com.neoskin.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 兼容纹理存储（源 {@code config/filesystems.php} 的 textures 盘，
 * {@code FS_DRIVER=s3}）。
 *
 * <p>对象键 = {@code <prefix>/<hash>}，其中 prefix 来自 {@code TEXTURES_DIR}
 * （源默认 {@code storage_path('textures')}）。路径风格访问（path-style）以兼容
 * Silo / RustFS / MinIO 等自托管服务。</p>
 */
public final class S3TextureStorage implements TextureStorage, AutoCloseable {

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3TextureStorage(String endpoint, String region, String accessKey, String secretKey,
            String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "FS_DRIVER=s3 需要 AWS_BUCKET（源 config/filesystems.php 的 s3.bucket）");
        }
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        var builder = S3Client.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .forcePathStyle(true);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        accessKey == null || accessKey.isBlank() ? "local" : accessKey,
                        secretKey == null ? "" : secretKey)));
        this.client = builder.build();
    }

    /** 测试用：直接注入 client。 */
    public S3TextureStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public boolean exists(String hash) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key(hash)).build());

            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 404 ? false : throwUnchecked(e);
        }
    }

    @Override
    public byte[] read(String hash) throws IOException {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key(hash)).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(hash);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new NoSuchFileException(hash);
            }
            throw new IOException("S3 read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String hash, byte[] bytes) throws IOException {
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key(hash))
                    .contentType("image/png")
                    .build(), RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw new IOException("S3 write failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String hash) throws IOException {
        try {
            client.deleteObject(b -> b.bucket(bucket).key(key(hash)));
        } catch (S3Exception e) {
            throw new IOException("S3 delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long lastModifiedMillis(String hash) throws IOException {
        try {
            return client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key(hash)).build())
                    .lastModified().toEpochMilli();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(hash);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new NoSuchFileException(hash);
            }
            throw new IOException("S3 head failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** 测试/诊断用。 */
    public String bucket() {
        return bucket;
    }

    String key(String hash) {
        return prefix.isEmpty() ? hash : prefix + "/" + hash;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String value = prefix.replace('\\', '/').trim();

        return value.replaceAll("^/+|/+$", "");
    }

    private static boolean throwUnchecked(S3Exception e) {
        throw new UncheckedIOException(new IOException("S3 exists failed: " + e.getMessage(), e));
    }
}
