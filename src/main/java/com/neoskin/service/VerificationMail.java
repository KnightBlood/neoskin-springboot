package com.neoskin.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.SignedUrl;
import com.neoskin.web.i18n.Translator;

/**
 * 「验证邮箱」邮件（源 {@code App\Mail\EmailVerification} +
 * {@code resources/views/mails/email-verification.blade.php} +
 * {@code App\Listeners\SendEmailVerification}）。
 *
 * <p>签名链接是<b>非临时</b>签名（源 {@code URL::signedRoute(..., null, false)}
 * 再 {@code url($url)} 补成绝对地址），因此 {@link SignedUrl#hasValidSignature}
 * 必须允许没有 {@code expires} 的签名。</p>
 */
public final class VerificationMail {

    private VerificationMail() {
    }

    /** 绝对地址：{@code url(URL::signedRoute('auth.verify', ['user' => $uid], null, false))}。 */
    public static String url(RouteUrlGenerator routes, String appKey, String host, int uid) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", uid);
        String path = SignedUrl.signedRoute("auth.verify", params, false, routes, appKey);

        return "http://" + (host == null || host.isEmpty() ? "localhost" : host) + path;
    }

    /** 邮件主题（源 {@code user.verification.mail.title}）。 */
    public static String subject(Option option, Translator translator, String locale) {
        String sitename = String.valueOf(option.getLocalized("site_name", "Blessing Skin"));

        return translator.trans("user.verification.mail.title",
                Map.of("sitename", sitename), locale);
    }

    /** 正文（源 {@code mails/email-verification.blade.php} 的三段）。 */
    public static String body(Option option, Translator translator, String locale, String url) {
        String sitename = String.valueOf(option.getLocalized("site_name", "Blessing Skin"));
        String message = translator.trans("user.verification.mail.message",
                Map.of("sitename", sitename), locale);
        String link = translator.trans("user.verification.mail.reset",
                Map.of("url", url), locale);
        String ignore = translator.trans("user.verification.mail.ignore", Map.of(), locale);

        return "<p>" + message + "</p><p>" + link + "</p><p>" + ignore + "</p>";
    }
}
