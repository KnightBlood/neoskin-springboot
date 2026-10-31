package com.neoskin.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/options.php} 的默认值表。
 *
 * <p>源项目的 {@code Option::get()} 在数据库/缓存里找不到某个键时，会回退到
 * {@code config('options')} 并<b>顺手写回数据库</b>。因此这张表不只是「默认值」，
 * 它决定了升级后新增选项的初始值。键名与顺序必须与源文件逐字一致
 * （顺序影响 {@code options:cache} 生成的文件内容，进而影响 diff）。
 *
 * <p>共 54 个键，与 {@code config/options.php} 实测一致（用 PHP 直接 {@code require} 该文件
 * 统计得到）。注意 {@code max_texture_width} <b>不在</b>这张表里——它由
 * {@code 2025_06_29_064944_add_max_texture_width_option} 这个 migration 单独写入
 * 8192，属于数据迁移而非配置默认值，见 {@code DataMigrator}。
 */
public final class OptionDefaults {

    private OptionDefaults() {
    }

    private static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site_url", "");
        m.put("site_name", "Blessing Skin");
        m.put("site_description", "Open-source PHP Minecraft Skin Hosting Service");
        m.put("register_with_player_name", "true");
        m.put("require_verification", "false");
        m.put("regs_per_ip", "3");
        m.put("announcement", "Welcome to Blessing Skin {version}!");
        m.put("home_pic_url", "./app/bg.webp");
        m.put("custom_css", "");
        m.put("custom_js", "");
        m.put("player_name_rule", "official");
        m.put("custom_player_name_regexp", "");
        m.put("player_name_length_min", "3");
        m.put("player_name_length_max", "16");
        m.put("user_initial_score", "1000");
        m.put("sign_gap_time", "24");
        m.put("sign_score", "10,100");
        m.put("score_per_storage", "true");
        m.put("private_score_per_storage", "10");
        m.put("return_score", "true");
        m.put("score_per_player", "100");
        m.put("sign_after_zero", "false");
        m.put("version", "");
        m.put("copyright_text",
                "<b>Copyright &copy; {year} <a href=\"{site_url}\">{site_name}</a>.</b> All rights reserved.");
        m.put("auto_del_invalid_texture", "false");
        m.put("allow_downloading_texture", "true");
        m.put("texture_name_regexp", "");
        m.put("cache_expire_time", "31536000");
        m.put("max_upload_file_size", "1024");
        m.put("force_ssl", "false");
        m.put("auto_detect_asset_url", "true");
        m.put("plugins_enabled", "");
        m.put("copyright_prefer", "0");
        m.put("score_per_closet_item", "0");
        m.put("favicon_url", "app/favicon.ico");
        m.put("score_award_per_texture", "0");
        m.put("take_back_scores_after_deletion", "true");
        m.put("score_award_per_like", "0");
        m.put("meta_keywords", "");
        m.put("meta_description", "");
        m.put("meta_extras", "");
        m.put("cdn_address", "");
        m.put("update_source", "");
        m.put("plugins_registry", "");
        m.put("recaptcha_sitekey", "");
        m.put("recaptcha_secretkey", "");
        m.put("recaptcha_invisible", "false");
        m.put("reporter_score_modification", "0");
        m.put("reporter_reward_score", "0");
        m.put("content_policy", "");
        m.put("transparent_navbar", "false");
        m.put("status_code_for_private", "403");
        m.put("navbar_color", "cyan");
        m.put("sidebar_color", "dark-maroon");
        DEFAULTS = java.util.Collections.unmodifiableMap(m);
    }

    /** 不可变的默认值表（保持 config/options.php 的键顺序）。 */
    public static Map<String, String> all() {
        return DEFAULTS;
    }

    /** 某个键的默认值；不存在返回 {@code null}（对应 {@code Arr::has()} 为 false）。 */
    public static String get(String key) {
        return DEFAULTS.get(key);
    }

    public static boolean has(String key) {
        return DEFAULTS.containsKey(key);
    }
}
