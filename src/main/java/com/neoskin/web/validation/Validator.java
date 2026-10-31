package com.neoskin.web.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoskin.service.Option;
import com.neoskin.support.PhpArr;
import com.neoskin.support.PhpString;
import com.neoskin.support.Str;
import com.neoskin.web.i18n.Translator;

/**
 * Laravel {@code Illuminate\Validation\Validator} 的移植（subset）。
 *
 * <p>支持 auth 与后续控制器所需的规则：{@code required / email / unique / min / max /
 * present / filled / nullable / string / integer / numeric / in / confirmed /
 * url / json / regex / array / array_keys / boolean / exists / required_if}，
 * 以及 {@link Rule} 自定义规则（{@code PlayerName} / {@code Captcha}）。
 * 语义按 Laravel 13 的 {@code validateAttribute} 流程实现。</p>
 *
 * <h2>通配符规则（{@code blocks.*} / {@code blocks.*.type}）</h2>
 * <p>Laravel 在 {@code setRules()} 时就把通配符键展开成具体键
 * （{@code ValidationRuleParser::explodeWildcardRules} + {@code ValidationData::
 * initializeAndGatherData}），所以错误键名是 {@code blocks.0.type} 这种形态，
 * 而不是模板键 {@code blocks.*.type}。本类在 {@link #passes()} 入口做同一件事，
 * 并记下 {@code implicitAttributes}（模板键 → 具体键）—— 它决定
 * {@code getDisplayableAttribute()} 是否回落到「原样输出属性名」。</p>
 */
public final class Validator {

    /** implicit 规则集合（失败即 shouldStopValidating）。 */
    private static final Set<String> IMPLICIT = Set.of(
            "required", "required_if", "required_unless", "required_with",
            "required_with_all", "required_without", "required_without_all",
            "present", "filled", "accepted", "accepted_if", "declined",
            "declined_if", "missing", "missing_if", "missing_unless",
            "missing_with", "missing_with_all", "present_if", "present_unless",
            "present_with", "present_with_all", "required_array_keys",
            "required_if_accepted", "required_if_declined");

    /** size 规则。 */
    private static final Set<String> SIZE_RULES = Set.of("min", "max", "between", "size");

    /** 数值规则（影响 getAttributeType / getSize）。 */
    private static final Set<String> NUMERIC_RULES = Set.of(
            "numeric", "integer", "int", "float", "double", "decimal");

    private final Map<String, Object> data;
    private final Translator translator;
    private final String locale;
    private final Option option;
    private final PresenceVerifier presenceVerifier;

    private final Map<String, List<Object>> rulesRaw = new LinkedHashMap<>();
    private final Map<String, List<String>> messages = new LinkedHashMap<>();
    private final Map<String, List<String>> failedRules = new LinkedHashMap<>();

    /** 展开后的规则表（模板键 → 具体键之后）；{@code null} 表示还没展开。 */
    private Map<String, List<Object>> rules;

    /** {@code $implicitAttributes}：模板键（{@code blocks.*}）→ 它展开出的具体键。 */
    private final Map<String, List<String>> implicitAttributes = new LinkedHashMap<>();

    private String currentRule;

    public Validator(Map<String, Object> data, Translator translator, String locale,
            Option option, PresenceVerifier presenceVerifier) {
        this.data = data == null ? Map.of() : data;
        this.translator = translator;
        this.locale = locale == null || locale.isEmpty() ? "en" : locale;
        this.option = option;
        this.presenceVerifier = presenceVerifier;
    }

    /** 添加一条规则（字符串或 Rule）。 */
    public void addRule(String attribute, Object rule) {
        rulesRaw.computeIfAbsent(attribute, k -> new ArrayList<>()).add(rule);
    }

    /** 执行校验。 */
    public boolean passes() {
        if (rules == null) {
            rules = expandRules();
        }
        for (Map.Entry<String, List<Object>> e : rules.entrySet()) {
            String attribute = e.getKey();
            for (Object rule : e.getValue()) {
                validateAttribute(attribute, rule);
                if (shouldStopValidating(attribute)) {
                    break;
                }
            }
        }
        return messages.isEmpty();
    }

