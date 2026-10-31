package com.neoskin.web.validation;

/**
 * 自定义校验规则的契约（对应 Laravel {@code Illuminate\Contracts\Validation\Rule}）。
 *
 * <p>{@code passes} 返回 true 表示通过；{@code message} 返回已翻译的失败文案。</p>
 */
public interface Rule {
    /** 是否通过。 */
    boolean passes(String attribute, Object value, Validator validator);

    /** 失败时的文案（已翻译）。 */
    String message();

    /** 规则名（自定义规则返回 null，走 message()）。 */
    default String name() {
        return null;
    }

    /** 参数。 */
    default java.util.List<String> parameters() {
        return java.util.List.of();
    }

    /** 是否为 implicit 规则（失败即 shouldStopValidating）。 */
    default boolean implicit() {
        return false;
    }
}
