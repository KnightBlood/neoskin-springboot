# AGENTS.md — NeoSkinSpringBoot (Java / Spring Boot 4.1.x) 迁移约束

> 最后更新：2026-11-06

本文件约束所有 agent 在 `NeoSkinSpringBoot/` 目录内的行为。

---

## 1. 目标与基线

- **目标**：将 `blessing-skin-server-Laravel13`（6.0.11_patch）完整迁移到 Java 21 + Spring Boot 4.1.x。
- **基线**：`D:\WORKspace\NeoSkin\blessing-skin-server-Laravel13`（只读参考）。

## 2. 硬约束（不可破坏）

1. **HTTP 接口零变更**：174 条路由的路径/方法/参数/响应结构与源完全一致。
2. **数据完全兼容**：17 张表 + 存量数据 + bcrypt 密码（使用 jBCrypt 支持 `$2y$`）+ SHA-256 纹理 hash。
3. **插件 API 契约**：Hook（7 方法）、PluginManager（13 方法）、Plugin、24 个 Event。
4. **patch 功能保留**：6.0.3 ~ 6.0.11_patch 全部功能。
5. **禁止修改源目录**。
6. **前端直接复用，不自己重写**：`resources/assets/`（前端源码）与
   `public/app/`（预编译产物）原样复用源项目，**不重新打包、不换技术栈、
   不做 SPA 改造**。静态产物拷贝到 `src/main/resources/static/app/`，`/lang/<locale>.js`
   由后端生成。服务端模板（Twig）是**前端契约**：`window.blessing`、
   `#blessing-extra`、`#logout-button`、`data-mark="nickname"`、`.locale`、
   `<main></main>`、`data-*` 钩子必须逐字保住。详见 `MIGRATION.md §9.0`。

## 3. 技术栈锁定

| 组件 | 版本 |
| --- | --- |
| Java | 21 (LTS) |
| Spring Boot | 4.1.x |
| Spring Framework | 7.0.x |
| Spring Security | 7.x |
| Web 服务器 | Vert.x 5.2.x（**禁止 Tomcat / Jetty / Undertow 等 Servlet 容器**） |
| ORM | Spring Data JPA / Hibernate |
| 数据库 | SQLite / MySQL / MariaDB / PostgreSQL / SQL Server |
| OAuth2 | Spring Authorization Server |
| 模板 | Thymeleaf |
| 插件 | PF4J（原生 Java 插件）+ PHP 清单层 | PHP 插件代码不可执行，需改写为 Java 插件（D-001） |
| 构建 | Maven / Gradle |

## 4. 禁止事项

1. 禁止改变数据库表结构语义。
2. 禁止破坏插件 API。
3. 禁止改动 `.env` 运行时行为。
4. 禁止引入 Servlet 容器（Tomcat / Jetty / Undertow）或 `spring-boot-starter-web`；Web 层只能使用 Vert.x。
5. 禁止提交 `target/`、`build/`、`node_modules/`、`.env`。

> 插件策略（D-001）：PHP 插件不可执行；保留 `package.json` 清单层，
> 原生扩展使用 PF4J Java 插件。详见根目录 `../DECISIONS.md`。

## 5. 代码风格

- 遵循 Java 标准（Google Java Style / Spring conventions）。
- 注释与文档使用中文，代码标识符使用英文。

## 6. 验证清单

```sh
mvn clean verify
# 或
./gradlew build
```

- [ ] 构建无错误
- [ ] 所有 174 条路由注册成功
- [ ] 数据库 migration 执行无错误
- [ ] 存量数据可正常读取
- [ ] bcrypt 密码验证通过
- [ ] OAuth2 端点与 Passport 响应一致
- [ ] Minecraft 客户端可正常获取皮肤/披风
- [ ] 97 个测试类 / 1085 个测试用例全部通过

## 7. 进度报告格式

agent 完成一个阶段后必须报告：
1. 已完成的 MIGRATION.md 阶段编号。
2. 修改的文件清单。
3. 失败/阻塞项与原因。
4. 下一阶段待办。

---

## 8. 源代码图谱（CodeGraph）

源项目 `D:\WORKspace\NeoSkin\blessing-skin-server-Laravel13`（只读）已生成机读代码图谱，移植时**优先读取它**，不要靠反复 grep 源码推断契约。

| 文件 | 内容 | 用途 |
| --- | --- | --- |
| `CODEGRAPH.md` | 架构总览、17 张表、路由分组、插件 API | 先读这一份建立全局认知 |
| `codegraph/routes.json` | 174 条路由（method / uri / name / action / middleware） | 1:1 生成路由注册代码 |
| `codegraph/controllers.json` | 20 个控制器类名 | 对照拆分 Controller |
| `codegraph/models.json` | 6 个 app 层 Eloquent 模型 | 对照生成 Entity/Model |
| `codegraph/events.json` | 24 个 Event + 9 个 Listener | 事件总线移植 |
| `codegraph/services.json` | 11 个 Service | 业务层移植 |
| `codegraph/middleware.json` | 12 个中间件 | 过滤器/中间件链移植 |

使用纪律：

1. 路由以 `routes.json` 为唯一权威，**不得增删改**路径、方法或参数名。
2. 表结构以 `database/migrations/` 为准（`codegraph` 不含字段级 DDL），字段名/类型/默认值必须逐字对齐。
3. 若发现 `codegraph` 与源码不一致，以源码为准，并在本文件记录偏差。
4. 迁移过程中新增的任何映射关系，追加到根目录 `../PORT-NOTES.md`（跨项目），
   本项目特有偏差写在本目录 `README.md` 的「已知限制」，不回写源项目。

已知偏差：

- `routes.json` 原 173 条漏记了源码 `routes/web.php` 里的
  `Route::post('', 'SkinlibController@handleUpload')`。已按源码补为
  `skinlib.upload.submit`（middleware: web / authorize / verified），
  总数 **174**，测试按 174 断言。
