package com.neoskin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NeoSkin 运行时配置。
 *
 * <p>字段与源项目 {@code blessing-skin-server-Laravel13} 的 {@code .env} 变量一一对应，
 * 默认值取自 {@code .env.example} / {@code config/*.php}，保证「不改变 .env 运行时行为」
 * （AGENTS.md §4.3）。所有配置项都可以用同名环境变量覆盖，运维脚本无需改动。
 *
 * <p>这里使用可变 POJO 而不是 record，因为 Spring Boot 的宽松绑定对嵌套 record 支持有限，
 * 而且后续需要在测试中直接 set 值。
 */
@ConfigurationProperties(prefix = "neoskin")
public class NeoSkinSettings {

    /** APP_ENV，源项目默认 production。 */
    private String env = "production";

    /** APP_DEBUG。 */
    private boolean debug = false;

    /** APP_KEY，AES-256-CBC 密钥。 */
    private String key = "";

    /** APP_FALLBACK_LOCALE。 */
    private String fallbackLocale = "en";

    /** config/app.php 的 version。 */
    private String version = "6.0.11_patch";

    /** config/app.php 的 update_source。 */
    private String updateSource = "";
    private Updates updates = new Updates();
    /** config/app.php 的 timezone，源项目固定 Asia/Shanghai。 */
    private String timezone = "Asia/Shanghai";

    private Database database = new Database();

    private Http http = new Http();

    private Session session = new Session();

    private Cache cache = new Cache();

    private Secure secure = new Secure();

    private Storage storage = new Storage();

    private Plugins plugins = new Plugins();

    private Mail mail = new Mail();

    private Hashing hashing = new Hashing();

    // ------------------------------------------------------------------ 嵌套配置

    /** 对应 config/database.php。 */
    public static class Database {
        /** DB_CONNECTION：sqlite / mysql / mariadb / pgsql / sqlsrv。 */
        private String connection = "mysql";
        /** DATABASE_URL，形如 mysql://user:pass@host:3306/db。 */
        private String url = "";
        private String host = "127.0.0.1";
        private String port = "";
        private String name = "blessingskin";
        private String username = "forge";
        private String password = "";
        /** DB_PREFIX，Laravel 的表前缀。 */
        private String prefix = "";
        /** DB_SOCKET，MySQL unix socket。 */
        private String socket = "";
        /** MYSQL_ATTR_SSL_CA。 */
        private String sslCa = "";
        /** DB_FOREIGN_KEYS，SQLite 外键约束开关。 */
        private boolean foreignKeys = true;
        /** DB_ENCRYPT，SQL Server 是否加密连接（mssql-jdbc 12+ 默认 true）。 */
        private boolean encrypt = true;
        /** DB_TRUST_SERVER_CERTIFICATE，SQL Server 是否信任自签证书。 */
        private boolean trustServerCertificate = false;

        public String getConnection() { return connection; }
        public void setConnection(String v) { this.connection = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public String getPort() { return port; }
        public void setPort(String v) { this.port = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String v) { this.prefix = v; }
        public String getSocket() { return socket; }
        public void setSocket(String v) { this.socket = v; }
        public String getSslCa() { return sslCa; }
        public void setSslCa(String v) { this.sslCa = v; }
        public boolean isForeignKeys() { return foreignKeys; }
        public void setForeignKeys(boolean v) { this.foreignKeys = v; }
        public boolean isEncrypt() { return encrypt; }
        public void setEncrypt(boolean v) { this.encrypt = v; }
        public boolean isTrustServerCertificate() { return trustServerCertificate; }
        public void setTrustServerCertificate(boolean v) { this.trustServerCertificate = v; }
    }

    /** HTTP 监听配置（对应 Laravel 的 php-fpm / apache 监听）。 */
    public static class Http {
        private String host = "0.0.0.0";
        private int port = 8080;
        /** 请求体上限（MB），语义等同 PHP 的 post_max_size。 */
        private int maxBodySize = 64;
        /** Vert.x 工作线程池大小，0 表示使用 Vert.x 默认值（20 * CPU）。 */
        private int workerPoolSize = 0;

        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public int getMaxBodySize() { return maxBodySize; }
        public void setMaxBodySize(int v) { this.maxBodySize = v; }
        public int getWorkerPoolSize() { return workerPoolSize; }
        public void setWorkerPoolSize(int v) { this.workerPoolSize = v; }
    }

    /** 会话配置，对应 config/session.php。 */
    public static class Session {
        /** SESSION_COOKIE，源项目显式固定为 BS_SESSION。 */
        private String cookie = "BS_SESSION";
        private long lifetimeMinutes = 120;
        private String path = "/";
        private String sameSite = "lax";
        private boolean secure = false;
        /** SESSION_DRIVER：file（默认）/ redis / array。 */
        private String driver = "file";

        public String getCookie() { return cookie; }
        public void setCookie(String v) { this.cookie = v; }
        public long getLifetimeMinutes() { return lifetimeMinutes; }
        public void setLifetimeMinutes(long v) { this.lifetimeMinutes = v; }
        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }
        public String getSameSite() { return sameSite; }
        public void setSameSite(String v) { this.sameSite = v; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean v) { this.secure = v; }
        public String getDriver() { return driver; }
        public void setDriver(String v) { this.driver = v; }
    }

    /** 缓存配置，对应 config/cache.php 与 config/database.php 的 redis 段。 */
    public static class Cache {
        /** CACHE_PREFIX，源项目显式固定为 blessing_skin_cache_。 */
        private String prefix = "blessing_skin_cache_";
        /** CACHE_DRIVER：file / array / redis。 */
        private String driver = "file";
        private String directory = "storage/framework/cache";
        /** REDIS_HOST。 */
        private String redisHost = "127.0.0.1";
        /** REDIS_PORT。 */
        private int redisPort = 6379;
        /** REDIS_USERNAME（Redis 6 ACL，可空）。 */
        private String redisUsername = "";
        /** REDIS_PASSWORD（"null"/空 视为无密码）。 */
        private String redisPassword = "";
        /** REDIS_CACHE_DB（源 config/database.php 的 cache 连接，默认 1）。 */
        private int redisDatabase = 1;
        /** REDIS_URL（优先于 host/port/db；redis://[:pass@]host:port/db）。 */
        private String redisUrl = "";
        /** REDIS_PREFIX（连接级前缀，源默认 blessing_skin_database_）。 */
        private String redisPrefix = "";
        /** MEMCACHED_HOST。 */
        private String memcachedHost = "127.0.0.1";
        /** MEMCACHED_PORT。 */
        private int memcachedPort = 11211;
        /** DYNAMODB_ENDPOINT（空 = 真实 AWS；本地指向 DynamoDB Local）。 */
        private String dynamodbEndpoint = "";
        /** AWS_DEFAULT_REGION。 */
        private String dynamodbRegion = "us-east-1";
        /** DYNAMODB_CACHE_TABLE。 */
        private String dynamodbTable = "cache";
        /** AWS_ACCESS_KEY_ID。 */
        private String dynamodbAccessKey = "";
        /** AWS_SECRET_ACCESS_KEY。 */
        private String dynamodbSecretKey = "";

        public String getPrefix() { return prefix; }
        public void setPrefix(String v) { this.prefix = v; }
        public String getDriver() { return driver; }
        public void setDriver(String v) { this.driver = v; }
        public String getDirectory() { return directory; }
        public void setDirectory(String v) { this.directory = v; }
        public String getRedisHost() { return redisHost; }
        public void setRedisHost(String v) { this.redisHost = v; }
        public int getRedisPort() { return redisPort; }
        public void setRedisPort(int v) { this.redisPort = v; }
        public String getRedisUsername() { return redisUsername; }
        public void setRedisUsername(String v) { this.redisUsername = v; }
        public String getRedisPassword() { return redisPassword; }
        public void setRedisPassword(String v) { this.redisPassword = v; }
        public int getRedisDatabase() { return redisDatabase; }
        public void setRedisDatabase(int v) { this.redisDatabase = v; }
        public String getMemcachedHost() { return memcachedHost; }
        public void setMemcachedHost(String v) { this.memcachedHost = v; }
        public int getMemcachedPort() { return memcachedPort; }
        public void setMemcachedPort(int v) { this.memcachedPort = v; }
        public String getRedisUrl() { return redisUrl; }
        public void setRedisUrl(String v) { this.redisUrl = v; }
        public String getRedisPrefix() { return redisPrefix; }
        public void setRedisPrefix(String v) { this.redisPrefix = v; }
        public String getDynamodbEndpoint() { return dynamodbEndpoint; }
        public void setDynamodbEndpoint(String v) { this.dynamodbEndpoint = v; }
        public String getDynamodbRegion() { return dynamodbRegion; }
        public void setDynamodbRegion(String v) { this.dynamodbRegion = v; }
        public String getDynamodbTable() { return dynamodbTable; }
        public void setDynamodbTable(String v) { this.dynamodbTable = v; }
        public String getDynamodbAccessKey() { return dynamodbAccessKey; }
        public void setDynamodbAccessKey(String v) { this.dynamodbAccessKey = v; }
        public String getDynamodbSecretKey() { return dynamodbSecretKey; }
        public void setDynamodbSecretKey(String v) { this.dynamodbSecretKey = v; }
    }

    /** 安全配置，对应 config/secure.php。 */
    public static class Secure {
        /** PWD_METHOD，默认 BCRYPT。 */
        private String cipher = "BCRYPT";
        /** SALT，仅对 SALTED2* 系列算法生效。 */
        private String salt = "";

        public String getCipher() { return cipher; }
        public void setCipher(String v) { this.cipher = v; }
        public String getSalt() { return salt; }
        public void setSalt(String v) { this.salt = v; }
    }

    /** 存储路径。 */
    public static class Storage {
        /** Laravel storage_path() 的根目录。 */
        private String root = "storage";
        /** Storage::disk('textures') 的目录。 */
        private String textures = "storage/textures";
        /** FS_DRIVER：纹理盘驱动（local / s3）。 */
        private String fsDriver = "local";
        /** AWS_BUCKET（s3 盘的 bucket）。 */
        private String s3Bucket = "";
        /** AWS_ENDPOINT（自托管 S3：Silo / RustFS / MinIO）。 */
        private String s3Endpoint = "";
        /** AWS_DEFAULT_REGION。 */
        private String s3Region = "us-east-1";
        /** AWS_ACCESS_KEY_ID。 */
        private String s3AccessKey = "";
        /** AWS_SECRET_ACCESS_KEY。 */
        private String s3SecretKey = "";

        public String getRoot() { return root; }
        public void setRoot(String v) { this.root = v; }
        public String getTextures() { return textures; }
        public void setTextures(String v) { this.textures = v; }
        public String getFsDriver() { return fsDriver; }
        public void setFsDriver(String v) { this.fsDriver = v; }
        public String getS3Bucket() { return s3Bucket; }
        public void setS3Bucket(String v) { this.s3Bucket = v; }
        public String getS3Endpoint() { return s3Endpoint; }
        public void setS3Endpoint(String v) { this.s3Endpoint = v; }
        public String getS3Region() { return s3Region; }
        public void setS3Region(String v) { this.s3Region = v; }
        public String getS3AccessKey() { return s3AccessKey; }
        public void setS3AccessKey(String v) { this.s3AccessKey = v; }
        public String getS3SecretKey() { return s3SecretKey; }
        public void setS3SecretKey(String v) { this.s3SecretKey = v; }
    }

    /** config/updates.php：运行包更新相关配置。 */
    public static class Updates {
        /** SYSTEM_UPDATE_MAX_MB（默认 256）。 */
        private int maxUploadMb = 256;

        public int getMaxUploadMb() { return maxUploadMb; }
        public void setMaxUploadMb(int v) { this.maxUploadMb = v; }
    }

    /** 插件配置。 */
    public static class Plugins {
        private String directory = "plugins";
        private String url = "";
        private String registry = "";
        /** 对应 PLUGINS_JAVA_DIR：PF4J Java 插件目录（与 PHP 插件目录分开）。 */
        private String javaDirectory = "plugins-java";
        /** PLUGINS_JAVA_ENABLED。 */
        private boolean javaEnabled = true;

        public String getDirectory() { return directory; }
        public void setDirectory(String v) { this.directory = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public String getRegistry() { return registry; }
        public void setRegistry(String v) { this.registry = v; }
        public String getJavaDirectory() { return javaDirectory; }
        public void setJavaDirectory(String v) { this.javaDirectory = v; }
        public boolean isJavaEnabled() { return javaEnabled; }
        public void setJavaEnabled(boolean v) { this.javaEnabled = v; }
    }

    /** 邮件配置。 */
    public static class Mail {
        private String mailer = "smtp";
        private String host = "";
        private int port = 465;
        private String username = "";
        private String password = "";
        private String encryption = "";
        private String fromAddress = "";
        private String fromName = "";

        public String getMailer() { return mailer; }
        public void setMailer(String v) { this.mailer = v; }
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public String getEncryption() { return encryption; }
        public void setEncryption(String v) { this.encryption = v; }
        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String v) { this.fromAddress = v; }
        public String getFromName() { return fromName; }
        public void setFromName(String v) { this.fromName = v; }
    }

    /**
     * 哈希配置，对应源项目两个<b>互相独立</b>的 bcrypt 成本来源。
     *
     * <p>这是移植时最容易踩的坑：源项目里「用户密码」和「OAuth 客户端密钥」
     * 走的是两套完全不同的代码路径，成本因子也不同。
     *
     * <table border="1">
     *   <caption>两套 bcrypt 成本来源</caption>
     *   <tr><th>用途</th><th>源实现</th><th>成本</th></tr>
     *   <tr>
     *     <td>用户密码（{@code users.password}）</td>
     *     <td>{@code App\Services\Cipher\BCRYPT} → {@code password_hash($v, PASSWORD_BCRYPT)}
     *         <b>不传 options</b></td>
     *     <td>PHP 默认值，8.4 起为 <b>12</b></td>
     *   </tr>
     *   <tr>
     *     <td>OAuth 客户端密钥（{@code oauth_clients.secret}）</td>
     *     <td>Laravel {@code Hash::make()} → {@code config('hashing.bcrypt.rounds')}
     *         = {@code env('BCRYPT_ROUNDS', 10)}</td>
     *     <td><b>10</b></td>
     *   </tr>
     * </table>
     *
     * <p>两者必须分开配置：如果统一用 12，{@code Hash::needsRehash()} 的判断会与 PHP 版
     * 不一致，已经哈希过的 OAuth 密钥会被反复重算（虽然不影响登录，但会造成无意义的
     * 数据库写入，且与 {@code php artisan passport:hash} 的结果不同）。
     */
    public static class Hashing {
        /**
         * 用户密码的 bcrypt 成本，对应 PHP 8.4+ 的 {@code PASSWORD_BCRYPT} 默认值。
         *
         * <p>源项目没有通过 {@code BCRYPT_ROUNDS} 控制它——{@code config/hashing.php} 的
         * {@code rounds} 只被 {@code Hash} 门面使用。所以这里的环境变量名叫
         * {@code NEOSKIN_PASSWORD_BCRYPT_ROUNDS}，避免与 {@code BCRYPT_ROUNDS} 混淆。
         */
        private int passwordRounds = 12;

        /**
         * OAuth 客户端密钥的 bcrypt 成本，对应 {@code config/hashing.php} 的
         * {@code bcrypt.rounds}，即 {@code env('BCRYPT_ROUNDS', 10)}。
         */
        private int clientSecretRounds = 10;

        public int getPasswordRounds() { return passwordRounds; }
        public void setPasswordRounds(int v) { this.passwordRounds = v; }
        public int getClientSecretRounds() { return clientSecretRounds; }
        public void setClientSecretRounds(int v) { this.clientSecretRounds = v; }
    }

    // ------------------------------------------------------------------ 访问器

    public String getEnv() { return env; }
    public void setEnv(String v) { this.env = v; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean v) { this.debug = v; }
    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getFallbackLocale() { return fallbackLocale; }
    public void setFallbackLocale(String v) { this.fallbackLocale = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getUpdateSource() { return updateSource; }
    public void setUpdateSource(String v) { this.updateSource = v; }

    public Updates getUpdates() { return updates; }
    public void setUpdates(Updates v) { this.updates = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public Database getDatabase() { return database; }
    public void setDatabase(Database v) { this.database = v; }
    public Http getHttp() { return http; }
    public void setHttp(Http v) { this.http = v; }
    public Session getSession() { return session; }
    public void setSession(Session v) { this.session = v; }
    public Cache getCache() { return cache; }
    public void setCache(Cache v) { this.cache = v; }
    public Secure getSecure() { return secure; }
    public void setSecure(Secure v) { this.secure = v; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage v) { this.storage = v; }
    public Plugins getPlugins() { return plugins; }
    public void setPlugins(Plugins v) { this.plugins = v; }
    public Mail getMail() { return mail; }
    public void setMail(Mail v) { this.mail = v; }
    public Hashing getHashing() { return hashing; }
    public void setHashing(Hashing v) { this.hashing = v; }
}
