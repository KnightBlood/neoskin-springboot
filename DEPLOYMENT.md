# NeoSkinSpringBoot — 部署指南

> 最后更新：2026-11-06

## 1. 构建

要求：JDK 21、Maven 3.9+。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'
mvn -B -ntp clean package -DskipTests
```

产物：`target/neoskin-springboot-6.0.11_patch-SNAPSHOT.jar`。

## 2. 配置（环境变量）

| 变量 | 说明 |
| --- | --- |
| `APP_KEY` | Laravel 兼容密钥（`base64:...`） |
| `APP_ENV` | `production` / `local` |
| `APP_DEBUG` | `true` / `false` |
| `APP_PORT` | HTTP 端口（默认 8080） |
| `DB_CONNECTION` | `sqlite` / `mysql` / `mariadb` / `pgsql` / `sqlsrv` |
| `DB_HOST` / `DB_PORT` / `DB_DATABASE` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接 |
| `DB_ENCRYPT` / `DB_TRUST_SERVER_CERTIFICATE` | SQL Server TLS 开关 |
| `STORAGE_PATH` | 存储根目录 |
| `TEXTURES_PATH` | 纹理目录 |
| `CACHE_DRIVER` / `SESSION_DRIVER` | `file` / `redis` / `array` |
| `MAIL_MAILER` | `smtp` / `log` |
| `UPDATE_SOURCE` / `PLUGINS_REGISTRY` | 更新源 / 插件市场 |

## 3. 安装与升级

首次安装：

```bash
java -jar neoskin-springboot-*.jar \
  --neoskin.command=install \
  --email=admin@example.com --password=secret123 --nickname=Admin
```

升级：

```bash
java -jar neoskin-springboot-*.jar --neoskin.command=update
```

请求期自动升级：已安装且 `options.version` 落后时，`RedirectToSetup` 会在
请求内执行同一套 `UpdateRunner`；也可手动执行上面的 CLI。

## 4. 前端静态资源

- 构建时默认从 `../blessing-skin-server-Laravel13/public/app` 复制到
  `target/classes/static/app`。
- 离线部署可用 `-Dfrontend.app.dir=<dir>` 指定目录。
- `/lang/<locale>.js` 由后端生成。

## 5. 启动

```bash
APP_PORT=8080 java -jar neoskin-springboot-*.jar
```

systemd 示例：

```ini
[Unit]
Description=NeoSkinSpringBoot
After=network.target

[Service]
WorkingDirectory=/srv/neoskin
ExecStart=/usr/bin/java -jar /srv/neoskin/neoskin-springboot.jar
Restart=always
Environment=APP_PORT=8080

[Install]
WantedBy=multi-user.target
```

## 6. 反向代理

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## 7. 从 PHP 迁移

1. 备份 PHP 站点与数据库。
2. 配置相同的 `APP_KEY` 与数据库连接。
3. 复制 `public/app/` 与 `storage/textures/`。
4. 执行 `--neoskin.command=update`（或首次启动让 `RedirectToSetup` 自动执行）。
5. 验证登录、皮肤库、衣柜、管理端、Minecraft `.json`。
6. 如使用插件，PHP 插件需按 D-001 重写为 PF4J 插件。

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动后页面 302 `/setup` | 缺少 `install.lock`；执行 install 或迁移 |
| sqlsrv 连接失败 | 检查 `DB_ENCRYPT` / `DB_TRUST_SERVER_CERTIFICATE` |
| PostgreSQL timestamp 写入失败 | 连接串需 `stringtype=unspecified` |
| 多实例会话不一致 | `SESSION_DRIVER=redis`、`CACHE_DRIVER=redis` |
| 更新后仍是旧版本 | 重启进程（运行中 JAR 不会被替换） |
