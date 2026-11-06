# NeoSkinSpringBoot — 进度快照

> 最后更新：2026-11-06
> 基准：`blessing-skin-server-Laravel13`（6.0.11_patch，只读）

## 1. 总览

| 指标 | 数值 |
| --- | --- |
| 路由 | **174 / 174**（145 条唯一路径） |
| 数据表 | 17 / 17 |
| 模板 | 86 个 HTML 模板 |
| 测试 | **1086 用例，0 fail / 2 skip**（`mvn test`，2026-11-06） |
| 数据库矩阵 | sqlite / mysql / mariadb / pgsql 本机 + CI；sqlsrv WiltonDB 本机 + CI 真 SQL Server |
| 插件 | PHP 清单层 + PF4J 原生 Java 插件（PHP 插件不可执行，D-001） |
| 更新 | ZIP 下载/上传/暂存/安装；请求期自动 `update` |
| 浏览器 E2E | Playwright + 系统 Chrome，CI `browser-e2e` job |

## 2. 模块状态

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 项目骨架 | 100% | Spring Boot 4.1 + Vert.x 5，CLI 与 Web 双模式 |
| 数据层 | 100% | 17 表、JPA 映射、4 个数据迁移、时区/时间格式对齐 |
| 认证/授权 | 100% | Session、CSRF、验证码、记住我、找回/重置/验证/绑定 |
| OAuth2 | 100% | Passport 兼容：authorize/approve/deny、三种 grant、tokens/scopes |
| 业务控制器 | 100% | 用户/玩家/衣柜/纹理/皮肤库/举报/站内信 |
| 管理端 | 100% | 用户/玩家/衣柜/选项/自定义/积分/资源/i18n/通信/状态/图表/插件/更新 |
| 安装向导 | 100% | `/setup` 四页 + 建表 + 超管 + `install.lock` |
| 模板/视图 | 100% | 86 个 Thymeleaf 模板，DOM 契约保持 |
| 插件系统 | 100% | 清单/启停/市场 + PF4J；Hook/Filter/Event |
| 自动更新 | 100% | 请求期 `UpdateRunner`（幂等、并发保护、失败退避） |
| 测试/CI | 100% | 1086 用例 + 五库矩阵 + browser-e2e |

## 3. 已知偏差（摘要）

1. Thymeleaf 而非 Twig；部分 Twig 语法用等价写法。
2. Passport 令牌格式、`laravel_token` 加密方式与 PHP 不完全相同（详见根 `PORT-NOTES.md`）。
3. 图像编码字节不保证与 GD/Imagick 完全一致。
4. 缓存/会话有进程内实现；多实例需切 `redis`。
5. PHP 插件不可执行（D-001）。
6. 真 SQL Server 仅 CI；本机用 WiltonDB 替身。

## 4. 变更记录

### 2026-11-06（后端补齐 + 浏览器 E2E）

- `RedirectToSetup` 接入 `UpdateRunner`：版本落后时请求期执行
  procedures → 版本号 → 迁移 → `install.lock` → 清缓存；并发锁 + 30s 失败退避。
- `UpdateRunner` 抽成 CLI 与请求期共用实现。
- `SetupController` 安装时补跑 `DataMigrator`（import_options 等）。
- `--neoskin.command=install|bs:install` 兼容位置参数。
- Playwright 浏览器 E2E + CI `browser-e2e` job；四端并行验证通过。
- `MatrixSpringBootTest` 本机 WiltonDB（sqlsrv）通过。
- 全量测试：1086 用例，0 fail / 2 skip。

## 5. 下一步

1. 真 SQL Server CI 常态化（已有 job，观察稳定性）。
2. 多实例部署时补 Redis 共享会话/缓存示例。
3. 前端视觉回归（截图基线）与移动端走查。

---

## 8. 共享前端 P1（2026-11-06）

- 新增 `GET /api/bootstrap`（契约 v1）与 SPA 壳 `GET /app-next/{site,admin}`、`/app-next/{site,admin}/*`。
- `VertxRouteRegistry` 在 PHP 路由前注册 SPA 路由，并复用 web 组中间件（`EncryptCookies` + `StartSession`）；静态 handler 对 `/app-next/` 主动放行，避免直接返回未注入的 `index.html`。
- 壳注入 `<meta name="csrf-token">`、`<meta name="neoskin-mode">` 与 `#neoskin-bootstrap`；静态资源一年 immutable 缓存；缺失扩展名文件 404。
- 实现：`com.neoskin.web.SpaFrontend`、`VertxRouteRegistry`、`VertxHttpServer`、`WebConfig`。
- `.gitignore` 放开 `public/app-next`；构建产物已发布（含 `manifest.json`）。
- 联调验证：登录 → `/app-next/site/user` → `/app-next/admin/` 仪表盘，0 console error。
