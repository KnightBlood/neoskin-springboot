package com.neoskin.web.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.neoskin.support.PhpJson.Array;

/**
 * {@code multipart/form-data} 请求体解码器。
 *
 * <p>对应 PHP/RFC 7578 语义：字段按 {@code name} 归位（含 PHP 的
 * {@code a[b][]} 形态展开，与 {@code $_POST} 一致）；带 {@code filename}
 * 的部件归为文件（{@code $_FILES}）。</p>
 */
public final class MultipartCodec {

    /** 解码结果：字段与文件两个「数组」。 */
    public record Result(Array fields, Array files) {
    }

    private MultipartCodec() {
    }

    public static Result parse(byte[] body, String contentType) {
        Array fields = new Array();
        Array files = new Array();

        String boundary = boundaryOf(contentType);
        if (body == null || boundary == null) {
            return new Result(fields, files);
        }

        byte[] delimBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);

        // 逐个 part 的起止（delimiter 出现的位置）
        List<int[]> spans = new ArrayList<>();
        int idx = indexOf(body, delimBytes, 0);
        while (idx >= 0) {
            int start = idx + delimBytes.length;
            boolean isFinal = start + 1 < body.length && body[start] == '-'
                    && body[start + 1] == '-';
            int next = isFinal ? body.length : indexOf(body, delimBytes, start);
            spans.add(new int[] {start, isFinal ? -1 : next});
            idx = next;
        }

        for (int[] span : spans) {
            if (span[1] < 0) {
                break;
            }

            int[] part = data(body, span);
            if (part != null) {
                parsePart(body, part[0], part[1], fields, files);
            }
        }

        return new Result(fields, files);
    }

    /** 拿到 part 的数据边界（去掉 delimiter 前的 CRLF）。 */
    private static int[] data(byte[] body, int[] span) {
        int i = span[0];
        int end = span[1];

        // delimiter 后的 CRLF
        if (i + 1 < end && body[i] == '\r' && body[i + 1] == '\n') {
            i += 2;
        } else if (i < end && body[i] == '\n') {
            i += 1;
        } else {
            return null;
        }

        if (end >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') {
            return new int[] {i, end - 2};
        }
        if (end >= 1 && body[end - 1] == '\n') {
            return new int[] {i, end - 1};
        }

        return new int[] {i, end};
    }

    private static void parsePart(byte[] body, int from, int to, Array fields, Array files) {
        if (from >= to) {
            return;
        }

        // 头与体分隔：CRLFCRLF（或 LF LF）
        int headEnd = indexOf(body, new byte[] {'\r', '\n', '\r', '\n'}, from);
        int bodyStart;

        if (headEnd >= 0 && headEnd + 4 <= to) {
            bodyStart = headEnd + 4;
        } else {
            headEnd = indexOf(body, new byte[] {'\n', '\n'}, from);
            if (headEnd < 0 || headEnd + 2 > to) {
                return;
            }
            bodyStart = headEnd + 2;
        }

        String headers = new String(body, from, headEnd - from, StandardCharsets.UTF_8);
        String name = headerValue(headers, "name");
        String filename = headerValue(headers, "filename");

        if (name == null) {
            return;
        }

        if (filename != null) {
            byte[] payload = new byte[to - bodyStart];
            System.arraycopy(body, bodyStart, payload, 0, payload.length);
            com.neoskin.web.middleware.RequestInput.putNested(files, name,
                    new UploadedFile(filename,
                            headerLine(headers, "content-type"), payload));
        } else {
            byte[] payload = new byte[to - bodyStart];
            System.arraycopy(body, bodyStart, payload, 0, payload.length);
            com.neoskin.web.middleware.RequestInput.putNested(fields, name,
                    new String(payload, StandardCharsets.UTF_8));
        }
    }

    private static String headerValue(String headers, String attr) {
        String needle = attr + "=\"";
        int di = headers.indexOf(needle);

        if (di < 0) {
            return null;
        }

        int start = di + needle.length();
        int end = headers.indexOf('"', start);

        return end < 0 ? null : headers.substring(start, end);
    }

    /** {@code Content-Type: image/png} 这类单行头。 */
    private static String headerLine(String headers, String attr) {
        for (String line : headers.split("\r?\n")) {
            int colon = line.indexOf(':');

            if (colon < 0) {
                continue;
            }

            if (line.substring(0, colon).trim().toLowerCase().equals(attr)) {
                return line.substring(colon + 1).trim();
            }
        }

        return null;
    }

    private static String boundaryOf(String contentType) {
        for (String seg : contentType.split(";")) {
            String trimmed = seg.trim();

            if (trimmed.toLowerCase().startsWith("boundary=")) {
                String b = trimmed.substring("boundary=".length()).trim();

                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }

                return b;
            }
        }

        return null;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = from; i <= data.length - pattern.length; i++) {
            boolean ok = true;

            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    ok = false;
                    break;
                }
            }

            if (ok) {
                return i;
            }
        }

        return -1;
    }
}
