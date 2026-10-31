package com.neoskin.web.validation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.web.i18n.Translator;

public final class CaptchaRule implements Rule {
    private final Option option;
    private final SessionStore session;
    private final Translator translator;
    private final String locale;

    public CaptchaRule(Option option, SessionStore session, Translator translator, String locale) {
        this.option = option;
        this.session = session;
        this.translator = translator;
        this.locale = locale == null || locale.isEmpty() ? "en" : locale;
    }

    @Override
    public boolean passes(String attribute, Object value, Validator validator) {
        String v = value == null ? "" : String.valueOf(value);
        String secret = option == null ? "" : String.valueOf(option.get("recaptcha_secretkey", ""));
        if (secret != null && !secret.isEmpty() && !"null".equals(secret)) {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                String body = "secret=" + java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8)
                        + "&response=" + java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://www.recaptcha.net/recaptcha/api/siteverify"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                String resp = res.body();
                return resp != null && resp.contains("\"success\"") && resp.contains("true");
            } catch (Exception e) {
                return false;
            }
        }
        // session captcha
        Object pulled = null;
        if (session != null) {
            pulled = session.get("captcha");
            // pull semantics: remove after read
            session.forget("captcha");
        }
        String stored = pulled == null ? null : String.valueOf(pulled);
        return testPhrase(stored, v);
    }

    private static boolean testPhrase(String stored, String input) {
        if (stored == null) return false;
        return niceize(input).equals(niceize(stored));
    }

    private static String niceize(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replace('0', 'o').replace('1', 'l');
    }

    @Override
    public String message() {
        String secret = option == null ? "" : String.valueOf(option.get("recaptcha_secretkey", ""));
        String key;
        if (secret != null && !secret.isEmpty() && !"null".equals(secret)) {
            key = "validation.recaptcha";
        } else {
            key = "validation.captcha";
        }
        if (translator == null) return key;
        String val = translator.trans(key, java.util.Map.of(), locale);
        return val.equals(key) ? "Incorrect captcha." : val;
    }

    @Override
    public String name() {
        return null;
    }
}
