# NeoSkinSpringBoot

Java / Spring Boot 4.1.x 实现的 Minecraft 皮肤托管平台，逐阶段迁移自
`blessing-skin-server-Laravel13`（6.0.11_patch）。HTTP 契约、数据库结构、
`public/app` 前端产物保持与源项目一致。

## 当前状态（2026-11-05）

| 项 | 数值 |
| --- | --- |
| 路由接线 | **174 / 174**（145 条唯一路径；源码漏记的 `POST skinlib` 已补） |
| 模板 | 86 个 HTML 模板（覆盖源 104 个 Twig：表单由 `OptionForm` 生成、`assets/*` 片段内联、错误页合并） |
| 测试 | 97 个测试类 / 1085 个用例；`mvn clean verify` 全绿 |
| 数据库矩阵 | sqlite / mysql / mariadb / pgsql 本机 + CI；sqlsrv 用 WiltonDB 本机 + CI 真 SQL Server |
| 插件 | PHP 清单层（安装/启停/市场）+ PF4J 原生 Java 插件；PHP 插件代码不可执行（D-001） |
| 更新 | ZIP 下载/上传/暂存/安装；`--neoskin.command=update` 执行结构迁移 + 4 个数据迁移 |

## 技术栈

- Java 21
- Spring Boot 4.1.x（仅用 IoC / 配置 / JPA，**无 Servlet 容器**）
- Vert.x 5.2.x（HTTP 层）
- Thymeleaf（模板，替代 Twig 但保留输出契约）
- SQLite / MySQL / MariaDB / PostgreSQL / SQL Server（与源一致）
- Maven

## 目录

```
NeoSkinSpringBoot/
├── pom.xml
├── scripts/prepare-public.ps1   # 拷贝源项目 public/app 到部署目录
└── src/
    ├── main/java/com/neoskin/
    │   ├── NeoSkinApplication.java   # 入口（含 CLI 模式）
    │   ├── cli/                      # php artisan 等价命令
    │   ├── config/                   # 配置、DataSource、装配
    │   ├── db/                       # SchemaInstaller + JDBC 仓库
    │   ├── oauth/                    # Passport（OAuth2）
    │   ├── render/                   # 皮肤/披风渲染（GD 移植）
    │   ├── service/                  # Option、插件、更新、邮件、图片…
    │   ├── support/                  # PHP 语义（Json/Serialize/Date/Semver…）
    │   └── web/                      # 控制器、中间件、模板引擎、错误页
    ├── main/resources/
    │   ├── application.yml           # 与源 .env 一一对应
    │   ├── views/                    # Thymeleaf 模板
    │   ├── lang/                     # 14 种语言包
    │   └── codegraph/routes.json     # 174 条路由（唯一权威）
    └── test/java/                    # 单元 + 端到端测试
```

## 构建与测试

```sh
mvn clean verify     # 全部测试（97 个测试类 / 1085 个用例）
mvn package -DskipTests
```

## 运行

配置与源 `.env` 同名（`APP_KEY`、`DB_*`、`MAIL_*` …），通过环境变量注入，
`application.yml` 里给的是默认值。

```sh
java -jar target/neoskin-springboot-6.0.11_patch-SNAPSHOT.jar
```

**前端产物**：`mvn package` 会**自动**把源项目 `public/app/`（108 个文件）打进
jar 的 `static/app/`；部署时没有 `<base>/public/` 也能正常加载前端（classpath
回退）。源目录默认是同级仓库 `../blessing-skin-server-Laravel13/public/app`，
可用属性覆盖（CI 里由 `frontend` job 构建后传入绝对路径）：

```sh
mvn clean verify -Dfrontend.app.dir=/path/to/public/app
```

只有「想用磁盘上的 public 覆盖 jar 内产物」时才需要：

```powershell
pwsh scripts/prepare-public.ps1
# 或手动：把 blessing-skin-server-Laravel13/public/app 复制到 <base>/public/app
```

磁盘 `public/` 存在时优先使用它（便于热替换前端），否则用 jar 内置版本。

## CLI（`php artisan` 等价物）

CLI 模式启动 Spring 容器但不监听 HTTP，执行完即退出：

```sh
java -jar app.jar --neoskin.command=install --email=a@b.com --password=secret123 --nickname=Admin
java -jar app.jar --neoskin.command=update
java -jar app.jar --neoskin.command=options:cache
java -jar app.jar --neoskin.command=plugin:enable  --name=PluginName
java -jar app.jar --neoskin.command=plugin:disable --name=PluginName
java -jar app.jar --neoskin.command=salt:random --show
java -jar app.jar --neoskin.command=down --retry=60
java -jar app.jar --neoskin.command=up
```

`--route-list-json` 仍保留（前端 `yarn build:urls` 用）。

## CI（GitHub Actions）

`.github/workflows/ci.yml`：

| job | 内容 |
| --- | --- |
| `Build frontend (public/app)` | 从 `KnightBlood/blessing-skin-server-Laravel13` 取源码，node 24 + yarn 1.22.22 执行 `yarn install --frozen-lockfile` + `yarn build`，再补上 `build-php85.py` 额外拷贝的 `bg.webp` / `favicon.ico` / `standalone/*.js`，上传 108 个文件 |
| `verify (sqlite / mysql / mariadb / pgsql / sqlsrv)` × 5 | 下载前端 artifact，启动对应数据库容器，`mvn clean verify -Dfrontend.app.dir=...` |

## 已知限制

- PHP 插件只能安装/启用/管理，**不能执行**（没有 PHP 运行时）；原生扩展使用
  PF4J Java 插件（D-001）。
- 缓存支持 `file`（默认，与 PHP file store 同格式）/ `redis`（`CACHE_DRIVER=redis`，
  可用 `../tools/` 下的 Redis 或 Garnet 验证）/ `array`；会话同样支持
  `SESSION_DRIVER=file`（默认）/ `redis` / `array`；图片缓存仍是进程内。
- 数据库矩阵：sqlite / mysql / mariadb / pgsql 本地 + CI 均可验证；
  sqlsrv 本机可用 WiltonDB（SQL Server 协议）跑同一矩阵，真 SQL Server 由
  `.github/workflows/ci.yml` 的 sqlsrv job（mcr.microsoft.com/mssql/server:2022
  镜像）覆盖；矩阵引导测试为 `com.neoskin.MatrixSpringBootTest`（按
  `DB_CONNECTION` 环境变量启用）。
- 数据库名默认 `blessingskin`（与 PHP `.env.example` 一致）；本机共享脚本
  `../tools/scripts/db.ps1` 创建的是 `blessing_skin`，跑矩阵时显式设置
  `DB_DATABASE=blessing_skin`。
- 版本升级的自动 `update` 尚未接线：`RedirectToSetup` 只记录“版本较旧”，
  不会像 PHP 那样在请求期调用 `Artisan::call('update')`；升级后需显式执行
  `--neoskin.command=update`。
- SQL Server 连接开关：`DB_ENCRYPT` / `DB_TRUST_SERVER_CERTIFICATE`。
- WebP 由 `webp-imageio` 编码；`?png` 可强制 PNG。
- 其余偏差（Passport 令牌格式、渲染字节、更新流程等）见根目录 `PORT-NOTES.md`。
