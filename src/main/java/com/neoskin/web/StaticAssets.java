package com.neoskin.web;

import java.nio.file.Files;
import java.nio.file.Path;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;

/**
 * 静态资源服务 —— 等价于 PHP 侧「Web 服务器直接把 {@code public/} 当文档根」。
 *
 * <p>本工程没有 Servlet 容器（{@code AGENTS.md §4.4}），所以这块必须自己接：
 * 在 173 条路由<b>之前</b>挂一个 {@link StaticHandler}，未命中时自动
 * fallthrough 继续走动态路由。</p>
 *
 * <h2>为什么必须挂（不是可选项）</h2>
 * <p>{@code resources/views/assets/*.twig} 直接引用
 * {@code /app/app.7278b90.js}、{@code /app/style.1dd8a8a.css} 等；前端语言包由
 * {@code JavaScript} 服务生成到 {@code public/lang/<locale>.js}。没有这个 handler，
 * 首页会「200 但白屏」—— HTML 正常、JS 全 404。</p>
 *
 * <h2>与迁移决策的关系（PORT-NOTES §8）</h2>
 * <p>{@code public/app/} 是源项目的<b>预编译</b>产物，原样拷贝即可，
 * 不重新 webpack 打包。这里只负责「按 URL 把文件发出去」。</p>
 *
 * <h2>安全项（对齐 Web 服务器的默认行为）</h2>
 * <ul>
 *   <li>{@code setDirectoryListing(false)}：不列目录；</li>
 *   <li>{@code setIncludeHidden(false)}：不暴露点文件（{@code .env} 之类）；</li>
 *   <li>{@code setFilesReadOnly(true)}：只读；</li>
 *   <li>默认 {@code indexPage = "index.html"} 保留：{@code public/} 下没有
 *       index.html，所以 {@code /} 会 fallthrough 给
 *       {@code HomeController@index}，不会被静态 handler 抢走。</li>
 * </ul>
 */
public final class StaticAssets {

    private StaticAssets() {
    }

    /**
     * 构造静态资源 handler。
     *
     * @param publicDir {@code public_path()}；目录不存在时所有请求都 fallthrough，
     *                  等价于「没放静态文件」
     */
    public static Handler<RoutingContext> handler(Path publicDir) {
        // Vert.x 5 的 StaticHandler 把 webRoot 当作「文件系统根之下的相对路径」，
        // 直接传 "C:\..." 会抛 IllegalArgumentException: root cannot start with 'C:\'。
        // 用 FileSystemAccess.ROOT 显式声明「这是绝对路径」。
        String webRoot = publicDir.toAbsolutePath().normalize().toString();
        return StaticHandler.create(io.vertx.ext.web.handler.FileSystemAccess.ROOT, webRoot)
                .setFilesReadOnly(true)
                .setCachingEnabled(true)
                .setDirectoryListing(false)
                .setIncludeHidden(false);
    }

    /**
     * jar 内置前端产物：{@code classpath:/static/app/**}（{@code mvn package} 自动
     * 从源 {@code public/app/} 复制）。
     *
     * <p>不用 Vert.x 的 {@code StaticHandler.create("static")}：Spring Boot fat jar
     * 里资源在嵌套 jar（{@code BOOT-INF/classes/static}），Vert.x 的 classpath
     * FileResolver 读不到，会静默 404。这里用类加载器直接读字节。</p>
     */
    public static Handler<RoutingContext> classpathHandler() {
        return classpathHandler(null);
    }

    /**
     * @param publicDir 磁盘 {@code public/}（{@code /lang/*.js} 由 {@code JavaScript}
     *                  写在这里；{@code public/app} 缺失时 {@code /app/*} 走 jar 内置）
     */
    public static Handler<RoutingContext> classpathHandler(Path publicDir) {
        return ctx -> {
            String path = ctx.normalizedPath();
            if (path.startsWith("/lang/")) {
                if (publicDir == null) {
                    ctx.next();
                    return;
                }
                java.nio.file.Path file = publicDir.resolve(path.substring(1));
                if (!java.nio.file.Files.isRegularFile(file)) {
                    ctx.next();
                    return;
                }
                try {
                    ctx.response()
                            .putHeader("Content-Type", "text/javascript; charset=UTF-8")
                            .putHeader("Cache-Control", "no-cache")
                            .end(io.vertx.core.buffer.Buffer.buffer(
                                    java.nio.file.Files.readAllBytes(file)));
                } catch (java.io.IOException e) {
                    ctx.fail(e);
                }
                return;
            }
            if (!path.startsWith("/app/")) {
                ctx.next();
                return;
            }
            String resource = "static" + path;
            try (java.io.InputStream in = StaticAssets.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (in == null) {
                    ctx.next();
                    return;
                }
                byte[] bytes = in.readAllBytes();
                ctx.response()
                        .putHeader("Content-Type", contentType(path))
                        .putHeader("Cache-Control", "public, max-age=31536000")
                        .end(io.vertx.core.buffer.Buffer.buffer(bytes));
            } catch (java.io.IOException e) {
                ctx.fail(e);
            }
        };
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".map") || lower.endsWith(".json")) {
            return "application/json";
        }

        return "application/octet-stream";
    }

    /**
     * 目录是否可用（装配期诊断用）。
     *
     * <p>源仓库的 {@code public/app/} 是<b>已提交</b>的预编译产物，部署时应整目录
     * 拷贝；为空说明打包漏了前端资产。</p>
     */
    public static boolean available(Path publicDir) {
        // 只认「public/app 存在」：只有空的 public（缺 app）时落回 jar 内置资源
        return publicDir != null && Files.isDirectory(publicDir.resolve("app"));
    }
}
