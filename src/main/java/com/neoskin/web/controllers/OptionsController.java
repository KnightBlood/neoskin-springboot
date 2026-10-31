package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.ImageCache;
import com.neoskin.service.Option;
import com.neoskin.service.OptionForm;
import com.neoskin.service.UpdateSources;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.Validator;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\OptionsController} 的移植（个性化 / 站点配置 /
 * 积分配置 / 资源配置）。
 *
 * <p>四个页面都是「渲染 OptionForm 生成的 HTML + 处理 {@code option=<id>} 的 POST」。
 * 表单 HTML 由 {@link OptionForm} 生成，结构与源 {@code forms/*.twig} 一致。</p>
 */
public final class OptionsController {

    private static final String UPDATES_MESSAGE = """
            <p class="text-danger font-weight-bold mb-0">请注意，修改更新 URL 可能导致您的 \
            Blessing Skin 被恶意代码注入，进而影响您的数据、服务器或内网资产安全。<br>\
            我们无法 100% 保证第三方更新源的安全，修改这里之前<strong>请一定要知道你在做什么！\
            </strong></p><div class="mt-3 d-flex flex-wrap"><button type="submit" \
            name="update_source_preset" value="official" class="btn btn-default mr-2 mb-2">\
            切换到 bs-community 官方源</button><button type="submit" \
            name="update_source_preset" value="patch" class="btn btn-primary mb-2">\
            切换到 DreamCraft-Tech 第三方修补源</button></div>""";

    private final Option option;
    private final Translator translator;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final UserRepository users;
    private final boolean debug;
    private final NeoSkinSettings settings;
    private final AppPaths paths;
    private final ImageCache imageCache;
    private final UpdateSources updateSources;

    public OptionsController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, UserRepository users, boolean debug,
            NeoSkinSettings settings, AppPaths paths, ImageCache imageCache) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.filter = filter;
        this.users = users;
        this.debug = debug;
        this.settings = settings;
        this.paths = paths;
        this.imageCache = imageCache;
        this.updateSources = new UpdateSources(paths, settings);
    }

    // ============================================================ customize

    /** {@code customize()}。 */
    public Handler<RoutingContext> customize() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                boolean post = isPost(ctx);
                Map<String, Object> input = postMap(ctx);
                OptionForm.Context fc = formContext(ctx);

                if (post && "color".equals(string(input.get("action")))) {
                    if (truthy(input.get("navbar"))) {
                        option.set("navbar_color", input.get("navbar"));
                    }
                    if (truthy(input.get("sidebar"))) {
                        option.set("sidebar_color", input.get("sidebar"));
                    }
                }

                OptionForm homepage = OptionForm.create(fc, "homepage");
                homepage.text("home_pic_url").hint();
                homepage.text("favicon_url").hint().description();
                homepage.checkbox("transparent_navbar").label();
                homepage.checkbox("hide_intro").label();
                homepage.checkbox("fixed_bg").label();
                homepage.select("copyright_prefer")
                        .option("0", "Powered with \u2764 by Blessing Skin Server.")
                        .option("1", "Powered by Blessing Skin Server.")
                        .option("2", "Proudly powered by Blessing Skin Server.")
                        .option("3", "由 Blessing Skin Server 强力驱动。")
                        .option("4", "采用 Blessing Skin Server 搭建。")
                        .option("5", "使用 Blessing Skin Server 稳定运行。")
                        .option("6", "自豪地采用 Blessing Skin Server。")
                        .description();
                homepage.textarea("copyright_text").rows(6).description();
                homepage.handle(post, input, form -> {
                    option.set("copyright_prefer_" + locale, input.get("copyright_prefer"));
                    option.set("copyright_text_" + locale, input.get("copyright_text"));
                });

                OptionForm customJsCss = OptionForm.create(fc, "customJsCss");
                customJsCss.textarea("custom_css", "CSS").rows(6);
                customJsCss.textarea("custom_js", "JavaScript").rows(6);
                customJsCss.addMessageAuto();
                customJsCss.handle(post, input);

                Map<String, Object> forms = new LinkedHashMap<>();
                forms.put("homepage", homepage.render());
                forms.put("custom_js_css", customJsCss.render());

                Map<String, Object> colors = new LinkedHashMap<>();
                colors.put("navbar", List.of("primary", "secondary", "success", "danger",
                        "indigo", "purple", "pink", "teal", "cyan", "dark", "gray", "fuchsia",
                        "maroon", "olive", "navy", "lime", "light", "warning", "white",
                        "orange"));
                colors.put("sidebar", List.of("primary", "warning", "info", "danger", "success",
                        "indigo", "navy", "purple", "fuchsia", "pink", "maroon", "orange",
                        "lime", "teal", "olive"));

                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("navbar", option.get("navbar_color"));
                extra.put("sidebar", option.get("sidebar_color"));

                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans("general.customize", Map.of(), locale));
                vars.put("forms", forms);
                vars.put("colors", colors);
                vars.put("extra_json", PhpJson.encode(extra, 0));
                render(ctx, "admin/customize", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ score

    /** {@code score()}。 */
    public Handler<RoutingContext> score() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                boolean post = isPost(ctx);
                Map<String, Object> input = postMap(ctx);
                OptionForm.Context fc = formContext(ctx);

                OptionForm rate = OptionForm.create(fc, "rate");
                rate.group("score_per_storage").text("score_per_storage").addon();
                rate.group("private_score_per_storage").text("private_score_per_storage")
                        .addon().hint();
                rate.group("score_per_closet_item").text("score_per_closet_item").addon();
                rate.checkbox("return_score").label();
                rate.group("score_per_player").text("score_per_player").addon();
                rate.text("user_initial_score");
                rate.handle(post, input);

                OptionForm report = OptionForm.create(fc, "report");
                report.text("reporter_score_modification").description();
                report.text("reporter_reward_score");
                report.handle(post, input);

                OptionForm sign = OptionForm.create(fc, "sign");
                sign.group("sign_score")
                        .text("sign_score_from")
                        .addon(translator.trans("options.sign.sign_score.addon1", Map.of(),
                                locale))
                        .text("sign_score_to")
                        .addon(translator.trans("options.sign.sign_score.addon2", Map.of(),
                                locale));
                sign.group("sign_gap_time").text("sign_gap_time").addon();
                sign.checkbox("sign_after_zero").label().hint();
                sign.after(form -> option.set("sign_score",
                        string(input.get("sign_score_from")) + ","
                                + string(input.get("sign_score_to"))));
                String[] signScore = splitSignScore(option.get("sign_score", null, true));
                sign.with("sign_score_from", signScore[0]);
                sign.with("sign_score_to", signScore[1]);
                sign.handle(post, input);

                OptionForm sharing = OptionForm.create(fc, "sharing");
                sharing.group("score_award_per_texture").text("score_award_per_texture")
                        .addon(translator.trans("general.user.score", Map.of(), locale));
                sharing.checkbox("take_back_scores_after_deletion").label();
                sharing.group("score_award_per_like").text("score_award_per_like")
                        .addon(translator.trans("general.user.score", Map.of(), locale));
                sharing.handle(post, input);

                Map<String, Object> forms = new LinkedHashMap<>();
                forms.put("rate", rate.render());
                forms.put("report", report.render());
                forms.put("sign", sign.render());
                forms.put("sharing", sharing.render());

                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans("general.score-options", Map.of(), locale));
                vars.put("forms", forms);
                render(ctx, "admin/score", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code @explode(',', option('sign_score'))[0..1]}。 */
    private static String[] splitSignScore(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        String[] parts = value.split(",", -1);
        String from = parts.length > 0 ? parts[0] : "";
        String to = parts.length > 1 ? parts[1] : null;

        return new String[] {from, to};
    }

    // ============================================================ options

    /** {@code options()}。 */
    public Handler<RoutingContext> options() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                boolean post = isPost(ctx);
                Map<String, Object> input = postMap(ctx);
                OptionForm.Context fc = formContext(ctx);
                String phpUploadMax = System.getProperty("neoskin.upload_max_filesize", "2M");

                if (post && "updates".equals(string(input.get("option")))) {
                    Object preset = input.get("update_source_preset");
                    if (preset != null && !string(preset).isEmpty()
                            && !List.of("official", "patch").contains(string(preset))) {
                        Map<String, List<String>> errors = new LinkedHashMap<>();
                        errors.put("update_source_preset",
                                List.of("The selected update_source_preset is invalid."));
                        redirectWithErrors(ctx, errors);
                        return;
                    }
                    if (preset != null && !string(preset).isEmpty()) {
                        UpdateSources.preset(string(preset))
                                .forEach((key, value) -> input.put(key, value));
                    }

                    Map<String, List<String>> errors = validateUpdates(input);
                    if (!errors.isEmpty()) {
                        redirectWithErrors(ctx, errors);
                        return;
                    }
                    Map<String, String> sources = new LinkedHashMap<>();
                    sources.put("update_source", string(input.get("update_source")));
                    sources.put("plugins_registry", string(input.get("plugins_registry")));
                    try {
                        updateSources.save(sources);
                    } catch (RuntimeException e) {
                        Map<String, List<String>> saveErrors = new LinkedHashMap<>();
                        saveErrors.put("update_source", List.of(e.getMessage()));
                        redirectWithErrors(ctx, saveErrors);
                        return;
                    }
                }

                OptionForm general = OptionForm.create(fc, "general");
                general.text("site_name");
                general.text("site_description").description();
                general.text("site_url").hint().format(url -> {
                    String value = url == null ? "" : String.valueOf(url);
                    if (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    if (value.endsWith("/index.php")) {
                        value = value.substring(0, value.length() - 10);
                    }
                    return value;
                });
                general.checkbox("register_with_player_name").label();
                general.checkbox("require_verification").label();
                general.text("regs_per_ip");
                general.group("max_upload_file_size").text("max_upload_file_size")
                        .addon("KB")
                        .hint(translator.trans("options.general.max_upload_file_size.hint",
                                Map.of("size", phpUploadMax), locale));
                general.group("max_texture_width").text("max_texture_width").addon("px")
                        .hint();
                general.select("player_name_rule")
                        .option("official", translator.trans(
                                "options.general.player_name_rule.official", Map.of(), locale))
                        .option("cjk", translator.trans(
                                "options.general.player_name_rule.cjk", Map.of(), locale))
                        .option("utf8", translator.trans(
                                "options.general.player_name_rule.utf8", Map.of(), locale))
                        .option("custom", translator.trans(
                                "options.general.player_name_rule.custom", Map.of(), locale));
                general.text("custom_player_name_regexp").placeholder().hint();
                general.group("player_name_length").text("player_name_length_min")
                        .addon("~")
                        .text("player_name_length_max")
                        .addon(translator.trans("options.general.player_name_length.suffix",
                                Map.of(), locale));
                general.checkbox("auto_del_invalid_texture").label().hint();
                general.checkbox("allow_downloading_texture").label();
                general.select("status_code_for_private")
                        .option("403", "403 Forbidden")
                        .option("404", "404 Not Found");
                general.text("texture_name_regexp").placeholder().hint();
                general.textarea("content_policy").rows(3).description();
                general.handle(post, input, form -> {
                    option.set("site_name_" + locale, input.get("site_name"));
                    option.set("site_description_" + locale, input.get("site_description"));
                    option.set("content_policy_" + locale, input.get("content_policy"));
                });

                OptionForm meta = OptionForm.create(fc, "meta");
                meta.text("meta_keywords").hint();
                meta.text("meta_description").hint();
                meta.textarea("meta_extras").rows(6);
                meta.handle(post, input);

                OptionForm recaptcha = OptionForm.create(fc, "recaptcha", "reCAPTCHA");
                recaptcha.text("recaptcha_sitekey", "sitekey");
                recaptcha.text("recaptcha_secretkey", "secretkey");
                recaptcha.checkbox("recaptcha_invisible").label();
                recaptcha.handle(post, input);

                OptionForm updates = OptionForm.create(fc, "updates", "更新与插件仓库");
                updates.text("update_source", "主站更新检测 URL")
                        .placeholder(settings.getUpdateSource());
                updates.text("plugins_registry", "插件仓库 URL")
                        .placeholder(settings.getPlugins().getRegistry());
                updates.type("danger").addMessage(UPDATES_MESSAGE, "danger");
                updates.handle(post, input);
                updates.always(form -> {
                    Object updateSource = option.get("update_source");
                    form.with("update_source",
                            truthy(updateSource) ? updateSource : settings.getUpdateSource());
                    Object registry = option.get("plugins_registry");
                    form.with("plugins_registry",
                            truthy(registry) ? registry : settings.getPlugins().getRegistry());
                });

                Map<String, Object> forms = new LinkedHashMap<>();
                forms.put("general", general.render());
                forms.put("meta", meta.render());
                forms.put("recaptcha", recaptcha.render());
                forms.put("updates", updates.render());

                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans("general.options", Map.of(), locale));
                vars.put("forms", forms);
                render(ctx, "admin/options", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code updates} 表单的 before 校验。 */
    private Map<String, List<String>> validateUpdates(Map<String, Object> input) {
        Validator validator = new Validator(input, translator, translator.getLocale(), option,
                null);
        validator.addRule("update_source", "nullable");
        validator.addRule("update_source", "url:http,https");
        validator.addRule("update_source", "max:2048");
        validator.addRule("plugins_registry", "nullable");
        validator.addRule("plugins_registry", "string");
        validator.addRule("plugins_registry", "max:2048");
        validator.addRule("plugins_registry", new RegistryUrlRule());
        if (validator.passes()) {
            return Map.of();
        }

        return validator.errors();
    }

    // ============================================================ resource

    /** {@code resource()}。 */
    public Handler<RoutingContext> resource() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                boolean post = isPost(ctx);
                Map<String, Object> input = postMap(ctx);
                OptionForm.Context fc = formContext(ctx);

                OptionForm resources = OptionForm.create(fc, "resources");
                resources.type("primary").hint();
                resources.checkbox("force_ssl").label().hint();
                resources.checkbox("auto_detect_asset_url").label().description();
                resources.text("cache_expire_time").hint();
                resources.text("cdn_address").hint().description();
                resources.after(form -> {
                    String cdn = input.get("cdn_address") == null ? ""
                            : String.valueOf(input.get("cdn_address"));
                    if (cdn.endsWith("/")) {
                        cdn = cdn.substring(0, cdn.length() - 1);
                    }
                    option.set("cdn_address", cdn);
                });
                resources.handle(post, input);

                OptionForm cache = OptionForm.create(fc, "cache");
                cache.type("warning");
                cache.checkbox("enable_avatar_cache").label();
                cache.checkbox("enable_preview_cache").label();
                cache.addButton(translator.trans("options.cache.clear", Map.of(), locale),
                        "warning", "a", "", "?clear-cache");
                cache.addMessage(translator.trans("options.cache.driver",
                        Map.of("driver", settings.getCache().getDriver()), locale), "info");

                if (ctx.request().params().contains("clear-cache")) {
                    imageCache.clear();
                    cache.addMessage(translator.trans("options.cache.cleared", Map.of(), locale),
                            "success");
                }
                cache.handle(post, input);

                Map<String, Object> forms = new LinkedHashMap<>();
                forms.put("resources", resources.render());
                forms.put("cache", cache.render());

                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans("general.res-options", Map.of(), locale));
                vars.put("forms", forms);
                render(ctx, "admin/resource", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ helpers

    private OptionForm.Context formContext(RoutingContext ctx) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        String token = session == null ? "" : String.valueOf(session.token());

        return new OptionForm.Context(option, translator, translator.getLocale(),
                "<input type=\"hidden\" name=\"_token\" value=\"" + token + "\">");
    }

    private static boolean isPost(RoutingContext ctx) {
        return "POST".equalsIgnoreCase(ctx.request().method().name());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> postMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object bag = ctx.get(RequestInput.INPUT_KEY);
        if (bag instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private Map<String, Object> pageVars(RoutingContext ctx, String locale, String title)
            throws Exception {
        String host = hostOf(ctx);
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = com.neoskin.web.middleware.Authenticate.currentUser(ctx);
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"),
                RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = PageView.variables(option, translator, javaScript, filter,
                user, locale, urlRoot, ctx.request().path(), debug, "admin", vc);
        vars.put("pageTitle", title);
        vars.put("errors", new AuthController.ErrorsView(
                com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                        session == null ? null : session.get("errors"))));

        return vars;
    }

    private void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = ViewEngine.render(template, java.util.Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    /** 校验失败：HTML 表单 → 闪存 errors + 302 回到来源页（Laravel ValidationException）。 */
    private void redirectWithErrors(RoutingContext ctx, Map<String, List<String>> errors) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            session.flash("errors", AuthController.toViewErrorBag(errors));
            Map<String, Object> input = new LinkedHashMap<>();
            Object bag = ctx.get(RequestInput.INPUT_KEY);
            if (bag instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    String name = String.valueOf(key);
                    if (!"password".equals(name)) {
                        input.put(name, value);
                    }
                });
            }
            session.flash("_old_input", input);
        }

        String previous = ctx.request().getHeader("Referer");
        if (previous == null || previous.isBlank()) {
            previous = "http://" + hostOf(ctx) + "/admin/options";
        }
        ctx.response().setStatusCode(302)
                .putHeader("Location", previous)
                .putHeader("Cache-Control", "no-cache, private")
                .end();
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String s = String.valueOf(value);
        return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s);
    }

    /** {@code plugins_registry} 的闭包校验：把 {@code {lang}} 换成 en 后须是 http(s) URL。 */
    private static final class RegistryUrlRule implements com.neoskin.web.validation.Rule {
        @Override
        public boolean passes(String attribute, Object value,
                com.neoskin.web.validation.Validator validator) {
            String raw = value == null ? "" : String.valueOf(value);
            if (raw.isEmpty()) {
                return true;
            }

            return Validator.isUrlWithScheme(raw.replace("{lang}", "en"));
        }

        @Override
        public String message() {
            return "请输入有效的 HTTP 或 HTTPS 插件仓库地址。";
        }
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, UserRepository users, boolean debug,
            NeoSkinSettings settings, AppPaths paths, ImageCache imageCache) {
        OptionsController c = new OptionsController(option, translator, javaScript, filter,
                users, debug, settings, paths, imageCache);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\OptionsController@customize" -> c.customize();
            case "App\\Http\\Controllers\\OptionsController@score" -> c.score();
            case "App\\Http\\Controllers\\OptionsController@options" -> c.options();
            case "App\\Http\\Controllers\\OptionsController@resource" -> c.resource();
            default -> null;
        };
    }
}
