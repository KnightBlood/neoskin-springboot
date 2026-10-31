package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;

import com.neoskin.web.view.ViewEngine;

/**
 * 通讯中心模板的冒烟渲染：只验证 Thymeleaf 语法与变量契约，不启动数据库。
 */
class CommunicationsViewSmokeTest {

    @Test
    void adminCommunicationsListRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "admin");
        vars.put("editor_open", false);
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1L);
        item.put("title", "标题");
        item.put("kind", "message");
        item.put("status", "published");
        item.put("read_count", 0L);
        item.put("delivery_count", 2L);
        item.put("updated_at", "2026-10-30 00:00:00");
        history.add(item);
        vars.put("messages", history);
        vars.put("pagination", pager());
        String html = ViewEngine.render("admin/communications", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("站内信发布与草稿记录"));
    }

    @Test
    void adminCommunicationsEditorRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "admin");
        vars.put("editor_open", true);
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("title", "标题");
        editor.put("kind", "message");
        editor.put("editor", "blocks");
        editor.put("source", "");
        editor.put("locale", "");
        editor.put("blocks", List.of());
        Map<String, Object> aud = new LinkedHashMap<>();
        aud.put("receiver", "all");
        aud.put("uid", null);
        aud.put("email", null);
        editor.put("audience", aud);
        vars.put("editor_data", editor);
        vars.put("editor_data_json", "{}");
        vars.put("editor_data_json_css", "{}");
        List<Map<String, Object>> pages = new ArrayList<>();
        vars.put("messages", List.of());
        vars.put("pagination", pager());
        String html = ViewEngine.render("admin/communications", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("新建站内信"));
    }

    @Test
    void inboxRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        Map<String, Object> pager = pager();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "abc");
        row.put("display_title", "标题");
        row.put("read_at", null);
        row.put("created_at", "2026-10-30");
        items.add(row);
        pager.put("items", items);
        vars.put("messages", pager);
        String html = ViewEngine.render("user/inbox", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("暂无站内信") || html.contains("标题"));
    }

    @Test
    void messageRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        vars.put("message_title", "标题");
        vars.put("message_created_at", "2026-10-30");
        vars.put("body_html", "<p>正文</p>");
        String html = ViewEngine.render("user/message", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("正文"), html);
    }

    @Test
    void userIndexRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        vars.put("pageTitle", "仪表盘");
        vars.put("score_intro", "介绍");
        vars.put("score_intro_html", "介绍<br />\n第二行");
        Map<String, Object> rates = new LinkedHashMap<>();
        rates.put("storage", 1);
        rates.put("player", 2);
        rates.put("closet", 3);
        vars.put("rates", rates);
        vars.put("announcement", "<p>公告</p>");
        vars.put("extra_json", "{\"unverified\":false}");
        vars.put("gridRows", List.of(List.of(col("md-7",
                "user/widgets/email-verification", "user/widgets/dashboard/usage"),
                col("md-5", "user/widgets/dashboard/announcement"))));
        String html = ViewEngine.render("user/index", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("usage-box") && html.contains("公告"), html);
    }

    @Test
    void userProfileRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        vars.put("pageTitle", "个人资料");
        vars.put("user_nickname", "nick");
        vars.put("user_email", "a@b.c");
        vars.put("user_is_admin", false);
        vars.put("gridRows", List.of(List.of(col("md-6",
                "user/widgets/profile/avatar", "user/widgets/profile/password"),
                col("md-6", "user/widgets/profile/nickname", "user/widgets/profile/email",
                        "user/widgets/profile/delete-account"))));
        String html = ViewEngine.render("user/profile", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("change-nickname") && html.contains("change-email"), html);
    }

    @Test
    void userPlayerRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        vars.put("pageTitle", "角色管理");
        vars.put("extra_json", "{}");
        vars.put("gridRows", List.of(List.of(
                col("md-6", "user/widgets/players/list", "user/widgets/players/notice"),
                col("md-6", "shared/previewer"))));
        String html = ViewEngine.render("user/player", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("players-list") && html.contains("previewer"), html);
    }

    @Test
    void skinlibShowRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("pageTitle", "皮肤");
        vars.put("extra_json", "{}");
        vars.put("gridRows", List.of(List.of(
                col("md-8", "shared/previewer"),
                col("md-4", "skinlib/widgets/show/side"))));
        String html = ViewEngine.render("skinlib/show", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("previewer") && html.contains("id=\"side\""), html);
    }

    @Test
    void userClosetRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("scope", "user");
        vars.put("pageTitle", "收藏夹");
        vars.put("extra_json", "{}");
        vars.put("gridRows", List.of(List.of(
                col("md-8", "user/widgets/email-verification", "user/widgets/closet/list"),
                col("md-4", "shared/previewer"))));
        String html = ViewEngine.render("user/closet", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("email-verification") && html.contains("closet-list")
                && html.contains("previewer"), html);
    }

    @Test
    void skinlibUploadRenders() {
        Map<String, Object> vars = baseVars();
        vars.put("pageTitle", "上传");
        vars.put("extra_json", "{}");
        vars.put("gridRows", List.of(List.of(
                col("md-6", "skinlib/widgets/upload/input"),
                col("md-6", "shared/previewer"))));
        String html = ViewEngine.render("skinlib/upload", java.util.Locale.ROOT, vars);
        assertTrue(html.contains("file-input") && html.contains("previewer")
                && !html.contains("modals"), html);
    }

    private static Map<String, Object> col(String css, String... widgets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", css);
        m.put("widgets", List.of(widgets));
        return m;
    }

    private static Map<String, Object> pager() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total", 1L);
        p.put("current_page", 1);
        p.put("last_page", 1);
        p.put("on_first_page", true);
        p.put("has_more_pages", false);
        p.put("previous_page_url", null);
        p.put("next_page_url", null);
        p.put("items", List.of());
        List<Map<String, Object>> pages = new ArrayList<>();
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("number", 1L);
        one.put("url", "/x?page=1");
        one.put("current", true);
        pages.add(one);
        p.put("pages", pages);
        return p;
    }

    private static Map<String, Object> baseVars() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("locale", "zh-CN");
        v.put("pageTitle", "标题");
        v.put("site_name", "Blessing Skin");
        v.put("navbar_color", "cyan");
        v.put("color_mode", "dark");
        v.put("dark_mode", false);
        v.put("favicon", "/app/favicon.ico");
        v.put("theme_color", "#17a2b8");
        Map<String, Object> seo = new LinkedHashMap<>();
        seo.put("keywords", null);
        seo.put("description", null);
        seo.put("extra", null);
        v.put("seo", seo);
        v.put("links", List.of());
        v.put("inline_css", null);
        v.put("custom_cdn_host", null);
        v.put("blessing", Map.of());
        v.put("extra_head", null);
        Map<String, Object> assets = new LinkedHashMap<>();
        assets.put("style", "/app/style.css");
        assets.put("app", "/app/app.js");
        v.put("assets", assets);
        v.put("i18n", "");
        v.put("scripts", List.of());
        v.put("inline_js", null);
        v.put("extra_foot", null);
        v.put("copyright", 0);
        v.put("custom_copyright", null);
        v.put("site_url", "");
        v.put("sidebar_color", "dark");
        v.put("user", null);
        v.put("panel_avatar", "");
        v.put("panel_avatar_png", "");
        v.put("badges", List.of());
        v.put("user_nickname", null);
        v.put("avatar", "");
        v.put("avatar_png", "");
        v.put("menu", List.of());
        v.put("userItems", List.of());
        v.put("exploreItems", List.of());
        v.put("adminItems", List.of());
        v.put("current", "简体中文");
        v.put("langs", List.of());
        v.put("notifications", List.of());
        v.put("notifications_json", "[]");
        v.put("errors", new AuthController.ErrorsView(Map.of()));
        v.put("view", new com.neoskin.web.view.ViewContext(null, null, null, null, "zh_CN", "token", null, null));
        return v;
    }
}
