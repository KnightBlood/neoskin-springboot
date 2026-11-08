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

---

## 9. 共享前端 P3（2026-11-07）

- 新增用户中心契约端点，挂在 `SpaFrontend`（PHP 路由表之前）：
  - `GET /api/user/inbox?page=&per_page=`：分页 + `unread`，条目含 `preview`/`format`/`communication_id`；
  - `GET /api/user/inbox/{id}`：`{code,data}`，自动已读；
  - `GET /api/user/reports?page=&per_page=`：举报分页，含 `texture_name`。
- 实现：`com.neoskin.web.SpaFrontend`（contractApi/分页/预览）、
  `com.neoskin.db.NotificationRepository`（`countUnread`）、`com.neoskin.config.WebConfig`（装配）。
- 验证：`mvn -DskipTests compile` 通过；`mvn test` 1086 用例 / 0 fail / 2 skip；CI 验证待推送。

### 2026-11-07（P4 增量）
- 新增 `GET /api/admin/dashboard`：用户/角色/材质数量与存储空间。
- 实现：`com.neoskin.web.SpaFrontend`（管理员校验 + 统计）、`WebConfig` 注入 `PlayerRepository`。
- 验证：`mvn -DskipTests compile` 通过；`mvn test` 1086 用例 / 0 fail / 2 skip。

- `POST /admin/i18n`：当 `Accept: application/json` 时返回 `{code:0,message}`（共享前端直接调用），否则保持原 302 跳转。

- 新增 `GET|PUT /api/admin/options/{group}`（`options|customize|score|resource`）：返回/保存站点配置、配色、积分、资源选项，供管理端 SPA 使用；布尔项统一 `"true"/"false"`。

- 新增 `GET /api/admin/communications`、`GET /api/admin/communications/{id}`（列表 / 编辑器数据）；`POST|PUT /admin/communications*` 与撤回在 `Accept: application/json` 时返回 JSON（否则保持 302）。

- 管理端 SPA 通过请求头 `X-Neoskin-Spa: 1` 标识；`POST|PUT /admin/communications*` 与撤回据此返回 JSON，其他请求保持 302（不改变 legacy 行为）。

- 新增 `GET /api/admin/update/info`：返回 `latest/current/error/can_update/package/upload_limits`，管理端更新页直接展示最新版本与可更新状态。

### 2026-11-08（bootstrap 开关 + 验证门禁确认）

- `com.neoskin.web.SpaFrontend` 的 bootstrap options 增加 `allow_downloading_texture`、
  `require_verification`、`player_name_length_min/max`、`user_initial_score`、`return_score`、
  `sign_after_zero`、`sign_gap_time`、`reporter_score_modification`、`reporter_reward_score`。
- `verified` 门禁已由 `CheckUserVerified` 中间件覆盖 `/user/player*`、`/user/oauth/manage`、
  `/texture*`、`/skinlib/upload`、`POST /skinlib`、`POST /skinlib/report`；`/api/*` 不门禁，
  本轮补充回归验证。
- 174 条 PHP 路由保持不变。
- 验证：`mvn test` 1086 用例，0 fail，2 skipped。
- 共享前端产物已同步到 `public/app-next`（site `index-pnUYroLe.js` / admin `index-DOqbNUvq.js`）。

### 2026-11-08（P6 切换验收，Spring 本机）

- `FRONTEND_MODE=next`：`GET /` → 302 `/app-next/site/`，`GET /admin` → 302 `/app-next/admin/`，
  两个 SPA 壳与 `/api/bootstrap` 均 200；站点/管理端页面 0 console error。
- `FRONTEND_MODE=legacy` 回滚：`GET /` 返回 legacy 页面（含 `#blessing-extra`），
  `/app-next/site/` 仍可访问。
- Playwright 浏览器 E2E（`NEOSKIN_BROWSER_E2E=1`）通过。
- 修复契约端点未挂载：`VertxRouteRegistry` 只注册了 `/api/bootstrap`，导致
  `/api/user/inbox`、`/api/user/reports`、`/api/admin/*` 契约读取端点返回 404；
  现按 GET 显式注册在 174 条路由之前，PUT/POST 仍落到原路由表。
- `contract-check.mjs` 通过（`http://127.0.0.1:8799`）。
- 回归：`mvn test` 1086 用例，0 fail，2 skipped（修复后复跑）。

### 2026-11-08（NeoSkin 品牌与发布）

- 默认站点名/描述/公告、安装向导与更新包提示改为 NeoSkin；版权选项与页脚署名保留
  Blessing Skin Server 上游归属（见 `PORT-NOTES.md`）。
- 新增 `.github/workflows/release.yml`：推送 `v*` tag 后在 GitHub runner 构建并发布
  `neoskin-springboot_<version>.zip`，包含 `neoskin.jar`（内嵌 legacy `public/app`）、`public/app-next` 与部署文档。
- 共享前端产物更新为 site `index-pnUYroLe.js` / admin `index-DOqbNUvq.js`。
- 验证：`mvn test` 1086 用例，0 fail / 2 skipped（品牌默认值断言已调整）。
