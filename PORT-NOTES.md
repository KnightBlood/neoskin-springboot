# NeoSkinSpringBoot — 端口偏差与决策

> 最后更新：2026-11-06
> 通用偏差的逐条细节在根目录 `../PORT-NOTES.md`（§6–§18）；本文件只保留
> Spring 端需要单独提醒的结论。

## 1. 模板

- 使用 Thymeleaf 替代 Twig；`shared/head`、`shared/foot` 等片段用
  `th:replace` / `th:insert` 实现。
- Twig 的 map 字面量、动态属性名、`block` 继承有少量语法差异；
  输出 HTML 保持 DOM 契约。
- 模板数量：86 个（源 104 个 Twig；表单由 `OptionForm` 生成、`assets/*` 内联、
  错误页合并）。

## 2. 认证 / 会话 / 缓存

- Session 支持 `file`（默认，与 PHP 同格式）/ `redis` / `array`。
- 缓存支持 `file` / `redis` / `array`；图片缓存为进程内。
- 验证码为本地图片 + reCAPTCHA 校验；`niceize` 规则与 PHP 对齐。
- 可信代理白名单（Whip）未完整移植；IP 取 `X-Forwarded-For` 首段后回退 TCP 对端。

## 3. OAuth2 / Passport

- 支持 `authorization_code`、`refresh_token`、`client_credentials`；
  `password` grant 按 PHP 默认保持关闭。
- access token 使用 JWT；refresh token 存储与 PHP 不完全相同。
- `laravel_token` transient cookie 的加密/claims 与 PHP 有差异（调用方应使用
  Bearer token 或 web session）。

## 4. 图像

- 皮肤/披风/头像渲染移植自 Blessing Renderer；`?3d` 已支持。
- WebP 由 `webp-imageio` 编码；`?png` 可强制 PNG。
- 输出字节不保证与 PHP GD/Imagick 完全一致（尺寸、语义一致）。

## 5. 更新

- `UpdatePackage` 支持 ZIP 校验/暂存/安装。
- 请求期自动升级已接线：`RedirectToSetup` → `UpdateRunner`
  （procedures → 版本号 → 迁移 → `install.lock` → 清缓存），
  并发锁 + 30 秒失败退避。
- 运行中替换 JAR 需要重启；CLI 仍可显式 `--neoskin.command=update`。

## 6. 数据库

- sqlite / mysql / mariadb / pgsql 本机 + CI；sqlsrv 本机用 WiltonDB，
  真 SQL Server 由 CI 覆盖。
- PostgreSQL 连接串需 `stringtype=unspecified`（写 timestamp 列）。
- `textures.public` 等保留字统一走 `SqlDialect.quote()`。
- 本地矩阵库名用 `blessing_skin`（共享脚本创建），默认配置为 `blessingskin`。

## 7. 错误页 / 校验

- 错误页分流与 PHP `Handler::render()` 对齐；完整字节 diff 不做。
- Validator 实现被用到的规则子集；未实现规则抛异常而不是静默放行。
- 邮件发送始终走真实 SMTP（测试只覆盖不触网分支）。

## 9. 共享前端新增契约端点（2026-11-07）

- 为共享前端 P3 增加 3 个**站内信/举报 JSON** 端点（PHP 原版只有 HTML 页面）：
  `GET /api/user/inbox`、`GET /api/user/inbox/{id}`、`GET /api/user/reports`。
- 这些是 NeoSkinFrontend 契约 v1 的增量端点，不计入 PHP 174 条路由；
  由 `com.neoskin.web.SpaFrontend` 在 PHP 路由表之前处理。
- 返回结构与 `NeoSkinFrontend/docs/BACKEND-CONTRACT.md` §7.2 一致；
  匿名访问返回 `401 application/json`。

### P4 增量端点（2026-11-07）

- 新增 GET /api/admin/dashboard（管理端统计：用户/角色/材质/存储空间），
  属于 NeoSkinFrontend 契约 v1 增量端点，不计入 PHP 174 条路由；`com.neoskin.web.SpaFrontend` 在 PHP 路由表之前处理，校验 `permission >= 1`。
