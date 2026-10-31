package com.neoskin.web.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 错误 JSON 的 trace：源 {@code Handler::convertExceptionToArray()} 只保留
 * {@code app/} 下的栈帧；Java 侧按 {@code com.neoskin.} 前缀对应。
 */
class ErrorTraceTest {

    @Test
    void traceKeepsOnlyApplicationFrames() {
        RuntimeException failure = failureInAppCode();

        List<Map<String, Object>> frames = ErrorHandler.traceFrames(failure);

        assertFalse(frames.isEmpty());
        Map<String, Object> first = frames.get(0);
        assertEquals("com/neoskin/web/error/ErrorTraceTest.java", first.get("file"));
        assertTrue(((Number) first.get("line")).intValue() > 0);
        assertTrue(frames.stream().allMatch(frame ->
                String.valueOf(frame.get("file")).startsWith("com/neoskin/")),
                "不应包含 JDK/框架帧：" + frames);
    }

    @Test
    void jsonBodySerializesTrace() {
        String body = ErrorPages.jsonBody("boom", List.of(Map.of(
                "file", "com/neoskin/web/controllers/AuthController.java", "line", 42)));

        assertTrue(body.contains("\"message\": \"boom\""), body);
        assertTrue(body.contains("\"exception\": true"), body);
        assertTrue(body.contains("\"file\": \"com/neoskin/web/controllers/AuthController.java\""),
                body);
        assertTrue(body.contains("\"line\": 42"), body);
    }

    @Test
    void emptyTraceStaysEmptyArray() {
        assertTrue(ErrorPages.jsonBody("m", List.of()).contains("\"trace\": []"));
        assertTrue(ErrorPages.jsonBody("m").contains("\"trace\": []"));
    }

    @Test
    void traceOnlyInDebugModeForServerErrors() {
        WebException error = WebException.http(500, "boom");
        RuntimeException failure = failureInAppCode();

        String noDebug = ErrorHandler.jsonBody(error, true, failure, false);
        assertTrue(noDebug.contains("\"trace\": []"), noDebug);

        String withDebug = ErrorHandler.jsonBody(error, true, failure, true);
        assertTrue(withDebug.contains("com/neoskin/web/error/ErrorTraceTest.java"), withDebug);
    }

    private static RuntimeException failureInAppCode() {
        try {
            throw new IllegalStateException("boom");
        } catch (RuntimeException e) {
            return e;
        }
    }
}
