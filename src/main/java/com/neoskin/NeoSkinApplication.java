package com.neoskin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.neoskin.config.NeoSkinSettings;

/**
 * NeoSkin 应用入口。
 *
 * <p>本项目<b>不使用任何 Servlet 容器</b>（见 AGENTS.md §4.4）。Spring 只负责依赖注入、
 * 事务、JPA 与配置绑定，HTTP 层完全由 Vert.x 承载，因此这里显式关闭 Spring 的
 * WebApplicationType，避免任何 Web 自动配置被激活。
 */
@SpringBootApplication
@EnableConfigurationProperties(NeoSkinSettings.class)
public class NeoSkinApplication {

    public static void main(String[] args) {
        // CLI 等价物：php artisan route:list --json --columns=uri,name
        // 前端 tools/generateUrls.ts（yarn build:urls）需要这个输入。
        if (hasFlag(args, "--route-list-json")) {
            System.out.println(com.neoskin.web.RouteListJson.render());
            return;
        }

        SpringApplication app = new SpringApplication(NeoSkinApplication.class);
        // 关键：没有 Tomcat / Jetty / Undertow
        app.setWebApplicationType(WebApplicationType.NONE);

        // CLI 模式（php artisan 等价物）：启动容器但不监听 HTTP，执行完即退出。
        String command = argValue(args, "--neoskin.command");
        if (command != null) {
            com.neoskin.cli.Commands.markActive();
        }
        var context = app.run(args);
        if (command != null) {
            int code = com.neoskin.cli.Commands.run(context, command, args);
            context.close();
            System.exit(code);
        }
    }

    private static String argValue(String[] args, String name) {
        if (args == null) {
            return null;
        }
        String prefix = name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
