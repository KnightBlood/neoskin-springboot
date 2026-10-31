package com.neoskin.event.listener;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PluginWasEnabled} / {@code plugin.versionChanged} 监听者：删除前端语言包
 * 缓存（源 {@code App\Listeners\CleanUpFrontEndLocaleFiles}，删除
 * {@code public/lang/*.js}）。
 *
 * <p>Java 侧的 {@code /lang/<locale>.js} 是动态生成并写盘的（{@code JavaScript}），
 * 删除后会在下次访问时重建 —— 与源行为一致。</p>
 */
public final class CleanUpFrontEndLocaleFiles {

    private static final Logger log = LoggerFactory.getLogger(CleanUpFrontEndLocaleFiles.class);

    /** @param langDir {@code public/lang} */
    public int handle(Path langDir) {
        if (!Files.isDirectory(langDir)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(langDir, "*.js")) {
            for (Path file : files) {
                try {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("cannot delete locale file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("cannot scan locale dir {}: {}", langDir, e.getMessage());
        }

        return removed;
    }
}
