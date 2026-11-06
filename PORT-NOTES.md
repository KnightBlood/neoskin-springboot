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
