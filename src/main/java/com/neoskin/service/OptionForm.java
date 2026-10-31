package com.neoskin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.web.i18n.Translator;

/**
 * {@code App\Services\OptionForm} 的移植：把后台设置表单构建成与源 Twig
 * （{@code resources/views/forms/*.twig}）同构的 HTML 字符串。
 *
 * <p>为什么直接拼 HTML：源模板用 {@code |raw} 注入表单片段，前端依赖这些
 * class / input 名称（例如 {@code hidden[name=option]}、{@code submit_<id>}）。
 * Java 侧只要输出同样的结构，样式与提交协议就保持一致。</p>
 *
 * <p>{@code AUTO_DETECT} 通过「不传 name」的重载表达；名称/提示/描述/标签
 * 缺省从 {@code options.<form>.<id>.*} 语言键读取。</p>
 */
public final class OptionForm {

    /** 表单渲染上下文（选项服务 + 翻译器 + CSRF）。 */
    public record Context(Option option, Translator translator, String locale,
            String csrfField) {
    }

    /** 表单生命周期回调。 */
    public interface Action {
        void run(OptionForm form) throws Exception;
    }

    private final Context context;
    private final String id;
    private final String title;
    private String hint;
    private String type = "primary";
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Message> messages = new ArrayList<>();
    private final List<Message> alerts = new ArrayList<>();

    private Action before;
    private Action after;
    private Action always;
    private boolean renderWithoutTable;
    private boolean renderInputTagsOnly;
    private boolean renderWithoutSubmitButton;

    private OptionForm(Context context, String id, String title) {
        this.context = context;
        this.id = id;
        this.title = title == null ? trans("options." + id + ".title", null) : title;
    }

    public static OptionForm create(Context context, String id) {
        return new OptionForm(context, id, null);
    }

    public static OptionForm create(Context context, String id, String title) {
        return new OptionForm(context, id, title);
    }

    // ---------------------------------------------------------------- 构建

    public OptionForm type(String type) {
        this.type = type;
        return this;
    }

    public OptionForm hint(String hintContent) {
        this.hint = hintContent == null
                ? trans("options." + id + ".hint", null) : hintContent;
        return this;
    }

    public OptionForm hint() {
        return hint(null);
    }

    public TextItem text(String itemId) {
        return add(new TextItem(this, itemId, null));
    }

    public TextItem text(String itemId, String name) {
        return add(new TextItem(this, itemId, name));
    }

    public CheckboxItem checkbox(String itemId) {
        return add(new CheckboxItem(this, itemId, null));
    }

    public CheckboxItem checkbox(String itemId, String name) {
        return add(new CheckboxItem(this, itemId, name));
    }

    public TextareaItem textarea(String itemId) {
        return add(new TextareaItem(this, itemId, null));
    }

    public TextareaItem textarea(String itemId, String name) {
        return add(new TextareaItem(this, itemId, name));
    }

    public SelectItem select(String itemId) {
        return add(new SelectItem(this, itemId, null));
    }

    public SelectItem select(String itemId, String name) {
        return add(new SelectItem(this, itemId, name));
    }

    public GroupItem group(String itemId) {
        return add(new GroupItem(this, itemId, null));
    }

    public GroupItem group(String itemId, String name) {
        return add(new GroupItem(this, itemId, name));
    }

    private <T extends Item> T add(T item) {
        items.add(item);
        return item;
    }

    public OptionForm addButton(String text, String style, String buttonType, String name,
            String href) {
        List<String> css = new ArrayList<>(List.of("btn", "btn-" + style));
        buttons.add(new Button(text, css, buttonType, name, href == null ? "" : href));
        return this;
    }

    public OptionForm addMessage(String message, String style) {
        messages.add(new Message(message, style));
        return this;
    }

    public OptionForm addAlert(String message, String style) {
        alerts.add(new Message(message, style));
        return this;
    }

    /** {@code addMessage()} 的 AUTO_DETECT 形式：{@code options.<id>.message}。 */
    public OptionForm addMessageAuto() {
        return addMessage(trans("options." + id + ".message", null), "info");
    }

