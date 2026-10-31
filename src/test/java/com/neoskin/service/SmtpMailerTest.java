package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.neoskin.config.NeoSkinSettings;

/**
 * SMTP 端到端：用 {@code tools/mailpit} 收信，再通过它的 HTTP API 校验。
 *
 * <p>验证 {@link Mailer} 的 smtp 驱动在真实 SMTP 服务器上的行为
 * （{@code MAIL_MAILER=smtp} 是源默认）。二进制缺失时跳过。</p>
 */
class SmtpMailerTest {

    private static final Path BINARY = binary("neoskin.mailpit.binary",
            "../tools/mailpit/mailpit.exe");

    @Test
    void sendsThroughRealSmtpServer() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(BINARY), "未找到 mailpit：" + BINARY);
        int smtpPort = freePort();
        int httpPort = freePort();
        Process process = start(smtpPort, httpPort);
        try {
            waitUntilReady(httpPort);

            NeoSkinSettings settings = new NeoSkinSettings();
            settings.getMail().setMailer("smtp");
            settings.getMail().setHost("127.0.0.1");
            settings.getMail().setPort(smtpPort);
            settings.getMail().setEncryption("");

            new Mailer(settings).send("user@example.com", "验证邮件",
                    "<p>hello</p>");

            HttpClient client = HttpClient.newHttpClient();
            String body = null;
            for (int i = 0; i < 50; i++) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + httpPort + "/api/v1/messages")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                body = response.body();
                if (body.contains("验证邮件")) {
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(body != null && body.contains("验证邮件"),
                    "mailpit 应收到主题为「验证邮件」的信： " + body);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static Process start(int smtpPort, int httpPort) throws IOException {
        return new ProcessBuilder(java.util.List.of(
                BINARY.toString(),
                "-s", "127.0.0.1:" + smtpPort,
                "-l", "127.0.0.1:" + httpPort))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void waitUntilReady(int httpPort) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + httpPort + "/api/v1/messages"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 端口尚未监听
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("mailpit 未在 20s 内就绪（port=" + httpPort + "）");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path binary(String property, String fallback) {
        String value = System.getProperty(property, fallback);

        return Path.of(value).toAbsolutePath().normalize();
    }
}
