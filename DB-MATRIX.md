# NeoSkinSpringBoot — 数据库矩阵

> 最后更新：2026-11-06
> 结论：sqlite / mysql / mariadb / pgsql 本机通过；sqlsrv 本机 WiltonDB 通过，真 SQL Server 由 CI 覆盖。

## 1. 本机数据库

| 方言 | 端口 | 账号 | 备注 |
| --- | --- | --- | --- |
| SQLite | — | — | 临时文件 |
| MySQL | 3307 | `root` / 空 | 共享脚本 `tools/scripts/db.ps1` |
| MariaDB | 3308 | `root` / 空 | 同上 |
| PostgreSQL | 5433 | `postgres` / `123456` | 同上 |
| SQL Server（WiltonDB） | 1433 | `wilton` / `123456` | 需 `DB_ENCRYPT=false`、`DB_TRUST_SERVER_CERTIFICATE=true` |

## 2. 环境变量

| 变量 | 说明 |
| --- | --- |
| `DB_CONNECTION` | `sqlite` / `mysql` / `mariadb` / `pgsql` / `sqlsrv` |
| `DB_HOST` / `DB_PORT` / `DB_DATABASE` | 连接目标 |
| `DB_USERNAME` / `DB_PASSWORD` | 账号 |
| `DB_ENCRYPT` | SQL Server 加密，默认 true |
| `DB_TRUST_SERVER_CERTIFICATE` | 信任自签证书，WiltonDB 设 true |

## 3. 运行矩阵测试

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'

# SQLite
$env:DB_CONNECTION='sqlite'
mvn -B -ntp -Dtest=MatrixSpringBootTest test

# MySQL
$env:DB_CONNECTION='mysql'; $env:DB_PORT='3307'
$env:DB_DATABASE='blessing_skin'; $env:DB_USERNAME='root'; $env:DB_PASSWORD=''
mvn -B -ntp -Dtest=MatrixSpringBootTest test

# MariaDB
$env:DB_CONNECTION='mariadb'; $env:DB_PORT='3308'
$env:DB_DATABASE='blessing_skin'; $env:DB_USERNAME='root'; $env:DB_PASSWORD=''
mvn -B -ntp -Dtest=MatrixSpringBootTest test

# PostgreSQL
$env:DB_CONNECTION='pgsql'; $env:DB_PORT='5433'
$env:DB_DATABASE='blessing_skin'; $env:DB_USERNAME='postgres'; $env:DB_PASSWORD='123456'
mvn -B -ntp -Dtest=MatrixSpringBootTest test

# SQL Server（WiltonDB 替身）
$env:DB_CONNECTION='sqlsrv'; $env:DB_PORT='1433'
$env:DB_DATABASE='blessing_skin'; $env:DB_USERNAME='wilton'; $env:DB_PASSWORD='123456'
$env:DB_ENCRYPT='false'; $env:DB_TRUST_SERVER_CERTIFICATE='true'
mvn -B -ntp -Dtest=MatrixSpringBootTest test
```

## 4. 覆盖内容

- 17 表建表/补表
- 用户、玩家、纹理、衣柜插入/查询
- 纹理 `public` 保留字引用
- PostgreSQL timestamp 写入（`stringtype=unspecified`）
- `DbValues` 统一时间戳 JSON 格式
- 布尔/无符号 ID 方言映射

## 5. 已知差异

| 方言 | 差异 | 处理 |
| --- | --- | --- |
| PostgreSQL | `textures.public` 保留字 | `SqlDialect.quote()` |
| PostgreSQL | timestamp 写入需文本转换 | `stringtype=unspecified` |
| SQL Server | 本机仅 WiltonDB 协议替身 | 真 SQL Server 由 CI 覆盖 |
| 全方言 | 库名 `blessingskin` vs 共享脚本 `blessing_skin` | 矩阵显式设置 `DB_DATABASE` |

## 6. 结果

| 方言 | 本机 | CI |
| --- | --- | --- |
| SQLite | ✅ | ✅ |
| MySQL | ✅ | ✅ |
| MariaDB | ✅ | ✅ |
| PostgreSQL | ✅ | ✅ |
| SQL Server（WiltonDB） | ✅（2026-11-06） | ✅（真 SQL Server） |
