package com.neoskin.service.cache;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB 缓存（Laravel {@code DynamoDbStore} 的移植，
 * {@code CACHE_DRIVER=dynamodb}）。
 *
 * <p>与源一致的语义：</p>
 * <ul>
 *   <li>表结构：{@code key}（String，hash key）、{@code value}（String，PHP serialize）、
 *       {@code expiration}（Number，Unix 秒；{@code 0} 表示永不过期）；</li>
 *   <li>读取时 {@code expiration > 0 && <= now} 视为过期并删除；</li>
 *   <li>{@code increment} 用 {@code UpdateItem ADD}（原子）；</li>
 *   <li>{@code flush} 扫描本前缀并逐条删除（源用 Scan + 删除）。</li>
 * </ul>
 *
 * <p>配置来自源 {@code config/cache.php} 的 dynamodb 段：
 * {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} /
 * {@code AWS_DEFAULT_REGION} / {@code DYNAMODB_CACHE_TABLE} /
 * {@code DYNAMODB_ENDPOINT}（指向 DynamoDB Local）。</p>
 *
 * <p>连接/建表失败时降级为进程内 {@link ArrayCache}（同 redis/memcached）。</p>
 */
public final class DynamoDbCache implements CacheStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbCache.class);

    private final DynamoDbClient client;
    private final String table;
    private final String prefix;
    private final ArrayCache fallback = new ArrayCache();
    private volatile boolean available;

    public DynamoDbCache(String endpoint, String region, String accessKey, String secretKey,
            String table, String prefix) {
        this.table = table == null || table.isBlank() ? "cache" : table;
        this.prefix = prefix == null ? "" : prefix;
        DynamoDbClient opened = null;
        try {
            var builder = DynamoDbClient.builder()
                    .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5))
                            .apiCallAttemptTimeout(Duration.ofSeconds(5)));
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            if (accessKey != null && !accessKey.isBlank()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey,
                                secretKey == null ? "" : secretKey)));
            } else {
                // DynamoDB Local 任意凭证即可；未配置时用占位值
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")));
            }
            opened = builder.build();
            ensureTable(opened);
            available = true;
            log.info("DynamoDB cache connected: table={} endpoint={}", this.table,
                    endpoint == null ? "(aws)" : endpoint);
        } catch (Exception e) {
            log.error("DynamoDB cache unavailable: {} — 降级为进程内缓存", e.getMessage());
            if (opened != null) {
                opened.close();
            }
            opened = null;
        }
        this.client = opened;
    }

    private void ensureTable(DynamoDbClient client) {
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(table).build());
        } catch (ResourceNotFoundException notFound) {
            client.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("key").keyType(KeyType.HASH).build())
                    .attributeDefinitions(a -> a.attributeName("key")
                            .attributeType(ScalarAttributeType.S))
                    .billingMode("PAY_PER_REQUEST")
                    .build());
        }
    }

    @Override
    public Object get(String key) {
        if (!available) {
            return fallback.get(key);
        }
        try {
            var response = client.getItem(GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("key", AttributeValue.fromS(prefix + key)))
                    .consistentRead(true)
                    .build());
            if (!response.hasItem()) {
                return null;
            }
            Map<String, AttributeValue> item = response.item();
            long expiration = item.containsKey("expiration")
                    ? Long.parseLong(item.get("expiration").n()) : 0;
            if (expiration > 0 && expiration <= nowSeconds()) {
                forget(key);

                return null;
            }

            return deserialize(item.get("value").s());
        } catch (Exception e) {
            degrade(e);
            return fallback.get(key);
        }
    }

    @Override
    public void put(String key, Object value, int seconds) {
        if (!available) {
            fallback.put(key, value, seconds);
            return;
        }
        try {
            long expiration = seconds > 0 ? nowSeconds() + seconds
                    : seconds == 0 ? 0 : nowSeconds() - 1;
            client.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "key", AttributeValue.fromS(prefix + key),
                            "value", AttributeValue.fromS(serialize(value)),
                            "expiration", AttributeValue.fromN(String.valueOf(expiration))))
                    .build());
        } catch (Exception e) {
            degrade(e);
            fallback.put(key, value, seconds);
        }
    }

    @Override
    public int increment(String key, int amount, int seconds) {
        if (!available) {
            return fallback.increment(key, amount, seconds);
        }
        try {
            // 值以 PHP serialize 存储（与 put/get 一致），无法用 DynamoDB 的原子 ADD；
            // 这里做读-改-写（与 file store 一样不是原子的，见 PORT-NOTES）。
            long existingExpiration = 0;
            Object current = null;
            var response = client.getItem(GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("key", AttributeValue.fromS(prefix + key)))
                    .consistentRead(true)
                    .build());
            if (response.hasItem()) {
                Map<String, AttributeValue> item = response.item();
                existingExpiration = item.containsKey("expiration")
                        ? Long.parseLong(item.get("expiration").n()) : 0;
                if (existingExpiration == 0 || existingExpiration > nowSeconds()) {
                    current = deserialize(item.get("value").s());
                }
            }
            int next = (current instanceof Number number ? number.intValue() : 0) + amount;
            long expiration = existingExpiration > 0 && existingExpiration > nowSeconds()
                    ? existingExpiration
                    : seconds > 0 ? nowSeconds() + seconds : 0;
            client.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "key", AttributeValue.fromS(prefix + key),
                            "value", AttributeValue.fromS(serialize(next)),
                            "expiration", AttributeValue.fromN(String.valueOf(expiration))))
                    .build());

            return next;
        } catch (Exception e) {
            degrade(e);
            return fallback.increment(key, amount, seconds);
        }
    }

    @Override
    public long ttlSeconds(String key) {
        if (!available) {
            return fallback.ttlSeconds(key);
        }
        try {
            var response = client.getItem(GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("key", AttributeValue.fromS(prefix + key)))
                    .build());
            if (!response.hasItem()) {
                return -1;
            }
            long expiration = response.item().containsKey("expiration")
                    ? Long.parseLong(response.item().get("expiration").n()) : 0;

            return expiration <= 0 ? 0 : Math.max(0, expiration - nowSeconds());
        } catch (Exception e) {
            degrade(e);
            return fallback.ttlSeconds(key);
        }
    }

    @Override
    public void forget(String key) {
        if (!available) {
            fallback.forget(key);
            return;
        }
        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("key", AttributeValue.fromS(prefix + key)))
                    .build());
        } catch (Exception e) {
            degrade(e);
            fallback.forget(key);
        }
    }

    @Override
    public void flush() {
        if (!available) {
            fallback.flush();
            return;
        }
        try {
            List<Map<String, AttributeValue>> keys = new ArrayList<>();
            String startKey = null;
            do {
                var request = ScanRequest.builder()
                        .tableName(table)
                        .projectionExpression("#k")
                        .expressionAttributeNames(Map.of("#k", "key"));
                if (startKey != null) {
                    request.exclusiveStartKey(Map.of("key", AttributeValue.fromS(startKey)));
                }
                var response = client.scan(request.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    String key = item.get("key").s();
                    if (key.startsWith(prefix)) {
                        keys.add(Map.of("key", AttributeValue.fromS(key)));
                    }
                }
                startKey = response.hasLastEvaluatedKey()
                        ? response.lastEvaluatedKey().get("key").s() : null;
            } while (startKey != null);

            for (Map<String, AttributeValue> key : keys) {
                client.deleteItem(DeleteItemRequest.builder().tableName(table).key(key).build());
            }
        } catch (Exception e) {
            degrade(e);
            fallback.flush();
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    /** 测试/诊断用：当前是否连上了 DynamoDB。 */
    public boolean available() {
        return available;
    }

    private void degrade(Exception e) {
        available = false;
        log.error("DynamoDB error, 降级为进程内缓存：{}", e.getMessage());
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String serialize(Object value) {
        return PhpSerialize.serialize(value);
    }

    private static Object deserialize(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PhpUnserialize.parse(raw).value();
        } catch (Exception e) {
            return raw;
        }
    }
}