    /** 把含 {@code *} 的模板键展开成具体键。 */
    private Map<String, List<Object>> expandRules() {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> e : rulesRaw.entrySet()) {
            if (!e.getKey().contains("*")) {
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        for (Map.Entry<String, List<Object>> e : rulesRaw.entrySet()) {
            String attr = e.getKey();
            if (!attr.contains("*")) continue;
            for (String concrete : concreteKeys(attr)) {
                out.computeIfAbsent(concrete, k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        return out;
    }

    private List<String> concreteKeys(String pattern) {
        List<String> result = new ArrayList<>();
        expandRec(pattern, "", result);
        return result;
    }

    public Map<String, List<String>> errors() {
        return messages;
    }

    private void expandRec(String pattern, String path, List<String> result) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            String full = path + pattern;
            full = full.replaceAll("^\\.", "");
            if (!full.isEmpty()) result.add(full);
            return;
        }
        String before = pattern.substring(0, star);
        String after = pattern.substring(star + 1);
        String prefixPath = (path + before).replaceAll("\\.$", "");
        Object base = prefixPath.isEmpty() ? data : PhpArr.get(data, prefixPath, null);

        java.util.Collection<?> keys;
        if (base instanceof Map<?, ?> m) {
            keys = m.keySet();
        } else if (base instanceof List<?> l) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) idx.add(i);
            keys = idx;
        } else {
            return;
        }
        for (Object k : keys) {
            String newPath = prefixPath.isEmpty() ? String.valueOf(k) : prefixPath + "." + k;
            expandRec(after, newPath, result);
        }
    }

