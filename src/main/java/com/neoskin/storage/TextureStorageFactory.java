package com.neoskin.storage;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

/**
 * 按 {@code FS_DRIVER} 选择纹理存储（源 {@code config/filesystems.php}）：
 *
 * <ul>
 *   <li>{@code local}（默认）→ {@link LocalTextureStorage}，根目录
 *       {@code TEXTURES_DIR}；</li>
 *   <li>{@code s3} → {@link S3TextureStorage}，bucket 来自 {@code AWS_BUCKET}，
 *       前缀来自 {@code TEXTURES_DIR}；</li>
 *   <li>其它值 → 抛 {@link IllegalArgumentException}（源不支持未注册的 disk driver）。</li>
 * </ul>
 */
public final class TextureStorageFactory {

    private TextureStorageFactory() {
    }

    public static TextureStorage create(NeoSkinSettings settings, AppPaths paths) {
        NeoSkinSettings.Storage storage = settings.getStorage();
        String driver = storage.getFsDriver() == null ? "local"
                : storage.getFsDriver().trim().toLowerCase();
        return switch (driver) {
            case "local", "" -> new LocalTextureStorage(paths.textures());
            case "s3" -> new S3TextureStorage(storage.getS3Endpoint(), storage.getS3Region(),
                    storage.getS3AccessKey(), storage.getS3SecretKey(),
                    storage.getS3Bucket(), storage.getTextures());
            default -> throw new IllegalArgumentException("Unsupported FS_DRIVER: " + driver
                    + " (expected local / s3)");
        };
    }
}
