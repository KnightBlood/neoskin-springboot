package com.neoskin.web.validation;

import com.neoskin.service.Option;
import com.neoskin.web.i18n.Translator;

public final class PlayerNameRule implements Rule {
    private final Option option;
    private final Translator translator;
    private final String locale;

    public PlayerNameRule(Option option, Translator translator, String locale) {
        this.option = option;
        this.translator = translator;
        this.locale = locale == null || locale.isEmpty() ? "en" : locale;
    }

    @Override
    public boolean passes(String attribute, Object value, Validator validator) {
        if (value == null) return false;
        String v = String.valueOf(value);
        String rule = option == null ? "official" : String.valueOf(option.get("player_name_rule", "official"));
        return switch (rule) {
            case "official" -> v.matches("^[A-Za-z0-9_]+$");
            case "cjk" -> v.matches("^[A-Za-z0-9_§\u4e00-\u9fff]+$");
            case "utf8" -> {
                try {
                    v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    yield !v.matches(".*\\s.*");
                } catch (Exception e) {
                    yield false;
                }
            }
            case "custom" -> {
                String regexp = option == null ? "" : String.valueOf(option.get("custom_player_name_regexp", ""));
                if (regexp.isEmpty()) regexp = ".*";
                try {
                    yield v.matches(regexp);
                } catch (Exception e) {
                    yield false;
                }
            }
            default -> v.matches("^[A-Za-z0-9_]+$");
        };
    }

    @Override
    public String message() {
        String rule = option == null ? "official" : String.valueOf(option.get("player_name_rule", "official"));
        String key = "user.player.player-name-rule." + rule;
        if (translator == null) return key;
        String val = translator.trans(key, java.util.Map.of(), locale);
        return val.equals(key) ? "The player name format is invalid." : val;
    }

    @Override
    public String name() {
        return null;
    }
}