    public OptionForm with(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public OptionForm before(Action action) {
        this.before = action;
        return this;
    }

    public OptionForm after(Action action) {
        this.after = action;
        return this;
    }

    public OptionForm always(Action action) {
        this.always = action;
        return this;
    }

    public OptionForm renderWithoutTable() {
        this.renderWithoutTable = true;
        return this;
    }

    public OptionForm renderInputTagsOnly() {
        this.renderInputTagsOnly = true;
        return this;
    }

    public OptionForm renderWithoutSubmitButton() {
        this.renderWithoutSubmitButton = true;
        return this;
    }

    // ---------------------------------------------------------------- 处理

    /**
     * {@code handle()}：{@code POST option=<id>} 时写回变更过的选项。
     *
     * @param isPost 是否 POST
     * @param post   请求体（{@code input} 包）
     * @param callback {@code handle()} 的可选回调（最先执行）
     */
    public OptionForm handle(boolean isPost, Map<String, Object> post, Action callback)
            throws Exception {
        if (!isPost || !id.equals(string(post.get("option")))) {
            return this;
        }
        if (callback != null) {
            callback.run(this);
        }
        if (before != null) {
            before.run(this);
        }

        List<Item> queue = new ArrayList<>();
        for (Item item : items) {
            if (item instanceof GroupItem group) {
                for (GroupItem.Inner inner : group.inners) {
                    if ("text".equals(inner.type())) {
                        queue.add(new TextItem(this, inner.id(), null));
                    }
                }
                continue;
            }
            queue.add(item);
        }

        for (Item item : queue) {
            if (item instanceof CheckboxItem && !post.containsKey(item.id)) {
                post.put(item.id, false);
            }
            Object data = post.get(item.id);
            Object raw = context.option().get(item.id, null, true);
            if (!looseEqual(data, raw)) {
                Object formatted = item.format == null ? data : item.format.apply(data);
                context.option().set(item.id, formatted);
            }
        }

        if (after != null) {
            after.run(this);
        }
        addAlert(trans("options.option-saved", null), "success");

        return this;
    }

    public OptionForm handle(boolean isPost, Map<String, Object> post) throws Exception {
        return handle(isPost, post, null);
    }

    /** PHP 的 {@code !=} 近似（只影响「是否需要再写一遍」）。 */
    static boolean looseEqual(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            Object other = a == null ? b : a;
            if (other instanceof Boolean bool) {
                return !bool;
            }
            String s = String.valueOf(other);
            return s.isEmpty() || "0".equals(s) || "false".equalsIgnoreCase(s)
                    || "(false)".equalsIgnoreCase(s);
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        String s = String.valueOf(value);
        return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s)
                && !"(false)".equalsIgnoreCase(s);
    }

    // ---------------------------------------------------------------- 渲染

    public String render() {
        try {
            if (always != null) {
                always.run(this);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (!renderWithoutSubmitButton) {
            addButton(trans("general.submit", null), "primary", "submit", "submit_" + id, "");
        }
        assignValues();

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"card card-").append(type).append("\">\n");
        html.append("  <div class=\"card-header\">\n");
        html.append("    <h3 class=\"card-title\">").append(title).append(' ')
                .append(hint == null ? "" : hint).append("</h3>\n");
        html.append("  </div>\n");
        html.append("  <form method=\"post\">\n");
        html.append("    ").append(context.csrfField()).append('\n');
        html.append("    <input type=\"hidden\" name=\"option\" value=\"").append(escape(id))
                .append("\">\n");
        html.append("    <div class=\"card-body\">\n");

        for (Message message : messages) {
            html.append("      <div class=\"callout callout-").append(message.type)
                    .append("\">\n        ").append(message.content)
                    .append("\n      </div>\n");
        }
        for (Message alert : alerts) {
            html.append("      <div class=\"alert alert-").append(alert.type).append("\">\n");
            html.append("        ").append(iconFor(alert.type)).append('\n');
            html.append("        <span>").append(alert.content).append("</span>\n");
            html.append("      </div>\n");
        }

        if (renderWithoutTable) {
            for (Item item : items) {
                html.append(item.render());
                if (item.description != null) {
                    html.append("        <p class=\"description\">")
                            .append(item.description).append("</p>\n");
                }
            }
        } else {
            html.append("      <div class=\"container-fluid\">\n");
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                String rowClass = i == 0 ? "mb-3"
                        : (i == items.size() - 1 ? "mt-3" : "my-3");
                html.append("        <div class=\"row ").append(rowClass).append("\">\n");
                if (!renderInputTagsOnly) {
                    html.append("          <div class=\"col-sm-4\">\n            ")
                            .append(item.displayName()).append(' ')
                            .append(item.hint == null ? "" : item.hint).append('\n');
                    html.append("          </div>\n");
                }
                html.append("          <div class=\"col-sm-")
                        .append(renderInputTagsOnly ? "12" : "8").append("\">\n");
                html.append(item.render());
                if (item.description != null) {
                    html.append("            <p class=\"description\">")
                            .append(item.description).append("</p>\n");
                }
                html.append("          </div>\n");
                html.append("        </div>\n");
            }
            html.append("      </div>\n");
        }

        html.append("    </div>\n");
        html.append("    <div class=\"card-footer\">\n");
        for (Button button : buttons) {
            if (button.href != null && !button.href.isEmpty()) {
                html.append("      <a href=\"").append(escape(button.href)).append("\" class=\"")
                        .append(String.join(" ", button.css)).append("\">\n        ")
                        .append(button.text).append("\n      </a>\n");
            } else {
                html.append("      <button type=\"").append(button.type).append("\" name=\"")
                        .append(escape(button.name)).append("\" class=\"")
                        .append(String.join(" ", button.css)).append("\">\n        ")
                        .append(button.text).append("\n      </button>\n");
            }
        }
        html.append("    </div>\n");
        html.append("  </form>\n");
        html.append("</div>");

        return html.toString();
    }

