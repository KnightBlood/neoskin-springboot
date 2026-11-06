package com.neoskin.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link AssetManifest}：生产哈希 vs 前端 dev-server。
 *
 * <p>源项目里这两条路径由 webpack 的 {@code HtmlWebpackPlugin} 分别写进
 * {@code resources/views/assets/*.twig}；Java 侧不能写死，否则前后端分离开发
 * （前端跑 {@code yarn dev}）时资源 404。</p>
 */
class AssetManifestTest {

    @Test
    void productionUsesHashedFilenames() {
        Map<String, String> urls = AssetManifest.urls(false, "");

        // CI 使用 6.0.10 发布包时哈希不同；只断言「带哈希的产物路径」。
        assertTrue(urls.get("app").matches("/app/app\\.[^.]+\\.[a-z]+"), urls.get("app"));
        assertEquals(true, urls.get("app").endsWith(".js"), urls.get("app"));
        assertTrue(urls.get("style").matches("/app/style\\.[^.]+\\.css"), urls.get("style"));
        assertTrue(urls.get("home").matches("/app/home\\.[^.]+\\.js"), urls.get("home"));
        assertTrue(urls.get("home_css").matches("/app/home-css\\.[^.]+\\.css"),
                urls.get("home_css"));
        assertTrue(urls.get("spectre").matches("/app/spectre\\.[^.]+\\.css"),
                urls.get("spectre"));
    }

    @Test
    void productionPrefixesCdnBase() {
        Map<String, String> urls = AssetManifest.urls(false, "https://cdn.example.com");
        assertTrue(urls.get("app").startsWith("https://cdn.example.com/app/app."),
                urls.get("app"));
        assertTrue(urls.get("app").endsWith(".js"), urls.get("app"));
    }

    @Test
    void debugPointsToDevServerWithoutHash() {
        Map<String, String> urls = AssetManifest.urls(true, "");

        assertTrue(urls.get("app").startsWith(AssetManifest.DEV_ROOT), urls.get("app"));
        assertEquals(AssetManifest.DEV_ROOT + "app.js", urls.get("app"));
        assertEquals(AssetManifest.DEV_ROOT + "style.css", urls.get("style"));
        // dev 下绝不能出现生产哈希
        assertNotEquals(urls.get("app"), AssetManifest.urls(false, "").get("app"));
    }

    /** dev 模式下 cdn_address 不生效（资源来自 dev-server）。 */
    @Test
    void debugIgnoresCdnBase() {
        Map<String, String> urls = AssetManifest.urls(true, "https://cdn.example.com");
        assertTrue(urls.get("app").startsWith(AssetManifest.DEV_ROOT), urls.get("app"));
    }
}
