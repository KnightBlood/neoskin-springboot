package com.neoskin.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地纹理存储（{@code storage/textures/<hash>}），与 PHP 版默认盘一致。
 */
public final class LocalTextureStorage implements TextureStorage {

    private final Path root;

    public LocalTextureStorage(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    @Override
    public boolean exists(String hash) {
        return Files.isRegularFile(root.resolve(hash));
    }

    @Override
    public byte[] read(String hash) throws IOException {
        return Files.readAllBytes(root.resolve(hash));
    }

    @Override
    public void write(String hash, byte[] bytes) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve(hash), bytes);
    }

    @Override
    public void delete(String hash) throws IOException {
        Files.deleteIfExists(root.resolve(hash));
    }

    @Override
    public long lastModifiedMillis(String hash) throws IOException {
        return Files.getLastModifiedTime(root.resolve(hash)).toMillis();
    }
}
