# NeoSkinSpringBoot — 验收清单

> 最后更新：2026-11-06
> 基准：`blessing-skin-server-Laravel13`（6.0.11_patch，只读）

## 1. 路由

| 项 | 结果 |
| --- | --- |
| PHP 源路由 | 174 条 / 145 唯一路径 |
| Spring 注册 | 174 / 174（含源码漏记的 `POST skinlib`） |
| 未接通 | 0 |
| 注册方式 | `VertxRouteRegistry` 按 `routes.json` + 源码补齐 |

## 2. 数据与兼容性

| 项 | 结果 |
| --- | --- |
| 表 | 17 / 17（16 业务 + `migrations`） |
| 字段/主键 | `uid` / `pid` / `tid`；无符号语义按方言映射 |
| 密码 | bcrypt `$2y$` / `$2a$` 验证通过 |
| 纹理 | SHA-256 hash |
| 时间 | `Y-m-d H:i:s`，Asia/Shanghai |
| 数据迁移 | import_options / max_texture_width / create_communications / hash_oauth_client_secrets |

## 3. 认证与授权

- Session（file / redis / array）、CSRF、记住我、验证码、找回/重置/邮箱验证/绑定
- `auth:web`、`guest`、`role:*`、`verified`、`auth:oauth`、`scope:*`
- 插件 Filter：`client_ip`、`can_login`、`can_register`、`verify_password`、`user_password`

## 4. OAuth2（Passport 兼容）

- scopes / clients / tokens / personal access tokens
- `authorize` / `approve` / `deny`
- `POST /oauth/token`：`authorization_code`、`refresh_token`、`client_credentials`
- Bearer + scope 鉴权；refresh token 一次性
- 已知差异：令牌格式与 PHP 不完全相同（见 `PORT-NOTES.md`）

## 5. 视图 / 前端契约

- `window.blessing`、`#blessing-extra`、`#logout-button`、`data-mark="nickname"`
- `.locale`、`<main></main>`、`data-*` 钩子
- `/lang/<locale>.js` 由后端生成
- 86 个 Thymeleaf 模板；静态资源来自 `public/app`
- 浏览器 E2E：首页/登录/用户中心/资料/衣柜/皮肤库/上传/管理端/用户管理/更新页

## 6. 插件

- PHP 清单层：安装/启停/删除/市场
- 原生插件：PF4J Java 插件（`NeoSkinPlugin`）
- Hook 7 方法、Filter、24 Event
- PHP 插件代码不可执行（D-001）

## 7. 更新

- 检测更新源、ZIP 下载/上传/暂存/安装
- 请求期自动 `update`（`RedirectToSetup` → `UpdateRunner`）
- CLI：`--neoskin.command=update`

## 8. 测试与 CI

| 项 | 结果 |
| --- | --- |
| `mvn test` | 1086 用例，0 fail / 2 skip |
| 数据库矩阵 | `MatrixSpringBootTest`：sqlite/mysql/mariadb/pgsql/sqlsrv |
| 浏览器 E2E | `BrowserE2ETest` 通过 |
| CI | build/test、五库矩阵、browser-e2e |

## 9. 已知限制

1. PHP 插件不可执行；只支持 PF4J 原生插件。
2. 真 SQL Server 仅 CI；本机 WiltonDB 为协议替身。
3. 图像字节不保证与 PHP GD/Imagick 完全一致。
4. 缓存/会话多实例需 Redis；图片缓存仍是进程内。
5. 部分 Twig 语法用 Thymeleaf 等价写法，详见根 `PORT-NOTES.md`。
