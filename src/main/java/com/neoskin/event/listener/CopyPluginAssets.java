package com.neoskin.event.listener;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.service.Plugin;

/**
 * {@code PluginWasEnabled} / {@code plugin.versionChanged} 监听者：把插件的
 * {@code assets/} 复制到 {@code public/plugins/<name>/}
 * （源 {@code App\Listeners\CopyPluginAssets}）。
 */
public final class CopyPluginAssets {

    private static final Logger log = LoggerFactory.getLogger(CopyPluginAssets.class);

    /** @param publicDir 站点 public 根目录 */
    public void handle(Plugin plugin, Path publicDir) {
        Path source = plugin.path() == null ? null : plugin.path().resolve("assets");
        Path target = publicDir.resolve("plugins").resolve(plugin.name());
        try {
            deleteRecursively(target);
            if (source == null || !Files.isDirectory(source)) {
                return;
            }
            copyRecursively(source, target);
        } catch (IOException e) {
            log.warn("cannot copy assets of plugin {}: {}", plugin.name(), e.getMessage());
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);

                return FileVisitResult.CONTINUE;
            }
        });
    }
}
