package com.neoskin.web.http;

/**
 * 分段上传的文件部件（对应 PHP {@code $_FILES} 项）。
 *
 * <p> {@code bytes} 是客户端原文（Laravel {@code UploadedFile} 的临时文件内容）。</p>
 */
public record UploadedFile(String originalFilename, String clientMime, byte[] bytes) {

    /** 文件有效性（Laravel {@code isValid()}：无错误码即有效）。 */
    public boolean isValid() {
        return bytes != null;
    }

    /** 字节数。 */
    public int size() {
        return bytes == null ? 0 : bytes.length;
    }
}
