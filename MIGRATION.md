# NeoSkinSpringBoot — Java / Spring Boot 4.1.x 迁移计划

> 最后更新：2026-11-06

> **目标**：把 `blessing-skin-server-Laravel13`（6.0.11_patch）完整迁移到 Java 21 + Spring Boot 4.1.x，保持 **HTTP 接口字节级兼容** 与 **数据库结构/数据完全兼容**。

---

## 1. 硬约束（不可破坏）

1. **HTTP 接口零变更**：174 条路由的路径、方法、请求参数名、请求体字段名、响应 JSON 结构必须与源完全一致。
2. **数据完全兼容**：17 张表 + 存量数据 + bcrypt 密码哈希 + SHA-256 纹理 hash。
3. **插件 API 契约**：Hook（7 方法）、PluginManager（13 方法）、Plugin、24 个 Event。
4. **patch 定制功能**：6.0.3 ~ 6.0.11_patch 全部保留。

---

## 2. 技术栈与版本基线

| 组件 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java | 21 (LTS) | Spring Boot 4.1 要求 |
| 框架 | Spring Boot | 4.1.x | Spring Framework 7.0.x |
| Web | Vert.x (reactive, event-driven) | — | 替代 Laravel 路由 / Tomcat，嵌入式 Vert.x HTTP server |
| ORM | Spring Data JPA / Hibernate | — | 或 Jimmer（参考 JNeoSkin_backend） |
| 数据库 | SQLite / MySQL / MariaDB / PostgreSQL / SQL Server | — | JPA 多数据库（与 Laravel config/database.php 一致） |
| 认证 | Spring Security 7.x | — | Session + OAuth2 |
| OAuth2 Server | Spring Authorization Server | — | 替代 Passport |
| 模板 | Thymeleaf | — | 替代 104 个 Twig |
| 前端 | 复用 webpack + yarn 1.x 产物 | — | 静态资源服务 |
| 图像 | `javax.imageio` + Thumbnailator | — | 替代 imagick |
| 队列 | Spring `@Async` / RabbitMQ | — | 替代 Laravel Queue |
| 构建 | Maven / Gradle | — | slnx 不适用（Java 用 pom/gradle） |

---

## 3. 源系统盘点

| 维度 | 数量 |
| --- | --- |
| 数据表 | 17（另有 Laravel 自用的 `migrations`，共 18 张） |
| 路由 | 174 |
| 控制器 | 20（另有 1 个基类 `Controller`） |
| 中间件 | 12 |
| Event | 24 |
| Listener | 9 |
| Service | 11（另有 9 个 `Services\Cipher\*` 算法类） |
| Twig 模板 | 104（另有 5 个 Blade：`master` / `mails`） |
| JS/TS 源 | 131（含 tests 共 192） |
| 语言包 | 14 |
| 测试 | 67 |

---

## 4. 分层架构设计

```
NeoSkinSpringBoot/
├── pom.xml (或 build.gradle)
├── src/main/java/com/neoskin/
│   ├── NeoSkinApplication.java
│   ├── config/                 # Spring 配置类
│   ├── controller/             # 20 个 @RestController/@Controller
│   ├── service/                # 11 个 Service
│   ├── repository/             # Spring Data JPA Repository
│   ├── model/                  # JPA Entity（17 个）
│   ├── event/                  # 24 个 ApplicationEvent
│   ├── listener/               # 9 个 EventListener
│   ├── security/               # Spring Security 配置 + Filter
│   ├── oauth2/                 # OAuth2 Authorization Server
│   ├── plugin/                 # PF4J 插件系统
│   └── console/                # CLI 命令
├── src/main/resources/
│   ├── application.yml
│   ├── templates/              # Thymeleaf（86 个，覆盖 104 个 Twig）
│   ├── static/                 # webpack 产物
│   └── i18n/                   # 语言包
├── src/test/
└── database/migrations/        # Flyway/Liquibase
```

