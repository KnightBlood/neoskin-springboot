package com.neoskin.web.controllers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\AdminController} 的移植（仪表盘 / 折线图 / 运行状态）。
 *
 * <p>{@code index} 与 {@code status} 通过 {@code shared/grid} 渲染 widget 网格；
 * {@code chartData} 输出 ECharts 需要的 {@code {labels,xAxis,data}}。
 * 网格上的 {@code grid:admin.index} / {@code grid:admin.status} 过滤钩子与插件系统
 * 尚未接入（见 PORT-NOTES §24）。</p>
 */
public final class AdminController {

    /** {@code app()->version()}（vendor/laravel/framework 的 VERSION 常量）。 */
    private static final String LARAVEL_VERSION = "13.38.0";

    private final Option option;
    private final Translator translator;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final UserRepository users;
    private final PlayerRepository players;
    private final TextureRepository textures;
    private final boolean debug;
    private final AppPaths paths;
    private final NeoSkinSettings settings;
    private final String timezone;

    public AdminController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, UserRepository users, PlayerRepository players,
            TextureRepository textures, boolean debug, AppPaths paths,
            NeoSkinSettings settings, String timezone) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.filter = filter;
        this.users = users;
        this.players = players;
        this.textures = textures;
        this.debug = debug;
        this.paths = paths;
        this.settings = settings;
        this.timezone = timezone;
    }

    // ============================================================ index

    /** {@code GET admin}。 */
    public Handler<RoutingContext> index() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                long storage = textures.sumSize();

                Map<String, Object> sum = new LinkedHashMap<>();
                sum.put("users", users.countAll());
                sum.put("players", players.countAll());
                sum.put("textures", textures.countAll());
                sum.put("storage", storage);

                Map<String, Object> vars = pageVars(ctx, "admin",
                        translator.trans("general.dashboard", Map.of(), locale), locale);
                vars.put("sum", sum);
                vars.put("sum_storage_display", storageDisplay(storage));
                vars.put("gridRows", indexGrid());
                render(ctx, "admin/index", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ chart

    /** {@code GET admin/chart}：最近 31 天（今天含）的注册/上传折线数据。 */
    public Handler<RoutingContext> chartData() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                LocalDate today = LocalDate.parse(PhpDate.now(timezone).substring(0, 10));
                LocalDate oneMonthAgo = subMonthPhp(today);
                String since = oneMonthAgo + " 00:00:00";

                Map<String, Integer> userRegistration = groupByDay(users.registerTimesSince(since),
                        locale);
                Map<String, Integer> textureUploads = groupByDay(
                        textures.uploadTimesSince(since), locale);

                List<Object> xAxis = new ArrayList<>();
                List<Object> registrationData = new ArrayList<>();
                List<Object> uploadData = new ArrayList<>();

                // Collection::times(31, fn ($i) => today()->subDays(31 - $i))
                for (int i = 1; i <= 31; i++) {
                    String day = carbonL(today.minusDays(31 - i), locale);
                    xAxis.add(day);
                    registrationData.add(userRegistration.getOrDefault(day, 0));
                    uploadData.add(textureUploads.getOrDefault(day, 0));
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("labels", List.of(
                        translator.trans("admin.index.user-registration", Map.of(), locale),
                        translator.trans("admin.index.texture-uploads", Map.of(), locale)));
                payload.put("xAxis", xAxis);
                payload.put("data", List.of(registrationData, uploadData));

                ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(Buffer.buffer(PhpJson.encode(payload, PhpJson.JSON_UNESCAPED_UNICODE),
                                "UTF-8"));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ status

    /** {@code GET admin/status}。 */
    public Handler<RoutingContext> status() {
        return ctx -> {
            try {
                String locale = translator.getLocale();

                Map<String, Object> bs = new LinkedHashMap<>();
                bs.put("version", settings.getVersion());
                bs.put("env", settings.getEnv());
                bs.put("debug", debug
                        ? translator.trans("general.yes", Map.of(), locale)
                        : translator.trans("general.no", Map.of(), locale));
                bs.put("commit", limit(commit(), 16));
                bs.put("laravel", LARAVEL_VERSION);

                Map<String, Object> server = new LinkedHashMap<>();
                server.put("php", System.getProperty("java.version", ""));
                String web = ctx.request().getHeader("Server");
                server.put("web", web == null || web.isEmpty()
                        ? translator.trans("general.unknown", Map.of(), locale) : web);
                server.put("os", System.getProperty("os.name", "") + " "
                        + System.getProperty("os.version", "") + " "
                        + System.getProperty("os.arch", ""));

                NeoSkinSettings.Database db = settings.getDatabase();
                String type = switch (db.getConnection()) {
                    case "mysql" -> "MySQL/MariaDB";
                    case "sqlite" -> "SQLite";
                    case "pgsql" -> "PostgreSQL";
                    default -> "";
                };
                boolean hasNetworkFields = !"sqlite".equals(db.getConnection());
                Map<String, Object> dbInfo = new LinkedHashMap<>();
                dbInfo.put("type", type);
                dbInfo.put("host", hasNetworkFields ? db.getHost() : "");
                dbInfo.put("port", hasNetworkFields ? db.getPort() : "");
                dbInfo.put("username", hasNetworkFields ? db.getUsername() : "");
                dbInfo.put("database", db.getName());
                dbInfo.put("prefix", db.getPrefix());

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("bs", bs);
                detail.put("server", server);
                detail.put("db", dbInfo);

                Map<String, Object> vars = pageVars(ctx, "admin",
                        translator.trans("general.status", Map.of(), locale), locale);
                vars.put("detail", detail);
                // 插件系统（PF4J）尚未接入 → 已启用插件列表恒为空（PORT-NOTES §24）
                vars.put("plugins", List.of());
                vars.put("plugins_title", translator.trans("admin.status.plugins",
                        Map.of("amount", 0), locale));
                vars.put("gridRows", statusGrid());
                render(ctx, "admin/status", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ helpers

    /**
     * {@code Carbon::today()->subMonth()}：PHP 的月减法会「溢出」到下一个月
     * （例如 10-31 减 1 月 = 10-01），用「月初 + (日-1) 天」复刻。
     */
    static LocalDate subMonthPhp(LocalDate date) {
        int totalMonths = date.getYear() * 12 + (date.getMonthValue() - 1) - 1;
        int year = Math.floorDiv(totalMonths, 12);
        int month = Math.floorMod(totalMonths, 12) + 1;

        return LocalDate.of(year, month, 1).plusDays(date.getDayOfMonth() - 1L);
    }

    /**
     * Carbon {@code isoFormat('l')}：按 locale 的 CLDR 短日期输出
     * （实测各语言的 {@code formats.L}，见 PORT-NOTES §24）。
     */
    static String carbonL(LocalDate date, String locale) {
        return formatterFor(locale).format(date);
    }

    private static DateTimeFormatter formatterFor(String locale) {
        String l = locale == null ? "en" : locale;
        String pattern = switch (l) {
            case "zh_CN", "zh_TW", "ja_JP" -> "yyyy/M/d";
            case "ko_KR" -> "yyyy.M.d.";
            case "de_DE", "ru_RU" -> "d.M.yyyy";
            case "el_GR", "es_ES", "fr_FR", "it_IT", "pt_PT" -> "d/M/yyyy";
            case "nl_NL" -> "d-M-yyyy";
            default -> "M/d/yyyy";
        };

        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    }

    /** 按 {@code isoFormat('l')} 分组计数（与源按「本地化日期串」groupBy 一致）。 */
    private static Map<String, Integer> groupByDay(List<String> timestamps, String locale) {
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (String timestamp : timestamps) {
            LocalDateTime parsed = PhpDate.parse(timestamp);
            if (parsed == null) {
                continue;
            }
            String day = carbonL(parsed.toLocalDate(), locale);
            grouped.merge(day, 1, Integer::sum);
        }

        return grouped;
    }

    /** {@code Str::limit($commit, 16, '')}。 */
    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }

        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * {@code Str::limit(trim(git log --pretty=%H -1), 16, '')}：
     * 仅当 {@code base_path('.git')} 存在时才执行 git。
     */
    private String commit() {
        Path git = paths.base().resolve(".git");
        if (!Files.exists(git)) {
            return "";
        }

        try {
            Process process = new ProcessBuilder("git", "log", "--pretty=%H", "-1")
                    .directory(paths.base().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "";
            }

            return output;
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code (sum.storage / 1024)|round(1) }}MB 或 {{ sum.storage }}KB。 */
    static String storageDisplay(long kilobytes) {
        if (kilobytes > 1024) {
            BigDecimal value = BigDecimal.valueOf(kilobytes)
                    .divide(BigDecimal.valueOf(1024), 1, RoundingMode.HALF_UP);

            return value.stripTrailingZeros().toPlainString() + "MB";
        }

        return kilobytes + "KB";
    }

    /** {@code grid:admin.index}。 */
    private static List<List<Map<String, Object>>> indexGrid() {
        return List.of(List.of(
                col("md-6", "admin/widgets/dashboard/usage"),
                col("md-6", "admin/widgets/dashboard/chart")));
    }

    /** {@code grid:admin.status}。 */
    private static List<List<Map<String, Object>>> statusGrid() {
        return List.of(List.of(
                col("md-6", "admin/widgets/status/info"),
                col("md-6", "admin/widgets/status/plugins")));
    }

    private static Map<String, Object> col(String css, String... widgets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", css);
        m.put("widgets", List.of(widgets));

        return m;
    }

    private Map<String, Object> pageVars(RoutingContext ctx, String scope, String title,
            String locale) throws Exception {
        String urlRoot = "http://" + hostOf(ctx);
        com.neoskin.service.session.SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = com.neoskin.web.middleware.Authenticate.currentUser(ctx);
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"),
                RouteUrlGenerator.forHost(hostOf(ctx)),
                locale, session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = PageView.variables(option, translator, javaScript, filter,
                user, locale, urlRoot, ctx.request().path(), debug, scope, vc);
        vars.put("pageTitle", title);
        vars.put("errors", new AuthController.ErrorsView(
                com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                        session == null ? null : session.get("errors"))));

        return vars;
    }

    private void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = ViewEngine.render(template, Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");

        return h == null || h.isEmpty() ? "localhost" : h;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, UserRepository users,
            PlayerRepository players, TextureRepository textures, boolean debug,
            AppPaths paths, NeoSkinSettings settings, String timezone) {
        AdminController c = new AdminController(option, translator, javaScript, filter, users,
                players, textures, debug, paths, settings, timezone);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\AdminController@index" -> c.index();
            case "App\\Http\\Controllers\\AdminController@chartData" -> c.chartData();
            case "App\\Http\\Controllers\\AdminController@status" -> c.status();
            default -> null;
        };
    }
}