- 匿名 401、非管理员 403；图表仍复用既有 GET /admin/chart。


- `POST /admin/i18n` 增加 JSON 响应分支（`Accept: application/json` → `{code,message}`；否则 302），供管理端 SPA 使用。

- 新增 `GET|PUT /api/admin/options/{group}`（`options|customize|score|resource`）：返回/保存站点配置、配色、积分、资源选项，供管理端 SPA 使用；布尔项统一 `"true"/"false"`。

- 新增 `GET /api/admin/communications`、`GET /api/admin/communications/{id}`（列表 / 编辑器数据）；`POST|PUT /admin/communications*` 与撤回在 `Accept: application/json` 时返回 JSON（否则保持 302）。

- 管理端 SPA 通过请求头 `X-Neoskin-Spa: 1` 标识；`POST|PUT /admin/communications*` 与撤回据此返回 JSON，其他请求保持 302（不改变 legacy 行为）。

- 新增 `GET /api/admin/update/info`：返回 `latest/current/error/can_update/package/upload_limits`，管理端更新页直接展示最新版本与可更新状态。

### 邮箱验证与 bootstrap 开关（2026-11-08）

- `verified` 门禁沿用 `CheckUserVerified` 中间件（`MiddlewareChain` 的 `verified` 别名），
  `/user/player*`、`/user/oauth/manage`、`/texture*`、`/skinlib/upload`、`POST /skinlib`、
  `POST /skinlib/report` 已对齐；`/api/*` 不门禁。
- `com.neoskin.web.SpaFrontend` 的 bootstrap options 补齐：`allow_downloading_texture`、
  `require_verification`、`player_name_length_min/max`、`user_initial_score`、`return_score`、
  `sign_after_zero`、`sign_gap_time`、`reporter_score_modification`、`reporter_reward_score`。
- 无需新增路由或中间件，174 条 PHP 路由保持不变。

### 契约端点挂载修复（2026-11-08）

- 问题：`VertxRouteRegistry.build` 只把 `/api/bootstrap` 交给 `SpaFrontend`，
  `GET /api/user/inbox`、`/api/user/reports`、`/api/admin/dashboard` 等契约端点
  落到路由表兜底后返回 404。
- 修复：在 174 条路由注册**之前**，按 GET 显式注册
  `/api/user/inbox`、`/api/user/inbox/:id`、`/api/user/reports`、
  `/api/admin/dashboard`、`/api/admin/update/info`、
  `/api/admin/options/:group`、`/api/admin/communications`、
  `/api/admin/communications/:id` 到 `SpaFrontend`。
- `SpaFrontend.isContractPath` 未命中的路径会 `ctx.next()`，PUT/POST 仍由原路由表处理；
  174 条路由数量与语义不变。`contract-check.mjs` 已验证通过。

### NeoSkin 品牌默认值（2026-11-08）

- 有意偏离 PHP 默认值：`site_name=NeoSkin`、
  `site_description=NeoSkin Minecraft Skin Hosting Service`、
  `announcement=Welcome to NeoSkin {version}!`。
- 安装向导与更新提示中的产品名改为 NeoSkin。
- 保留上游归属与兼容标识：`copyright_prefer` 的 7 条文案、页脚链接、
  `blessing-*` 路由/字段/cookie/模板钩子均不变。
- SPA 更新安装：`POST /admin/update/install` 在 `Accept: application/json` 或
  `X-Neoskin-Spa: 1` 时返回 `{code,message}`；未传 `id` 时使用会话中的待安装包 id。
  legacy 表单仍要求 32 位 id 并保持 302。

### 管理端运行状态内容协商（2026-11-08）

- `GET /admin/status` 在 `Accept: application/json` 或 `X-Neoskin-Spa: 1` 时返回
  `{bs,server,db,plugins}` JSON；普通 HTML 请求继续渲染 `admin/status` 模板。
- 用于共享管理端 SPA 状态页；四端联调时不再只显示 Go 端数据。