    private void assignValues() {
        for (Item item : items) {
            if (item instanceof GroupItem group) {
                for (GroupItem.Inner inner : group.inners) {
                    if (inner.id() != null && inner.value() == null) {
                        inner.setValue(getValueById(inner.id()));
                    }
                }
                continue;
            }
            if (item.value == null) {
                item.value = getValueById(item.id);
            }
        }
    }

    private Object getValueById(String optionId) {
        if (values.containsKey(optionId)) {
            return values.get(optionId);
        }
        return context.option().getLocalized(optionId);
    }

    private static String iconFor(String style) {
        return switch (style) {
            case "success" -> "<i class=\"fas fa-check icon\"></i>";
            case "info" -> "<i class=\"fas fa-info icon\"></i>";
            case "warning" -> "<i class=\"fas fa-exclamation-triangle icon\"></i>";
            case "danger" -> "<i class=\"fas fa-times-circle icon\"></i>";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- 翻译

    String trans(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        Translator translator = context.translator();
        if (translator.get(key, Map.of(), context.locale()) instanceof String s
                && !s.equals(key)) {
            return s;
        }
        return fallback == null ? key : fallback;
    }

    /** {@code options.<form>.<id>.<suffix>}，缺失返回 null。 */
    String optionText(String itemId, String suffix) {
        String key = "options." + id + "." + itemId + "." + suffix;
        Object value = context.translator().get(key, Map.of(), context.locale());
        if (value instanceof String s && !s.equals(key)) {
            return s;
        }
        return null;
    }

    /** 自动名称：{@code options.<form>.<id>}（字符串或 {title}）。 */
    String autoName(String itemId) {
        String key = "options." + id + "." + itemId;
        Object node = context.translator().get(key, Map.of(), context.locale());
        if (node instanceof Map<?, ?> map && map.get("title") != null) {
            return String.valueOf(map.get("title"));
        }
        if (node instanceof String s && !s.equals(key)) {
            return s;
        }
        return key;
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#039;");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ================================================================ 条目

    private record Button(String text, List<String> css, String type, String name, String href) {
    }

    private record Message(String content, String type) {
    }

    public abstract static class Item {
        final OptionForm form;
        final String id;
        final String explicitName;
        String hint;
        String description;
        Object value;
        String disabled;
        java.util.function.Function<Object, Object> format;

        Item(OptionForm form, String id, String explicitName) {
            this.form = form;
            this.id = id;
            this.explicitName = explicitName;
        }

        public abstract String render();

        String displayName() {
            return explicitName != null ? explicitName : form.autoName(id);
        }

        public Item hint(String content) {
            this.hint = content == null ? form.optionText(id, "hint") : content;
            return this;
        }

        public Item hint() {
            return hint(null);
        }

        public Item description(String text) {
            String content = text == null ? form.optionText(id, "description") : text;
            this.description = content == null ? null
                    : "<i class=\"fas fa-question-circle\" title=\"" + escape(content)
                            + "\" data-toggle=\"tooltip\" data-placement=\"top\"></i>";
            return this;
        }

        public Item description() {
            return description(null);
        }

        public Item value(Object v) {
            this.value = v;
            return this;
        }

        public Item disabled() {
            this.disabled = "disabled";
            return this;
        }

        public Item format(java.util.function.Function<Object, Object> fn) {
            this.format = fn;
            return this;
        }
    }

    public static final class TextItem extends Item {
        private String placeholder = "";

        TextItem(OptionForm form, String id, String explicitName) {
            super(form, id, explicitName);
        }

        public TextItem placeholder(String text) {
            this.placeholder = text == null ? nullOrDefault() : text;
            return this;
        }

        public TextItem placeholder() {
            return placeholder(null);
        }

        private String nullOrDefault() {
            String p = form.optionText(id, "placeholder");
            return p == null ? "" : p;
        }

        @Override
        public String render() {
            return "<input\n              type=\"text\"\n              class=\"form-control\"\n"
                    + "              name=\"" + escape(id) + "\"\n              "
                    + (disabled != null ? "disabled=disabled" : "") + "\n"
                    + "              value=\"" + escape(value == null ? "" : String.valueOf(value))
                    + "\"\n              placeholder=\"" + escape(placeholder) + "\"\n            >\n";
        }
    }

    public static final class CheckboxItem extends Item {
        private String label;

        CheckboxItem(OptionForm form, String id, String explicitName) {
            super(form, id, explicitName);
            this.label = null;
        }

        public CheckboxItem label(String text) {
            this.label = text == null ? form.optionText(id, "label") : text;
            return this;
        }

        public CheckboxItem label() {
            return label(null);
        }

        @Override
        public String render() {
            boolean checked = value instanceof Boolean b ? b
                    : value != null && !"0".equals(String.valueOf(value))
                            && !"false".equalsIgnoreCase(String.valueOf(value));
            return "<label for=\"" + escape(id) + "\">\n"
                    + "              <input\n                type=\"checkbox\"\n                "
                    + (checked ? "checked=\"true\"" : "") + "\n                id=\""
                    + escape(id) + "\"\n                name=\"" + escape(id) + "\"\n"
                    + "                " + (disabled != null ? "disabled=disabled" : "") + "\n"
                    + "                value=\"true\"\n              > "
                    + escape(label == null ? "" : label) + "\n            </label>\n";
        }
    }

    public static final class TextareaItem extends Item {
        private int rows = 3;

        TextareaItem(OptionForm form, String id, String explicitName) {
            super(form, id, explicitName);
        }

        public TextareaItem rows(int rows) {
            this.rows = rows;
            return this;
        }

        @Override
        public String render() {
            return "<textarea\n              class=\"form-control\"\n              rows=\"" + rows
                    + "\"\n              name=\"" + escape(id) + "\"\n              "
                    + (disabled != null ? "disabled=disabled" : "") + "\n            >"
                    + (value == null ? "" : escape(String.valueOf(value)))
                    + "</textarea>\n";
        }
    }

    public static final class SelectItem extends Item {
        private final List<String[]> options = new ArrayList<>();

        SelectItem(OptionForm form, String id, String explicitName) {
            super(form, id, explicitName);
        }

        public SelectItem option(String optionValue, String name) {
            options.add(new String[] {optionValue, name});
            return this;
        }

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("<select class=\"form-control\" name=\"").append(escape(id)).append("\" ")
                    .append(disabled != null ? "disabled=disabled" : "").append(">\n");
            for (String[] option : options) {
                boolean selected = value != null
                        && String.valueOf(value).equals(option[0]);
                sb.append("              <option\n                value=\"")
                        .append(escape(option[0])).append("\"\n                ")
                        .append(selected ? "selected=selected" : "").append("\n              >\n")
                        .append("                ").append(escape(option[1]))
                        .append("\n              </option>\n");
            }
            sb.append("            </select>\n");

            return sb.toString();
        }
    }

    public static final class GroupItem extends Item {
        private final List<Inner> inners = new ArrayList<>();

        GroupItem(OptionForm form, String id, String explicitName) {
            super(form, id, explicitName);
        }

        public GroupItem text(String innerId) {
            inners.add(new Inner("text", innerId, null));
            return this;
        }

        public GroupItem text(String innerId, Object innerValue) {
            inners.add(new Inner("text", innerId, innerValue));
            return this;
        }

        public GroupItem addon(String text) {
            inners.add(new Inner("addon", null, text));
            return this;
        }

        public GroupItem addon() {
            String value = form.optionText(id, "addon");
            inners.add(new Inner("addon", null, value == null ? "" : value));
            return this;
        }

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"input-group\">\n");
            for (Inner inner : inners) {
                if ("addon".equals(inner.type())) {
                    sb.append("              <div class=\"input-group-append\">\n")
                            .append("                <span class=\"input-group-text\">")
                            .append(escape(String.valueOf(inner.value())))
                            .append("</span>\n              </div>\n");
                } else {
                    TextItem text = new TextItem(form, inner.id(), null);
                    text.value = inner.value();
                    sb.append(text.render());
                }
            }
            sb.append("            </div>\n");

            return sb.toString();
        }

        /** 组内条目（handle 时需要单独写回 text）。 */
        public static final class Inner {
            private final String type;
            private final String id;
            private Object value;

            Inner(String type, String id, Object value) {
                this.type = type;
                this.id = id;
                this.value = value;
            }

            public String type() {
                return type;
            }

            public String id() {
                return id;
            }

            public Object value() {
                return value;
            }

            void setValue(Object v) {
                this.value = v;
            }
        }
    }
}