---

## 5. 数据层迁移

### 5.1 JPA Entity 映射

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "uid")
    private Long uid;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "nickname", length = 50)
    private String nickname = "";

    @Column(name = "score")
    private Integer score;

    @Column(name = "avatar")
    private Integer avatar = 0;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "ip", length = 32)
    private String ip;

    @Column(name = "permission")
    private Integer permission = 0;

    @Column(name = "last_sign_at")
    private LocalDateTime lastSignAt;

    @Column(name = "register_at")
    private LocalDateTime registerAt;
}
```

### 5.2 密码哈希兼容

密码相关逻辑全部收敛到三个类，**不允许**在业务代码里另起一套：

| 类 | 职责 |
| --- | --- |
| `service.cipher.PhpBcrypt` | bcrypt 的唯一实现：NUL 截断 → 72 字节截断 → `BCrypt.hashpw`；`$2y$` 原样保留 |
| `service.cipher.PhpPasswordVerify` | `password_verify()` 的唯一移植：bcrypt / argon2i / argon2id 分派 |
| `service.cipher.PhpPasswordInfo` | `password_get_info()` + `password_needs_rehash()` 的唯一移植 |

**禁止**使用 Spring Security 的 `BCryptPasswordEncoder.encode()`（密码超过 72 字节会抛异常，
而 PHP 静默截断）和 `matches()`（不接受 `$2y$` 前缀）。`org.mindrot.jbcrypt.BCrypt.checkpw`
与 `BCrypt.hashpw(byte[], String)` 可以直接用，但必须经过 `PhpBcrypt` 包装。

关键语义（已由 `conformance/data/cipher.json` 与 `hash.json` 固定）：

- 归一化顺序不可颠倒：先按**第一个 NUL 字节**截断（C 字符串语义），再按**字节**截断到 72
  （可能劈开多字节字符，产生非法 UTF-8）。
- `password_hash()` 拒绝含 NUL 的密码（`ValueError`），`crypt()` 容忍 NUL——两者行为不同，
  移植时分别对应 `PhpBcrypt.hash` 与 `PhpBcrypt.check`。
- 用户密码 bcrypt cost = 12（`NEOSKIN_PASSWORD_BCRYPT_ROUNDS`）；OAuth 客户端密钥
  cost = 10（`BCRYPT_ROUNDS`）。两个成本值**绝不合并**，见 `NeoSkinSettings.Hashing`。
- `LaravelHash.isHashed()` / `needsRehash()` 只认 `$2y$`，所以库里存的 `$2a$` 密钥
  会被重新哈希一次但仍能验证——这是有意的行为兼容，不是 bug。

### 5.3 表结构安装

**不使用 Flyway / Liquibase**。`flyway-database-sqlite` 与 `flyway-database-mariadb` 在
Maven Central 上并不存在，无法覆盖要求的 5 种数据库。

改用项目自有的 `db.SchemaInstaller`（纯 JDBC DDL）：

- 通过 `SqlDialect` 把 Laravel 的列类型映射到 SQLite / MySQL / MariaDB / PostgreSQL / SQL Server；
- 建表顺序、索引名、默认值、可空性与源 `database/migrations/` 逐字对齐；
- 同时写入 Laravel 的 `migrations` 表（23 条纯结构迁移），使 PHP 侧
  `php artisan migrate` 不会重复建表；
- 4 条数据迁移由 `migration.DataMigrator` 执行并记录，合计 27 条，与源项目完全一致；
- Hibernate 侧固定 `ddl-auto: none`，**绝不允许** JPA 生成或修改任何 DDL。

---

## 6. 接口层迁移

### 6.1 路由映射（Vert.x）

使用 Vert.x `Router` + handler，路径 1:1：

```java
Router router = Router.router(vertx);
router.get("/").handler(homeController::index);
router.route("/auth/*").handler(authMiddleware);
// ... 逐条移植，权威清单见 conformance/data/routes.json
```

### 6.2 中间件 → Vert.x Handler

12 个中间件 → 实现 `NeoSkinMiddleware` 的 Vert.x `Handler<RoutingContext>`（**不是** Servlet Filter，
本项目没有 Servlet 容器）。注册顺序与 `routes/web.php` 的 `middleware()` 链一致：

| 中间件 | 触发条件 |
| --- | --- |
| `ConvertEmptyStringsToNull` | 全局栈；路径为 `admin/options`、`admin/score`、`admin/resource`、`admin/customize` 时跳过 |
| `DetectLanguagePrefer` | 全局栈；`lang` 参数 → `locale` cookie → `Accept-Language`；非 `api` 路由回写 `locale` cookie |
| `EnforceEverGreen` | Chrome < 55 / Trident / MSIE 直接拒绝 |
| `Authenticate` | 未登录：`api`/`ajax` 返回 401 JSON，否则跳转登录 |
| `CheckRole` | 权限不足 → 403 |
| `RejectBannedUser` / `EnsureEmailFilled` / `CheckUserVerified` | `authorize` 组 |
| `FireUserAuthenticated` | 触发 `UserAuthenticated` 事件 |
| `RedirectIfAuthenticated` / `RedirectToSetup` / `CheckInstallation` | 见源文件

### 6.3 Minecraft 客户端契约

`GET /{player}.json` 和 `GET /csl/{player}.json` 返回 JSON 字段名必须一致。

---

## 7. 认证与授权

### 7.1 Session 认证

Spring Security Session 管理，Cookie 名称与源保持一致（`SESSION_COOKIE`）。

### 7.2 OAuth2 Server

Spring Authorization Server 替代 Passport，需配置：
- `RegisteredClientRepository`（对应 oauth_clients 表）
- `AuthorizationServerSettings`（端点路径 `/oauth/authorize`、`/oauth/token` 等）
- Scope 映射（`User.Read`、`Player.Read/ReadWrite` 等）

**关键**：端点路径和请求/响应格式必须与 Passport 完全一致。

### 7.3 Scope 机制

Spring Security `@PreAuthorize("hasAuthority('SCOPE_Player.Read')")` 或自定义 scope 检查。

---

## 8. 插件系统

### 8.1 方案

使用 **PF4J**（Plugin Framework for Java），成熟稳定：
- 插件以 JAR 包形式加载
- 插件接口定义在 `plugin-api` 模块
- 插件生命周期管理（start/stop）
- 插件市场下载与安装

### 8.2 Hook 映射

| Hook 方法 | Java 实现 |
| --- | --- |
| `addMenuItem` | Spring ApplicationEvent + 菜单注册表 |
| `addRoute` | 路由注册回调（启动时收集） |
| `addStyleFileToPage` / `addScriptFileToPage` | 页面资源收集器 |
| `addUserBadge` | 徽章注册表 |
| `sendNotification` | 通知服务 |
| `pushMiddleware` | Filter 链注册 |

### 8.3 Event 系统

24 个 Event → Spring `ApplicationEvent` + `@EventListener`。

---

## 9. 前端与视图层

### 9.0 决策：前端直接复用 blessing 项目（不自己重写）

**前端（`resources/assets/` + `public/app/`）不重写、不换技术栈、不做 SPA 改造**，
直接复用 `blessing-skin-server-Laravel13` 的既有前端：

| 资产 | 来源 | 处理方式 |
| --- | --- | --- |
| 前端源码 | `resources/assets/`（React 17 + TypeScript + webpack 5，131 个 JS/TS 文件） | 原样复用；构建沿用源仓库的 `package.json` / `webpack.config.ts` / `yarn.lock` |
| 预编译产物 | `public/app/`（`app.<hash>.js`、`style.<hash>.css`、`spectre.*`、`home-css.*`、`home.*`、字体/图片/wasm） | 原样复用，**不重新打包**（哈希值被 `resources/views/assets/*.twig` 写死引用） |
| 运行时语言包 | `/lang/<locale>.js`（由 `JavaScript` 服务生成） | 后端生成，内容与源一致 |

**关键约束 —— 服务端模板是「前端契约」，必须逐字保住 DOM 结构。**
预编译 bundle 靠选择器定位挂载点，改 HTML 结构等于改前端。以下都是硬契约：

- `window.blessing = {...}`（`shared.head`）与 `#blessing-extra` 的 JSON；
- `#logout-button`、`data-mark="nickname"`、`.locale`、`.main-button`、`.splash*`；
- auth 页的 `<main></main>`（React 挂载点）、`#blessing-extra` 的
  `{tooManyFails, recaptcha, invisible}`；
- `data-toggle="dropdown"` / `data-communication-*` / `data-system-update-form` 等
  行为钩子。

因此 **Twig 模板是移植的规格说明书**（spec），不是可以用自由风格重写的草稿。
服务端换模板引擎时，输出结构必须与 Twig 渲染结果一致。

---

### 9.1 Thymeleaf 转换

104 个 Twig 契约 → **86 个 Thymeleaf 模板**（表单由 `OptionForm` 生成、
`assets/*` 片段内联、错误页合并）。关键转换：
- `{{ variable }}` → `th:text="${variable}"`
- `{% if %}` → `th:if`
- `{% for %}` → `th:each`
- `{% extends %}` → `th:replace` / layout dialect

> 注意：转换的验收标准是 **9.0 的 DOM 契约**，不是「看起来一样」。

### 9.2 静态资源

`public/app/` 的预编译产物原样拷贝到 `src/main/resources/static/app/`（不重新
webpack 打包），由静态资源服务提供；`/lang/<locale>.js` 由 `JavaScript` 服务在
运行时生成到 `public/lang/`。

---

### 9.4 前后端分离：前端开发模式与运行时契约

前端可独立运行，复用它对后端提出了 5 条硬要求（缺一条线上白屏）：

1. **URL**：一律 `${blessing.base_url}${url}`，`base_url` 来自 `shared.head` 注入的
   `window.blessing`。
2. **凭证**：`credentials: 'same-origin'` → 会话 cookie 必须同源可带。
3. **请求头**：所有请求带 `Accept: application/json`；写请求带 `X-CSRF-TOKEN`，
   值取自 `meta[name="csrf-token"]`。⚠ 因此「校验失败」必须回 **422 JSON**
   （`{message, errors: {field: [..]}}`），**不能 302**。
4. **错误分支**：422 → 取 `errors[field][0]`；419 → CSRF 提示；403/400 → 弹
   `message`；其它非 2xx → 抛异常，若 body 含 `exception` + `trace` 会**渲染堆栈**。
5. **成功体**：`Content-Type: application/json` 才按 JSON 解析；业务层再看
   `{code, message, data}` 的 `code !== 0`。

**开发模式**：`yarn dev`（webpack-dev-server）默认 `0.0.0.0:8080`、`publicPath=/app/`、
`Access-Control-Allow-Origin: *`、HMR。dev 下产物名**无哈希**（`app.js`），
`resources/views/assets/*.twig` 会由 `HtmlWebpackPlugin` 重写成
`//localhost:8080/app/`；prod 才写 `{{ cdn_base }}/app/<hash>`。
⚠ `assets/*.twig` 是**构建产物**（`assets/.gitignore` = `*` + `!.gitignore`），
所以后端模板不能把哈希路径写死，否则 dev 模式不可用 —— 需要 debug 开关切换资源根。

**前端测试**：46 个 jest 用例（ts-jest + jsdom），`tests/setup.ts` 注入
`window.blessing` 与 `i18n`（读 `resources/lang/en/front-end.yml`），各用例自行
mock `fetch`。这些 mock 断言了 URL/方法/body/响应形状，等价于一份可执行的接口契约。

**路由表生成**：`yarn build:urls` 执行
`php artisan route:list --json --columns=uri,name` 生成
`resources/assets/src/scripts/urls.ts`（已提交，复用前端时无需重生成）。若需重生成，
后端须提供等价的 `route:list --json --columns=uri,name` 输出。

## 10. 图像处理

- `javax.imageio` + Thumbnailator 替代 imagick。
- avatar 端点返回 2D PNG。
- 纹理预览/缩略图。

---

## 11. 国际化

- Spring `MessageSource` + `messages_{locale}.properties`。
- `language_lines` 表保持（DB 驱动的翻译覆盖）。

---

## 12. 分阶段实施计划

### Phase 0：项目骨架（1-2 天）
- [x] Spring Boot 4.1 项目初始化（Maven/Gradle）
- [x] 配置数据库连接
- [x] 自研 SchemaInstaller（纯 JDBC，未使用 Flyway：5 种数据库方言）
- [x] 验证 `mvn spring-boot:run`

### Phase 1：数据层 + 模型（3-5 天）
- [x] 17 张表（JDBC 仓储 + 记录类，未使用 JPA Entity）
- [x] 密码哈希兼容（jBCrypt `$2y$`）
- [x] 纹理 hash 算法
- [x] 单元测试

### Phase 2：认证与授权（3-5 天）
- [x] Spring Security 配置
- [x] OAuth2 Authorization Server（Passport 兼容）
- [x] Filter/Interceptor 链（13 个）
- [x] Scope 机制

### Phase 3：核心业务控制器（7-10 天）
- [x] AuthController, UserController, PlayerController, ClosetController
- [x] TextureController, SkinlibController, ReportController
- [x] ClosetManagement, PlayersManagement, UsersManagement

### Phase 4：管理面板（5-7 天）
- [x] AdminController, CommunicationsController, NotificationsController
- [x] OptionsController, TranslationsController, PluginController
- [x] UpdateController, MarketController

### Phase 5：安装向导（2-3 天）
- [x] SetupController, CheckInstallation

### Phase 6：前端（5-7 天）
- [x] 86 个 HTML 模板（覆盖源 104 个 Twig：表单由 `OptionForm` 生成、`assets/*` 片段内联、错误页合并）
- [x] 静态资源服务

### Phase 7：插件系统（5-7 天）
- [x] PF4J 集成（原生 Java 插件；PHP 插件不可执行，D-001）
- [x] 插件 manifest 解析
- [x] 事件总线
- [x] 插件管理

### Phase 8：测试与验证（3-5 天）
- [x] 97 个测试类 / 1085 个测试用例（含语料库一致性）
- [x] HTTP 契约测试
- [x] 数据兼容性测试

---

## 13. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| OAuth2 Passport 兼容 | 高 | Spring Authorization Server 自定义端点，严格按 Passport 格式 |
| PF4J 插件生态 | 中 | PF4J 成熟稳定，参考 JNeoSkin_backend 已有 spring-impl |
| Thymeleaf 转换 | 中 | 优先转换高频页面 |
| bcrypt `$2y$` 兼容 | 高 | 使用 jBCrypt 原生支持 |
| Minecraft 客户端兼容 | 高 | 严格对照响应格式 |

---

## 14. 验证清单

```sh
mvn clean verify
# 或
./gradlew build
```

- [x] 构建无错误
- [x] 所有 174 条路由注册成功
- [x] 数据库 migration 执行无错误
- [x] 存量数据可正常读取
- [x] bcrypt 密码验证通过
- [x] OAuth2 端点与 Passport 响应一致
- [x] Minecraft 客户端可正常获取皮肤/披风
- [x] 1085 个测试用例全部通过（97 个测试类，见 README/AGENTS）
