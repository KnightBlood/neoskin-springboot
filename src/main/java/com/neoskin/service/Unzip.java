package com.neoskin.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code App\Services\Unzip} 的移植：把 ZIP 直接解压到站点根目录（旧式补丁包）。
 */
public final class Unzip {

    /** {@code extract($file, $destination)}；成功后删除源文件。 */
    public void extract(Path file, Path destination) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new RuntimeException("admin.download.errors.unzip");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("解压运行包失败。", e);
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 与 PHP 的 Filesystem::delete 一样，删除失败不影响结果
        }
    }
}