    /** 返回校验通过的字段（attribute → value），仅含 rules 里声明的键。 */
    public Map<String, Object> validated() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String attr : rulesRaw.keySet()) {
            out.put(attr, getValue(attr));
        }
        return out;
    }

    // ============================================================ 核心流程

    private void validateAttribute(String attribute, Object rule) {
        currentRule = String.valueOf(rule);

        String ruleName;
        List<String> params;
        Rule custom = null;

        if (rule instanceof Rule r) {
            custom = r;
            ruleName = r.name();
            params = r.parameters();
        } else {
            String[] parsed = parseRule(String.valueOf(rule));
            ruleName = parsed[0];
            params = List.of(parsed).subList(1, parsed.length);
        }

        Object value = getValue(attribute);

        if (ruleName == null || ruleName.isEmpty()) {
            // 自定义规则约定 name() 返回 null（见 Rule 的 javadoc）。这类规则没有
            // 内置实现可查，但仍必须执行 passes()，否则会被静默跳过 —— 那会让
            // 「验证码错误」「角色名非法」这类校验彻底失效。
            if (custom != null
                    && isValidatable("__custom", attribute, value, custom)
                    && !custom.passes(attribute, value, this)) {
                addFailure(attribute, "__custom", params, custom.message());
            }
            return;
        }

        // 文件上传失败兜底（本工程不涉及文件，跳过）

        boolean validatable = isValidatable(ruleName, attribute, value, custom);

        if (custom != null) {
            if (validatable && !custom.passes(attribute, value, this)) {
                addFailure(attribute, ruleName, params, custom.message());
            }
            return;
        }

        String method = "validate" + capitalize(ruleName);

        if (validatable && !invokeBuiltin(method, attribute, value, params)) {
            addFailure(attribute, ruleName, params, null);
        }
    }

    private boolean invokeBuiltin(String method, String attribute, Object value, List<String> params) {
        return switch (method) {
            case "validateRequired" -> validateRequired(value);
            case "validatePresent" -> validatePresent(attribute, value);
            case "validateFilled" -> validateFilled(value);
            case "validateEmail" -> validateEmail(value);
            case "validateString" -> value == null || value instanceof String;
            case "validateInteger" -> isInteger(value);
            case "validateNumeric" -> isNumeric(value);
            case "validateMin" -> validateMin(attribute, value, params);
            case "validateMax" -> validateMax(attribute, value, params);
            case "validateUnique" -> validateUnique(attribute, value, params);
            case "validateIn" -> validateIn(value, params);
            case "validateRegex" -> validateRegex(value, params);
            case "validateUrl" -> validateUrl(value, params);
            case "validateJson" -> validateJson(value);
            case "validateConfirmed" -> validateConfirmed(attribute, value);
            case "validateNullable" -> true;
            case "validateArray" -> validateArray(value, params);
            case "validateBoolean" -> validateBoolean(value, params);
            case "validateExists" -> validateExists(attribute, value, params);
            case "validateRequiredIf" -> validateRequiredIf(attribute, value, params);
            default -> true;
        };
    }

    // ============================================================ 内置规则

    private boolean validateRequired(Object value) {
        if (value == null) return false;
        if (value instanceof String s && PhpString.trim(s).isEmpty()) return false;
        if (value instanceof java.util.Collection<?> c && c.isEmpty()) return false;
        if (value instanceof Map<?, ?> m && m.isEmpty()) return false;
        return true;
    }

    private boolean validatePresent(String attribute, Object value) {
        return data.containsKey(attribute) || PhpArr.has(data, attribute);
    }

    private boolean validateFilled(Object value) {
        return value == null || (value instanceof String s && PhpString.trim(s).isEmpty()) ? false : true;
    }

    private boolean validateEmail(Object value) {
        if (value == null) return false;
        if (!(value instanceof String s)) return false;
        return RfcEmailValidator.isValid(s);
    }

    private boolean isInteger(Object value) {
        if (value == null) return false;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d);
        }
        try {
            Integer.parseInt(String.valueOf(value).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isNumeric(Object value) {
        if (value == null) return false;
        if (value instanceof Number) return true;
        try {
            new BigDecimal(String.valueOf(value).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validateMin(String attribute, Object value, List<String> params) {
        if (params.isEmpty()) return true;
        String size = getSize(attribute, value);
        try {
            return new BigDecimal(size).compareTo(new BigDecimal(params.get(0).trim())) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validateMax(String attribute, Object value, List<String> params) {
        if (params.isEmpty()) return true;
        String size = getSize(attribute, value);
        try {
            return new BigDecimal(size).compareTo(new BigDecimal(params.get(0).trim())) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validateUnique(String attribute, Object value, List<String> params) {
        if (params.isEmpty()) return true;
        String table = params.get(0);
        String column = params.size() > 1 ? params.get(1) : attribute;
        Object excludeId = params.size() > 2 ? params.get(2) : null;
        String idColumn = params.size() > 3 ? params.get(3) : "id";
        if (presenceVerifier == null) return true;
        int count = presenceVerifier.getCount(table, column, value, excludeId, idColumn);
        return count == 0;
    }

    private boolean validateIn(Object value, List<String> params) {
        if (value == null) return false;
        String s = String.valueOf(value);
        return params.contains(s);
    }

    /**
     * {@code regex:<pattern>}：PHP {@code preg_match} 是「搜索」语义（find），不是整段匹配。
     */
    private boolean validateRegex(Object value, List<String> params) {
        if (value == null) {
            return false;
        }

        String pattern = params.isEmpty() ? "" : params.get(0);

        try {
            return java.util.regex.Pattern.compile(pattern)
                    .matcher(String.valueOf(value)).find();
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * {@code url} / {@code url:http,https}：Laravel 的 url 规则默认允许
     * http/https/ftp；带参数时只允许列出的协议。
     */
    private boolean validateUrl(Object value, List<String> params) {
        if (value == null) return false;
        String s = String.valueOf(value);
        try {
            java.net.URL url = new java.net.URL(s);
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase();
            if (params.isEmpty()) {
                return scheme.equals("http") || scheme.equals("https") || scheme.equals("ftp");
            }
            return params.stream().anyMatch(p -> p.equalsIgnoreCase(scheme))
                    && !url.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** http/https URL（授权更新源 / 插件仓库用）。 */
    public static boolean isUrlWithScheme(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            java.net.URL url = new java.net.URL(value);
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase();
            return (scheme.equals("http") || scheme.equals("https"))
                    && !url.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateJson(Object value) {
        if (value == null) return false;
        try {
            com.neoskin.support.PhpJson.decode(String.valueOf(value));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateConfirmed(String attribute, Object value) {
        Object other = getValue(attribute + "_confirmation");
        if (value == null && other == null) return true;
        if (value == null || other == null) return false;
        return String.valueOf(value).equals(String.valueOf(other));
    }

    private boolean validateArray(Object value, List<String> params) {
        if (value == null) return true;
        if (!(value instanceof java.util.List || value instanceof Map)) return false;
        if (params.isEmpty()) return true;
        if (!(value instanceof Map<?, ?> m)) return true;
        // keys subset
        for (Object key : m.keySet()) {
            if (!params.contains(String.valueOf(key))) {
                // array_diff_key: extra keys in value not in params => fail
                // but value keys must be subset of params
                return false;
            }
        }
        return true;
    }

    private boolean validateBoolean(Object value, List<String> params) {
        if (value == null) return false;
        if (value instanceof Boolean) return true;
        String s = String.valueOf(value);
        if (s.equals("true") || s.equals("false") || s.equals("1") || s.equals("0")
                || s.equals("on") || s.equals("off")) return true;
        // numeric 0/1
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == 0 || d == 1;
        }
        return false;
    }

    private boolean validateExists(String attribute, Object value, List<String> params) {
        if (value == null) return true;
        if (params.isEmpty()) return true;
        if (presenceVerifier == null) return true;
        String table = params.get(0);
        String column = params.size() > 1 ? params.get(1) : attribute;
        // handle uid column mapping? exists:users,uid
        if (value instanceof java.util.Collection<?> c) {
            for (Object v : c) {
                if (presenceVerifier.getCount(table, column, v, null, "id") == 0) return false;
            }
            return true;
        }
        return presenceVerifier.getCount(table, column, value, null, "id") > 0;
    }

    private boolean validateRequiredIf(String attribute, Object value, List<String> params) {
        if (params.size() < 2) return true;
        String other = params.get(0);
        String expected = params.get(1);
        Object otherValue = getValue(other);
        boolean condition;
        // boolean conversion
        if (otherValue instanceof Boolean b) {
            condition = b.toString().equalsIgnoreCase(expected)
                    || (expected.equalsIgnoreCase("true") && b)
                    || (expected.equalsIgnoreCase("false") && !b);
        } else {
            condition = String.valueOf(otherValue).equals(expected);
            // also handle boolean string values
            if (otherValue != null && (expected.equalsIgnoreCase("true") || expected.equalsIgnoreCase("false"))) {
                String ov = String.valueOf(otherValue);
                boolean ob = ov.equalsIgnoreCase("true") || ov.equals("1") || ov.equalsIgnoreCase("on");
                boolean eb = expected.equalsIgnoreCase("true");
                condition = (ob == eb);
            }
        }
        if (!condition) return true;
        // required
        if (value == null) return false;
        if (value instanceof String s && PhpString.trim(s).isEmpty()) return false;
        if (value instanceof java.util.Collection<?> c && c.isEmpty()) return false;
        if (value instanceof Map<?, ?> m && m.isEmpty()) return false;
        return true;
    }

    // ============================================================ isValidatable

    private boolean isValidatable(String ruleName, String attribute, Object value, Rule custom) {
        boolean implicit = (custom != null && custom.implicit())
                || IMPLICIT.contains(ruleName.toLowerCase());

        // presentOrRuleIsImplicit
        boolean presentOrImplicit;
        if (value instanceof String s && PhpString.trim(s).isEmpty()) {
            presentOrImplicit = implicit;
        } else {
            presentOrImplicit = validatePresent(attribute, value) || implicit;
        }

        // passesOptionalCheck: always true (no Sometimes support needed)
        // isNotNullIfMarkedAsNullable
        boolean notNullIfNullable = true;
        // hasNotFailedPreviousRuleIfPresenceRule: unique/exists skipped if already failed
        boolean notFailedPrev = true;
        if (ruleName.equalsIgnoreCase("unique") || ruleName.equalsIgnoreCase("exists")) {
            notFailedPrev = !messages.containsKey(attribute);
        }

        return presentOrImplicit && notNullIfNullable && notFailedPrev;
    }

    // ============================================================ shouldStopValidating

    private boolean shouldStopValidating(String attribute) {
        if (!messages.containsKey(attribute)) return false;
        // implicit rule failed → stop
        List<String> failed = failedRules.getOrDefault(attribute, List.of());
        for (String r : failed) {
            if (IMPLICIT.contains(r.toLowerCase())) return true;
        }
        if (failed.contains("uploaded")) return true;
        // Bail not tracked; treat any failure of implicit as stop (covered above)
        return false;
    }

    // ============================================================ helpers

    private void addFailure(String attribute, String ruleName, List<String> params, String customMessage) {
        String message;
        if (customMessage != null && !customMessage.isEmpty()) {
            message = customMessage;
        } else {
            message = resolveMessage(attribute, ruleName, params);
        }
        // makeReplacements
        message = makeReplacements(message, attribute, ruleName, params);
        messages.computeIfAbsent(attribute, k -> new ArrayList<>()).add(message);
        failedRules.computeIfAbsent(attribute, k -> new ArrayList<>()).add(ruleName);
    }

    private String resolveMessage(String attribute, String ruleName, List<String> params) {
        String lower = snake(ruleName);
        // size rules
        if (SIZE_RULES.contains(lower)) {
            String type = getAttributeType(attribute);
            String key = "validation." + lower + "." + type;
            String val = translator.trans(key, Map.of(), locale);
            if (!val.equals(key)) return val;
        }
        String key = "validation." + lower;
        String val = translator.trans(key, Map.of(), locale);
        if (!val.equals(key)) return val;
        return key;
    }

    private String getAttributeType(String attribute) {
        // has numeric rule?
        if (hasRule(attribute, NUMERIC_RULES)) return "numeric";
        // array? not needed
        Object value = getValue(attribute);
        if (value instanceof java.util.Collection || value instanceof Map) return "array";
        return "string";
    }

    private boolean hasRule(String attribute, Set<String> names) {
        List<Object> rs = rulesRaw.get(attribute);
        if (rs == null) return false;
        for (Object r : rs) {
            String n;
            if (r instanceof Rule rule) n = rule.name();
            else {
                String[] p = parseRule(String.valueOf(r));
                n = p[0];
            }
            if (n != null && names.contains(n.toLowerCase())) return true;
        }
        return false;
    }

    private String getSize(String attribute, Object value) {
        boolean hasNumeric = hasRule(attribute, NUMERIC_RULES);
        if (value instanceof Number n && hasNumeric) {
            return n.toString();
        }
        if (value instanceof java.util.Collection<?> c) {
            return String.valueOf(c.size());
        }
        if (value instanceof Map<?, ?> m) {
            return String.valueOf(m.size());
        }
        if (value == null) return "0";
        return String.valueOf(PhpString.mbSubstr(String.valueOf(value), 0, Integer.MAX_VALUE).codePointCount(0, String.valueOf(value).length()));
    }

    private String makeReplacements(String message, String attribute, String ruleName, List<String> params) {
        // attribute placeholder
        String displayable = getDisplayableAttribute(attribute);
        message = message.replace(":attribute", displayable)
                .replace(":ATTRIBUTE", displayable.toUpperCase(java.util.Locale.ROOT))
                .replace(":Attribute", capitalize(displayable));

        // rule-specific
        String lower = snake(ruleName);
        if (lower.equals("min") && !params.isEmpty()) {
            message = message.replace(":min", params.get(0));
        }
        if (lower.equals("max") && !params.isEmpty()) {
            message = message.replace(":max", params.get(0));
        }
        if (lower.equals("between") && params.size() >= 2) {
            message = message.replace(":min", params.get(0)).replace(":max", params.get(1));
        }
        if (lower.equals("size") && !params.isEmpty()) {
            message = message.replace(":size", params.get(0));
        }
        if (lower.equals("in") && !params.isEmpty()) {
            message = message.replace(":values", String.join(", ", params));
        }
        if (lower.equals("required_if") && params.size() >= 2) {
            String otherAttr = params.get(0);
            String otherDisplay = getDisplayableAttribute(otherAttr);
            String val = params.get(1);
            Object otherValue = getValue(otherAttr);
            // displayable value
            String displayVal = val;
            if (val.equalsIgnoreCase("uid")) displayVal = val;
            message = message.replace(":other", otherDisplay).replace(":value", displayVal);
        }
        return message;
    }

    private String getDisplayableAttribute(String attribute) {
        // validation.attributes mapping via translator
        Object attrs = translator.get("validation.attributes", Map.of(), locale);
        if (attrs instanceof Map<?, ?> m) {
            Object v = m.get(attribute);
            if (v != null) return String.valueOf(v);
        }
        return snake(attribute).replace("_", " ");
    }

    private Object getValue(String attribute) {
        return PhpArr.get(data, attribute, null);
    }

    private static String[] parseRule(String rule) {
        String[] parts = rule.split(":", 2);
        String name = parts[0].trim();
        List<String> params = new ArrayList<>();
        if (parts.length > 1) {
            for (String p : parts[1].split(",")) {
                params.add(p.trim());
            }
        }
        String[] out = new String[1 + params.size()];
        out[0] = name;
        for (int i = 0; i < params.size(); i++) out[i + 1] = params.get(i);
        return out;
    }

    private static String snake(String s) {
        // Str::snake: insert _ before capitals, lowercase
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public Translator getTranslator() {
        return translator;
    }

    public String getLocale() {
        return locale;
    }

    public Option getOption() {
        return option;
    }

    public com.neoskin.web.validation.Validator.PresenceVerifier getPresenceVerifier() {
        return presenceVerifier;
    }
    public interface PresenceVerifier {
        int getCount(String table, String column, Object value, Object excludeId, String idColumn);
    }

    /** ValidationException summary (Laravel's summarize). */
    public static String summarize(Map<String, List<String>> errors) {
        // flatten
        List<String> all = new ArrayList<>();
        for (List<String> v : errors.values()) all.addAll(v);
        if (all.isEmpty() || !(all.get(0) instanceof String)) {
            return "The given data was invalid.";
        }
        String message = all.get(0);
        int count = all.size() - 1;
        if (count > 0) {
            String plural = (count == 1) ? "error" : "errors";
            message += " (and " + count + " more " + plural + ")";
        }
        return message;
    }
}
