package com.neoskin.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.config.NeoSkinSettings;
import com.neoskin.web.middleware.RequestInput;

/**
 * {@code App\Services\UploadLimits} 的移植：运行包上传上限。
 *
 * <p>PHP 侧读 {@code ini_get('upload_max_filesize')} / {@code post_max_size}；Java
 * 侧用同名系统属性（{@code neoskin.upload_max_filesize} 默认 {@code 2M}、
 * {@code neoskin.post_max_size} 默认 {@code 8M}、{@code neoskin.file_uploads} 默认 true）。
 * 应用上限取 {@code updates.max_upload_mb}（默认 256 MiB）。</p>
 */
public final class UploadLimits {

    private final NeoSkinSettings settings;

    public UploadLimits(NeoSkinSettings settings) {
        this.settings = settings;
    }

    /** {@code max(1, config('updates.max_upload_mb')) * 1048576}。 */
    public long applicationBytes() {
        return Math.max(1, settings.getUpdates().getMaxUploadMb()) * 1048576L;
    }

    private String phpUpload() {
        return System.getProperty("neoskin.upload_max_filesize", "2M");
    }

    private String phpPost() {
        return System.getProperty("neoskin.post_max_size", "8M");
    }

    private boolean enabled() {
        String raw = System.getProperty("neoskin.file_uploads", "1");
        return !(raw.isEmpty() || raw.equals("0") || raw.equalsIgnoreCase("false")
                || raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("no"));
    }

    /** {@code current()}：模板里的 upload_limits。 */
    public Map<String, Object> current() {
        long php = RequestInput.parsePostMaxSize(phpUpload());
        long app = applicationBytes();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("effective", format(Math.min(php, app)));
        info.put("php_upload", phpUpload());
        info.put("php_post", phpPost());
        info.put("application", format(app));
        info.put("enabled", enabled());

        return info;
    }

    /** {@code rtrim(rtrim(number_format($bytes/1048576,2,'.',''),'0'),'.').' MiB'}。 */
    private static String format(long bytes) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", bytes / 1048576.0);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }

        return text + " MiB";
    }
}
