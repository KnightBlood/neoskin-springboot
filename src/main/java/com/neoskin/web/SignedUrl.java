package com.neoskin.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Laravel {@code UrlGenerator::signedRoute / temporarySignedRoute / hasValidSignature} 的移植。
 *
 * <p>HMAC key 用 {@code config('app.key')} 原字符串（含 {@code base64:} 前缀）。</p>
 */
public final class SignedUrl {

    private SignedUrl() {
    }

    /** {@code temporarySignedRoute(name, expiryEpochSeconds, params, absolute)}。 */
    public static String temporarySignedRoute(String name, long expiryEpochSeconds,
            Map<String, Object> params, boolean absolute, RouteUrlGenerator routeUrl, String appKey) {
        Map<String, Object> all = new LinkedHashMap<>(params == null ? Map.of() : params);
        all.put("expires", expiryEpochSeconds);
        // ksort
        Map<String, Object> sorted = new TreeMap<>(all);
        String signed = routeUrl.route(name, sorted, absolute);
        String signature = hmacSha256(signed, appKey);
        Map<String, Object> withSig = new LinkedHashMap<>(sorted);
        withSig.put("signature", signature);
        return routeUrl.route(name, withSig, absolute);
    }

    /** {@code signedRoute(name, params, absolute)}：非临时签名（不带 {@code expires}）。 */
    public static String signedRoute(String name, Map<String, Object> params, boolean absolute,
            RouteUrlGenerator routeUrl, String appKey) {
        // ksort
        Map<String, Object> sorted = new TreeMap<>(params == null ? Map.of() : params);
        String signed = routeUrl.route(name, sorted, absolute);
        String signature = hmacSha256(signed, appKey);
        Map<String, Object> withSig = new LinkedHashMap<>(sorted);
        withSig.put("signature", signature);

        return routeUrl.route(name, withSig, absolute);
    }

    /** {@code hasValidSignature(false)}：用请求路径 + 去掉 signature 的 query 重建原 URL。 */
    public static boolean hasValidSignature(String requestPath, Map<String, String> queryParams,
            long nowEpochSeconds, String appKey, List<String> previousKeys) {
        String signature = queryParams.get("signature");
        if (signature == null || signature.isEmpty()) return false;
        String expiresRaw = queryParams.get("expires");
        // 源：isset($expires) && !is_numeric($expires) → 无效；临时签名才有 expires。
        if (expiresRaw != null) {
            if (!expiresRaw.matches("\\d+")) return false;
            if (nowEpochSeconds > Long.parseLong(expiresRaw)) return false;
        }

        // build original URL
        Map<String, String> q = new LinkedHashMap<>(queryParams);
        q.remove("signature");
        StringBuilder qs = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) qs.append('&');
            first = false;
            qs.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        String path = requestPath.startsWith("/") ? requestPath : "/" + requestPath;
        String original = path + (qs.length() == 0 ? "" : "?" + qs);
        original = rtrimQuestion(original);

        List<String> keys = new ArrayList<>();
        if (appKey != null) keys.add(appKey);
        if (previousKeys != null) keys.addAll(previousKeys);

        for (String key : keys) {
            if (hashEquals(hmacSha256(original, key), signature)) return true;
        }
        return false;
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    (key == null ? "" : key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hashEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String rtrimQuestion(String s) {
        while (s.endsWith("?")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
