package com.neoskin.ignition;

import java.util.List;
import java.util.Locale;

/**
 * Spatie Ignition 的「可运行解决方案」开关（对应 {@code RunnableSolutionsGuard}）。
 *
 * <p>源逻辑：{@code APP_DEBUG=false} 一律禁用；显式设置
 * {@code IGNITION_ENABLE_RUNNABLE_SOLUTIONS} 时以该值为准；否则只有
 * {@code APP_ENV} 为 {@code local} / {@code development} 且开启 debug 才允许。
 * 三条 {@code /_ignition/*} 路由在禁用时一律 404（{@code RunnableSolutionsEnabled} 中间件）。</p>
 */
public final class Ignition {

    /**
     * {@code health-check} 返回的 {@code can_execute_commands}。
     *
     * <p>源通过 {@code Artisan::call('help', ['--version'])} 判断能否执行命令；
     * Java 端没有 Artisan 等价物，恒为 false（见 PORT-NOTES §35）。</p>
     */
    public static final boolean CAN_EXECUTE_COMMANDS = false;

    private static final String OVERRIDE = "IGNITION_ENABLE_RUNNABLE_SOLUTIONS";

    private static volatile boolean debug;
    private static volatile String environment = "production";

    private Ignition() {
    }

    /** 由 {@code WebConfig} 在启动时注入（{@code APP_DEBUG} / {@code APP_ENV}）。 */
    public static void configure(boolean debugMode, String env) {
        debug = debugMode;
        environment = env == null ? "production" : env;
    }

    /** {@code RunnableSolutionsGuard::check()}。 */
    public static boolean check() {
        String override = override();
        if (override != null) {
            return truthy(override);
        }
        if (!debug) {
            return false;
        }

        return "local".equals(environment) || "development".equals(environment);
    }

    private static String override() {
        for (String name : List.of(OVERRIDE, "neoskin.ignition.enable-runnable-solutions")) {
            String value = System.getenv(name);
            if (value == null) {
                value = System.getProperty(name);
            }
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }

        return null;
    }

    /** PHP 的 {@code env()} 语义：{@code false/0/off/no/null/空} 为假。 */
    private static boolean truthy(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);

        return !(normalized.isEmpty() || normalized.equals("false") || normalized.equals("0")
                || normalized.equals("off") || normalized.equals("no") || normalized.equals("null"));
    }
}
