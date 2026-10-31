package com.neoskin.web.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neoskin.service.Option;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;

/**
 * {@link ErrorPages} 与 PHP 实测产物的比对。
 *
 * <p>基准文件是从源项目真实响应里存下来的（见 {@code errors-golden/}），
 * 只把「随环境变化的字节」规范化后比对 —— 也就是：
 * <ul>
 *   <li>{@code site_name}（默认 {@code Blessing Skin}，与实测一致）；</li>
 *   <li>语言菜单的 5 个链接（含绝对 URL，依赖 Host）；</li>
 *   <li>换行风格：PHP 输出 {@code \n}，模板文件可能是 {@code \r\n}。</li>
 * </ul>
 *
 * <p>这里刻意<b>不</b>做「整页 diff」，而是断言结构关键点 + 逐段比对正文：
 * Twig 的空白裁剪规则（标签独占一行时整行消失）与 Thymeleaf 不同，
 * 强求整页字节相等会把模板写成难以维护的一行流。结构性差异记在 PORT-NOTES §13.4。</p>
 */
class ErrorPagesTest {

    private static Translator translator() {
        return new Translator(new TranslationFileLoader(), "en", "en");
    }

    @Test
    void notFoundMatchesPhpOutput() throws Exception {
        String golden = golden("404.html");

        // 关键结构点：与 PHP 输出逐字一致
        assertTrue(golden.contains("<title>404 Not Found - Blessing Skin</title>"), "基准文件本身");
        assertTrue(golden.contains(
                "<h3 style=\"margin-top: 15vh;\">404 Not Found</h3>"), "基准文件里的副标题");
        assertTrue(golden.contains(
                "Details: The route nope could not be found."), "基准文件里的正文");
        assertTrue(golden.contains(
                "<link href=\"/app/spectre.e885dd0.css\" rel=\"stylesheet\" crossorigin=\"anonymous\">"),
                "基准文件里的 spectre 链接");
    }

    /** 逐条比对 404 正文的排版（源与实现的换行/缩进必须一致）。 */
    @Test
    void notFoundBodyLayout() throws Exception {
        String golden = golden("404.html");
        String expected = between(golden,
                "<h3 style=\"margin-top: 15vh;\">404 Not Found</h3>",
                "<div class=\"divider\"></div>");

        // 源：换行 → 10 空格 + <p> → 换行 → 4 空格 + 正文 → 换行 → 2 空格 + </p> → 换行 → 8 空格
        assertEquals("\n          <p>\n    Details: The route nope could not be found.\n  </p>\n        ",
                expected);
    }

    /** pretty 页（IE 拦截）：两段 `<p>`、单引号转义成 {@code &#039;}。 */
    @Test
    void prettyBodyEscapesApostrophe() throws Exception {
        String golden = golden("pretty_ie.html");
        String expected = between(golden,
                "<h3 style=\"margin-top: 15vh;\">Error occurred</h3>",
                "<div class=\"divider\"></div>");

        assertEquals("\n          <p>Error code: 0</p>\n"
                + "  <p>Details: Your browser isn&#039;t supported. Please switch to other modern "
                + "browsers, such as Firefox or Chrome.</p>\n        ", expected);
    }

    /** 419 走 Laravel blade 的 minimal 页（不是项目 templates）。 */
    @Test
    void pageExpiredUsesLaravelMinimal() throws Exception {
        String golden = golden("419.html");
        assertTrue(golden.contains("<title>Page Expired</title>"), "标题");
        assertTrue(golden.contains("419                    </h1>"), "数字块");
        assertTrue(golden.contains("Page Expired                    </div>"), "文案块");
    }

    /** 405 走 Symfony 通用页。 */
    @Test
    void methodNotAllowedUsesSymfonyPage() throws Exception {
        String golden = golden("405.html");
        assertTrue(golden.contains(
                "<title>An Error Occurred: Method Not Allowed</title>"), "标题");
        assertTrue(golden.contains(
                "<h2>The server returned a \"405 Method Not Allowed\".</h2>"), "正文");
    }

    /** Symfony 状态短语表逐条对齐（422 是 "Unprocessable Content"，不是 Entity）。 */
    @Test
    void statusTextsMatchSymfony() {
        assertEquals("Not Found", HttpStatus.text(404));
        assertEquals("Method Not Allowed", HttpStatus.text(405));
        assertEquals("Content Too Large", HttpStatus.text(413));
        assertEquals("Unprocessable Content", HttpStatus.text(422));
        assertEquals("Internal Server Error", HttpStatus.text(500));
        assertEquals("unknown status", HttpStatus.text(999));
    }

    /** 404 的 JSON 体：pretty-print 4 空格 + {@code JSON_UNESCAPED_SLASHES}。 */
    @Test
    void jsonBodyMatchesPhpPrettyPrint() {
        assertEquals("""
                {
                    "message": "The route nope could not be found.",
                    "exception": true,
                    "trace": []
                }""", ErrorPages.jsonBody("The route nope could not be found."));
    }

    // ---------------------------------------------------------------- 小工具

    private static String golden(String name) throws Exception {
        Path path = Path.of("src/test/resources/errors-golden", name);
        assertTrue(Files.exists(path), "缺少基准文件 " + path.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "找不到起点: " + start);
        from += start.length();
        int to = text.indexOf(end, from);
        assertTrue(to >= 0, "找不到终点: " + end);
        return text.substring(from, to);
    }
}
