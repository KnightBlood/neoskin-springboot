package com.neoskin.web.view;

import java.util.Locale;
import java.util.Map;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Thymeleaf 模板引擎的单点装配（standalone，无 Servlet）。
 *
 * <p>源项目用 TwigBridge 渲染 {@code resources/views/*.twig}；本工程按
 * {@code AGENTS.md §3} 锁定 Thymeleaf，模板落在
 * {@code src/main/resources/views/}（classpath {@code /views/}），
 * 后缀 {@code .html}。</p>
 *
 * <p>三个必须对齐的引擎选项（对应 {@code config/twigbridge.php}）：</p>
 * <ul>
 *   <li>{@code strict_variables = false} → Twig 把缺失变量当 {@code null}；
 *       Thymeleaf 的 {@code Context} 不接受 null 值，所以本工程用
 *       {@link NullValue} 哨兵占位，由方言还原成 null；</li>
 *   <li>{@code auto_reload = true} → {@code setCacheable(false)}，
 *       改模板立即生效；</li>
 *   <li>{@code charset = utf-8} → 显式声明。</li>
 * </ul>
 */
public final class ViewEngine {

    /** 模板前缀（classpath）。 */
    public static final String PREFIX = "/views/";

    /** 模板后缀。 */
    public static final String SUFFIX = ".html";

    private static volatile TemplateEngine engine;

    private ViewEngine() {
    }

    /** 进程内共享的引擎（懒加载，{@code TemplateEngine} 本身线程安全）。 */
    public static TemplateEngine engine() {
        if (engine == null) {
            synchronized (ViewEngine.class) {
                if (engine == null) {
                    engine = build();
                }
            }
        }
        return engine;
    }

    private static TemplateEngine build() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(PREFIX);
        resolver.setSuffix(SUFFIX);
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        // twigbridge 的 auto_reload = true
        resolver.setCacheable(false);
        resolver.setOrder(1);

        TemplateEngine built = new TemplateEngine();
        built.setTemplateResolver(resolver);
        built.addDialect(new NeoSkinDialect());
        return built;
    }

    /**
     * 渲染一个模板。
     *
     * @param name   模板名（{@code home} → {@code /views/home.html}）
     * @param locale 当前语言（消息解析用）
     * @param vars   视图变量（值可为 null，内部转 {@link NullValue}）
     * @return 渲染后的 HTML
     */
    /**
     * 渲染一个模板。
     *
     * @param name   模板名（{@code home} → {@code /views/home.html}）
     * @param locale 当前语言（消息解析用）
     * @param vars   视图变量（值可为 null，内部转 {@link NullValue}）
     * @return 渲染后的 HTML
     */
    public static String render(String name, Locale locale, Map<String, Object> vars) {
        Context context = new Context(locale == null ? Locale.ROOT : locale);
        if (vars != null) {
            vars.forEach((k, v) -> context.setVariable(k, v == null ? NullValue.INSTANCE : v));
        }
        return engine().process(name, context);
    }

    /** 测试用：丢弃已缓存的引擎，下次重新装配。 */
    public static void reset() {
        synchronized (ViewEngine.class) {
            engine = null;
        }
    }

    /**
     * 「变量存在但值为 null」的哨兵：Thymeleaf 的 {@code Context}
     * 不接受 null 值（{@code setVariable} 会抛异常），而 Twig 在
     * {@code strict_variables = false} 下把缺失/空变量当 {@code null}。
     * 方言求值时会把它还原成 {@code null}。
     */
    public enum NullValue {

        /** 唯一实例。 */
        INSTANCE;

        @Override
        public String toString() {
            return "";
        }
    }
}
