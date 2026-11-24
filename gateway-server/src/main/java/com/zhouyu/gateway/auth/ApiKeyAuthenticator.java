package com.zhouyu.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.quota.KeySpend;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * API Key 鉴权。
 *
 * <p>本地 Caffeine 缓存 sha256 → 视图，避免每请求查库；命中时零网络往返。
 * 失效由 TTL 兜底（30s），后续接入 Redis 失效广播可缩短到秒级。
 */
@Component
public class ApiKeyAuthenticator {

    private static final String BEARER = "Bearer ";

    private record Lookup(long version, String hash) {}

    private final Cache<Lookup, Optional<ApiKeyView>> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .recordStats()
            .build();

    private final RouteTableHolder tables;

    private record Minute(long number, int count) {}
    private record LastUse(long time, String ip) {}
    private final Cache<Long, Minute> minutes = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(2)).build();
    private final java.util.concurrent.ConcurrentMap<Long, LastUse> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KeySpend spend;

    public ApiKeyAuthenticator(RouteTableHolder tables, JdbcTemplate jdbc, TransactionTemplate transaction, KeySpend spend) {
        this.tables = tables;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.spend = spend;
    }

    /**
     * 从请求头提取并校验 Key。
     *
     * <p>支持 {@code Authorization: Bearer xxx}（OpenAI 风格）与
     * {@code x-api-key: xxx}（Anthropic 风格），前者优先。
     */
    public ApiKeyView authenticate(ServerHttpRequest request) {
        String secret = extractSecret(request);
        if (secret == null || secret.isBlank()) {
            throw new GatewayException(ErrorCode.INVALID_API_KEY, "缺少 API Key");
        }
        String hash = RouteTable.sha256(secret);
        // 缓存"查不到"的空结果，避免无效 Key 反复穿透
        RouteTable table = tables.table();
        Optional<ApiKeyView> cached = cache.get(new Lookup(table.version(), hash), entry -> table.findKeyHash(entry.hash()));
        ApiKeyView key = cached.orElseThrow(() -> new GatewayException(ErrorCode.INVALID_API_KEY, "API Key 无效"));
        if (key.expired()) throw new GatewayException(ErrorCode.INVALID_API_KEY, "API Key 已过期");
        var remote = request.getRemoteAddress();
        if (!key.ipRules().permits(remote)) throw new GatewayException(ErrorCode.IP_RESTRICTED);
        long now = System.currentTimeMillis();
        Integer rpm = key.rpmLimit();
        if (rpm != null && rpm > 0) {
            long number = now / 60_000;
            Minute minute = minutes.asMap().compute(key.id(), (id, previous) ->
                    previous == null || previous.number() != number ? new Minute(number, 1)
                            : previous.count() > rpm ? previous : new Minute(number, previous.count() + 1));
            if (minute.count() > rpm) throw new GatewayException(ErrorCode.KEY_RATE_LIMITED);
        }
        if (key.managed()) {
            String ip = remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress().split("%", 2)[0];
            pending.merge(key.id(), new LastUse(now, ip), (previous, next) -> previous.time() > next.time() ? previous : next);
        }
        return key;
    }

    public reactor.core.publisher.Mono<Void> checkSpend(ApiKeyView key) { return spend.check(key); }

    @Scheduled(fixedDelay = 3000)
    void flushLastUse() {
        if (pending.isEmpty()) return;
        var entries = new java.util.HashMap<>(pending);
        try {
            transaction.executeWithoutResult(ignored -> {
                for (var entry : entries.entrySet()) {
                    var seen = entry.getValue();
                    var timestamp = new java.sql.Timestamp(seen.time());
                    jdbc.update("""
                            UPDATE api_key k JOIN gateway_client_key m ON m.key_id=k.id
                            SET k.last_used_at=?,m.last_used_ip=? WHERE k.id=? AND (k.last_used_at IS NULL OR k.last_used_at<=?)
                            """, timestamp, seen.ip(), entry.getKey(), timestamp);
                }
            });
            entries.forEach(pending::remove);
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
            LoggerFactory.getLogger(ApiKeyAuthenticator.class).warn("记录密钥最近使用失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 主动失效某个 Key（管理端禁用/轮换时调用）。 */
    public void invalidate(String rawSecret) {
        String hash = RouteTable.sha256(rawSecret);
        cache.asMap().keySet().removeIf(entry -> entry.hash().equals(hash));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    private static String extractSecret(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER)) {
            return auth.substring(BEARER.length()).trim();
        }
        String apiKey = request.getHeaders().getFirst("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return null;
    }
}
