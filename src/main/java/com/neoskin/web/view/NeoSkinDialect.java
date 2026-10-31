package com.neoskin.web.view;

import java.util.Set;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

/**
 * 把源项目的 Twig 全局函数暴露成 Thymeleaf 表达式对象。
 *
 * <p>{@link ViewContext} 每请求一个实例，挂到模板上下文的 {@code view} 键上
 * （由 {@link ViewEngine#render} 的调用方注入），模板里就能写：</p>
 *
 * <pre>
 * &lt;span th:text="${view.trans('general.login')}"&gt;
 * &lt;a th:href="${view.url('skinlib')}"&gt;
 * &lt;meta th:content="${view.csrfToken()}"&gt;
 * </pre>
 *
 * <p>没有显式注入时给一个「空」上下文，避免 NPE —— 对应 Twig
 * {@code strict_variables = false} 下「函数照常可调用」的语义。</p>
 */
public final class NeoSkinDialect extends AbstractDialect implements IExpressionObjectDialect {

    /** 模板上下文里 {@link ViewContext} 的键名。 */
    public static final String CTX_KEY = "view";

    private static final Set<String> NAMES = Set.of(CTX_KEY);

    private static final Factory FACTORY = new Factory();

    public NeoSkinDialect() {
        super("neoskin");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return FACTORY;
    }

    private static final class Factory implements IExpressionObjectFactory {

        @Override
        public Set<String> getAllExpressionObjectNames() {
            return NAMES;
        }

        @Override
        public Object buildObject(IExpressionContext context, String name) {
            if (!CTX_KEY.equals(name)) {
                return null;
            }
            Object existing = context.getVariable(CTX_KEY);
            return existing instanceof ViewContext vc
                    ? vc : new ViewContext(null, null, null, null, null, null, null, null);
        }

        /**
         * 不缓存：{@link ViewContext} 每请求不同（语言 / CSRF token /
         * 当前用户都变），缓存会串请求。
         */
        @Override
        public boolean isCacheable(String name) {
            return false;
        }
    }
}
