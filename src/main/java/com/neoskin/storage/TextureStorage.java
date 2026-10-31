package com.neoskin.storage;

import java.io.IOException;

/**
 * 纹理文件存储（源 {@code Storage::disk('textures')} 的抽象）。
 *
 * <p>键是纹理的 SHA-256 文件名（32/64 位十六进制，见 {@code hash} 列）。
 * 两个实现：</p>
 * <ul>
 *   <li>{@link LocalTextureStorage}（{@code FS_DRIVER=local}，默认，等价
 *       {@code storage/textures/<hash>}）；</li>
 *   <li>{@link S3TextureStorage}（{@code FS_DRIVER=s3}，源 {@code config/filesystems.php}
 *       的 textures 盘切到 s3 时的行为）。</li>
 * </ul>
 */
public interface TextureStorage {

    /** {@code $disk->exists($hash)}。 */
    boolean exists(String hash);

    /** 读取文件内容（不存在时抛 {@link java.nio.file.NoSuchFileException}）。 */
    byte[] read(String hash) throws IOException;

    /** 覆盖写入。 */
    void write(String hash, byte[] bytes) throws IOException;

    /** 删除（不存在也算成功）。 */
    void delete(String hash) throws IOException;

    /** {@code Last-Modified} 用：毫秒时间戳。 */
    long lastModifiedMillis(String hash) throws IOException;
}
