package com.neoskin.web.view;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端资源路径表 —— 把 {@code resources/views/assets/*.twig} 的内容搬到 Java 侧。
 *
 * <p>源项目里那 5 个片段是 <b>webpack 构建产物</b>（{@code assets/.gitignore} 是
 * {@code *} + {@code !.gitignore}），由 {@code HtmlWebpackPlugin} 每次构建重写：</p>
 * <ul>
 *   <li>生产：{@code {{ cdn_base }}/app/app.<hash>.js}（{@code cdn_base} 来自
 *       {@code assets.*} composer 的 {@code option('cdn_address')}）；</li>
 *   <li>开发：{@code //localhost:8080/app/app.js}（dev-server，文件名<b>无哈希</b>，
 *       Gitpod 下换成 {@code gp url 8080}）。</li>
 * </ul>
 *
 * <p>因此后端模板<b>不能</b>把哈希路径写死在 HTML 里，否则「前后端分离 + 前端
 * dev-server」模式直接失效（dev 下根本不存在 {@code app.7278b90.js}）。
 * 这也是 PORT-NOTES 偏差 9.1 的修复点。</p>
 *
 * <p>哈希值与源仓库 {@code public/app/} 的实际产物一致；换前端版本时只需改这里
 * （或改为读取构建清单）。</p>
 */
public final class AssetManifest {

    /** dev-server 的默认地址（webpack.config.ts 的 devServer 默认 8080 + publicPath /app/）。 */
    public static final String DEV_ROOT = "//localhost:8080/app/";

    /** 生产哈希（取自源仓库 {@code public/app/} 实际文件名）。 */
    private static final String PROD_APP = "app.7278b90.js";

    private static final String PROD_STYLE = "style.1dd8a8a.css";

    private static final String PROD_HOME = "home.fe8cbb3.js";

    private static final String PROD_HOME_CSS = "home-css.6c76352.css";

    private static final String PROD_SPECTRE = "spectre.e885dd0.css";

    /** dev 模式的文件名（无 contenthash）。 */
    private static final String DEV_APP = "app.js";

    private static final String DEV_STYLE = "style.css";

    private static final String DEV_HOME = "home.js";

    private static final String DEV_HOME_CSS = "home-css.css";

    private static final String DEV_SPECTRE = "spectre.css";

    private AssetManifest() {
    }

    /**
     * 生成资源 URL 表。
     *
     * @param debug    {@code app.debug}；true → 指向 webpack dev-server
     * @param cdnBase  {@code option('cdn_address')}；空串表示同源
     * @return {@code app/style/home/home_css/spectre} 四个 URL
     */
    public static Map<String, String> urls(boolean debug, String cdnBase) {
        Map<String, String> urls = new LinkedHashMap<>();

        if (debug) {
            urls.put("app", DEV_ROOT + DEV_APP);
            urls.put("style", DEV_ROOT + DEV_STYLE);
            urls.put("home", DEV_ROOT + DEV_HOME);
            urls.put("home_css", DEV_ROOT + DEV_HOME_CSS);
            urls.put("spectre", DEV_ROOT + DEV_SPECTRE);
            return urls;
        }

        String base = (cdnBase == null ? "" : cdnBase);
        // 源模板是 {{ cdn_base }}/app/...：cdn_base 为空时就成了 /app/...
        String root = base + "/app/";
        urls.put("app", root + PROD_APP);
        urls.put("style", root + PROD_STYLE);
        urls.put("home", root + PROD_HOME);
        urls.put("home_css", root + PROD_HOME_CSS);
        urls.put("spectre", root + PROD_SPECTRE);
        return urls;
    }
}
