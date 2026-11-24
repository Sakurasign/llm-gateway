package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.config.CredentialCipher;
import com.zhouyu.gateway.domain.AccountProxy;
import com.zhouyu.gateway.config.GatewayProperties;
import com.zhouyu.gateway.config.GatewayConfigLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AccountPool {
    public static final String WORKBUDDY = "WORKBUDDY";
    public static final String CODEX = "CODEX_SUBSCRIPTION";
    public static final String ANTHROPIC = "ANTHROPIC";
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final ObjectMapper mapper;
    private final GatewayProperties config;
    private final AccountAuthorization authorization;
    private final AccountProxies proxies;
    private final int defaultConcurrency;
    private final boolean checkInEnabled;
    private final AsyncLoadingCache<Long, List<CodexAccountStatus.Window>> codexWindows = Caffeine.newBuilder()
            .maximumSize(5_000).expireAfterWrite(Duration.ofMinutes(2))
            .buildAsync((id, executor) -> Mono.fromCallable(() -> refreshCodexWindows(id))
                    .subscribeOn(Schedulers.boundedElastic()).onErrorReturn(List.of()).toFuture());
    private final Map<Long, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public AccountPool(JdbcTemplate jdbc, CredentialCipher cipher, ObjectMapper mapper, GatewayProperties config,
                       AccountAuthorization authorization, AccountProxies proxies, @Value("${gateway.accounts.max-inflight:4}") int maxInflight,
                       @Value("${GATEWAY_WORKBUDDY_CHECKIN:false}") boolean checkInEnabled) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
        this.config = config;
        this.authorization = authorization;
        this.proxies = proxies;
        this.defaultConcurrency = Math.max(1, maxInflight);
        this.checkInEnabled = checkInEnabled;
    }

    @Bean
    @org.springframework.core.annotation.Order(0)
    ApplicationRunner accountSchema() {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gateway_upstream_account (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      provider VARCHAR(24) NOT NULL, external_id VARCHAR(191) NOT NULL,
                      name VARCHAR(128) NOT NULL, realm VARCHAR(12) NOT NULL,
                      credential MEDIUMBLOB NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
                      expires_at BIGINT NOT NULL, cooldown_until BIGINT NOT NULL DEFAULT 0,
                      last_error VARCHAR(64), requests BIGINT NOT NULL DEFAULT 0,
                      failures BIGINT NOT NULL DEFAULT 0, input_tokens BIGINT NOT NULL DEFAULT 0,
                      output_tokens BIGINT NOT NULL DEFAULT 0, credits BIGINT,
                      UNIQUE KEY uk_account (provider,external_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            var columns = jdbc.queryForList("""
                    SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gateway_upstream_account'
                    """, String.class);
            if (!columns.contains("schedulable")) jdbc.execute("ALTER TABLE gateway_upstream_account ADD schedulable BOOLEAN NOT NULL DEFAULT TRUE");
            if (!columns.contains("notes")) jdbc.execute("ALTER TABLE gateway_upstream_account ADD notes VARCHAR(512)");
            if (!columns.contains("proxy_id")) jdbc.execute("ALTER TABLE gateway_upstream_account ADD proxy_id BIGINT");
            proxies.initialize();
            if (!columns.contains("last_used_at")) jdbc.execute("ALTER TABLE gateway_upstream_account ADD last_used_at BIGINT");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gateway_account_channel (
                      account_id BIGINT PRIMARY KEY, channel_id BIGINT NOT NULL UNIQUE
                    ) ENGINE=InnoDB
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gateway_account_binding (
                      gateway_key_id BIGINT PRIMARY KEY, account_id BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gateway_account_model_cooldown (
                      account_id BIGINT NOT NULL, model VARCHAR(128) NOT NULL, until_epoch BIGINT NOT NULL,
                      PRIMARY KEY(account_id, model)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        };
    }

    public record Summary(long id, String provider, String name, String realm, boolean enabled, long expiresAt,
                          long cooldownUntil, String lastError, long requests, long failures, long inputTokens,
                          long outputTokens, Long credits, int inflight, String source, boolean schedulable, String notes, Long lastUsedAt, int priority, int mappedModels, int concurrency, Long proxyId) {}

    private record Account(long id, String provider, String externalId, String name, String realm, boolean enabled,
                           long expiresAt, long cooldown, byte[] secret, Long credits, boolean schedulable, Long proxyId) {}

    public record Registration(String provider, String externalId, String name, String realm, long expiresAt, ObjectNode credential) {}

    public List<Summary> summaries() {
        return jdbc.query("""
                SELECT u.id,u.provider,u.external_id,u.name,u.realm,u.enabled,u.expires_at,u.cooldown_until,u.last_error,u.requests,
                  u.failures,u.input_tokens,u.output_tokens,u.credits,u.schedulable,u.notes,u.last_used_at,u.proxy_id,COALESCE(c.priority,100) AS priority,
                  COALESCE(c.max_inflight, ?) AS concurrency,
                  (SELECT COUNT(*) FROM model_route r JOIN virtual_model m ON m.id=r.virtual_model_id
                   WHERE r.channel_id=c.id AND r.enabled=1 AND m.status=1) AS mapped_models
                FROM gateway_upstream_account u LEFT JOIN gateway_account_channel a ON a.account_id=u.id
                LEFT JOIN channel c ON c.id=a.channel_id ORDER BY u.provider,u.id
                """, (row, index) -> new Summary(row.getLong("id"), row.getString("provider"), row.getString("name"), row.getString("realm"),
                row.getBoolean("enabled"), row.getLong("expires_at"), row.getLong("cooldown_until"), row.getString("last_error"),
                row.getLong("requests"), row.getLong("failures"), row.getLong("input_tokens"), row.getLong("output_tokens"),
                row.getObject("credits", Long.class), inflight.getOrDefault(row.getLong("id"), new AtomicInteger()).get(),
                row.getString("external_id").startsWith("sub2:") ? Sub2ApiImport.SOURCE : "gateway", row.getBoolean("schedulable"), row.getString("notes"),
                row.getObject("last_used_at", Long.class), row.getInt("priority"), row.getInt("mapped_models"), row.getInt("concurrency"), row.getObject("proxy_id", Long.class)), defaultConcurrency);
    }

    public List<Map<String, Long>> bindings() {
        return jdbc.query("SELECT gateway_key_id,account_id FROM gateway_account_binding ORDER BY gateway_key_id",
                (row, index) -> Map.of("keyId", row.getLong(1), "accountId", row.getLong(2)));
    }

    public List<Long> keyIds() {
        return config.getKeys().stream().filter(key -> key.getSecret() != null && !key.getSecret().isBlank())
                .map(GatewayProperties.KeyConfig::getId).distinct().sorted().toList();
    }

    public long save(Registration account) {
        byte[] encrypted = cipher.encrypt(account.credential().toString(), scope(account.provider(), account.externalId()));
        jdbc.update("""
                INSERT INTO gateway_upstream_account(provider,external_id,name,realm,credential,expires_at)
                VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name), credential=VALUES(credential),
                expires_at=VALUES(expires_at), enabled=1, last_error=NULL, cooldown_until=0
                """, account.provider(), account.externalId(), limit(account.name(), 128), account.realm(), encrypted, account.expiresAt());
        return jdbc.queryForObject("SELECT id FROM gateway_upstream_account WHERE provider=? AND external_id=?",
                Long.class, account.provider(), account.externalId());
    }

    long saveApiKey(String provider, Long id, String name, String apiKey) {
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.isEmpty() || key.length() > 4096 || !key.chars().allMatch(c -> c > 32 && c < 127)) {
            throw problem(HttpStatus.BAD_REQUEST, "请输入有效的 API Key");
        }
        var credential = mapper.createObjectNode().put("api_key", key);
        if (id == null) {
            if (name == null || name.isBlank() || name.strip().length() > 128) {
                throw problem(HttpStatus.BAD_REQUEST, "请输入 1–128 字符的账号名称");
            }
            return save(new Registration(provider, java.util.UUID.randomUUID().toString(), name.strip(), "api", 0, credential));
        }
        synchronized (lock(id)) {
            var account = byId(id);
            if (!provider.equals(account.provider()) || !"api".equals(account.realm())) {
                throw problem(HttpStatus.BAD_REQUEST, "请选择相同平台的 API Key 账号");
            }
            byte[] encrypted = cipher.encrypt(credential.toString(), scope(account.provider(), account.externalId()));
            jdbc.update("UPDATE gateway_upstream_account SET credential=?, enabled=1, last_error=NULL, cooldown_until=0 WHERE id=?", encrypted, id);
            return id;
        }
    }

    long saveClaudeOAuth(Long id, Registration registration) {
        if (id != null && !byId(id).externalId().equals(registration.externalId())) {
            throw problem(HttpStatus.BAD_REQUEST, "请授权原来的 Claude 账号");
        }
        return save(registration);
    }

    long saveCodex(Long id, Registration next) {
        if (id == null) return save(next);
        synchronized (lock(id)) {
            Account account = byId(id);
            if (!CODEX.equals(account.provider()) || "api".equals(account.realm())) throw problem(HttpStatus.BAD_REQUEST, "请选择 ChatGPT 订阅账号");
            ObjectNode old = secret(account);
            if (old.path("codex_oauth").asBoolean()) {
                for (String key : List.of("chatgpt_account_id", "chatgpt_user_id")) {
                    if (old.hasNonNull(key) && !old.path(key).asText().isBlank()
                            && !old.path(key).asText().equals(next.credential().path(key).asText())) {
                        throw problem(HttpStatus.BAD_REQUEST, "请使用原 ChatGPT 账号和工作区重新授权");
                    }
                }
            }
            jdbc.update("""
                    UPDATE gateway_upstream_account SET credential=?, expires_at=?, enabled=1, last_error=NULL, cooldown_until=0 WHERE id=?
                    """, cipher.encrypt(next.credential().toString(), scope(account.provider(), account.externalId())), next.expiresAt(), id);
            return id;
        }
    }

    public Registration workbuddyImport(JsonNode body) {
        JsonNode auth = body.has("auth") ? body.path("auth") : body;
        JsonNode identity = body.has("account") ? body.path("account") : body;
        String access = auth.path("accessToken").asText();
        String refresh = auth.path("refreshToken").asText();
        String uid = identity.path("uid").asText();
        String domain = auth.path("domain").asText();
        String realm = auth.path("realm").asText(domain.endsWith("workbuddy.ai") ? "global" : "cn");
        if (!Set.of("cn", "global").contains(realm) || uid.isBlank() || uid.length() > 170 || access.isBlank()) {
            throw problem(HttpStatus.BAD_REQUEST, "需要有效的 WorkBuddy 授权 JSON、uid 和站点");
        }
        var secret = mapper.createObjectNode().put("access_token", access).put("refresh_token", refresh)
                .put("uid", uid).put("domain", domain).put("enterpriseId", identity.path("enterpriseId").asText())
                .put("device_token", body.path("device_token").asText());
        return new Registration(WORKBUDDY, realm + ":" + uid, identity.path("nickname").asText(uid), realm,
                auth.path("expiresAt").asLong(Instant.now().plusSeconds(3600).getEpochSecond()), secret);
    }

    public record ImportIssue(int row, String message) {}
    public record ImportResult(List<Long> ids, int skipped, List<ImportIssue> errors, List<ImportIssue> warnings) {}

    public ImportResult importCodexAccounts(JsonNode input, GatewayConfigLoader config) {
        boolean cockpit = CockpitImport.isFormat(input);
        JsonNode entries = cockpit ? CockpitImport.entries(mapper, input) : Sub2ApiImport.entries(input);
        var ids = new java.util.ArrayList<Long>();
        var errors = new java.util.ArrayList<ImportIssue>();
        var warnings = new java.util.ArrayList<ImportIssue>();
        int row = 0;
        int skipped = 0;
        var proxyEntries = new java.util.HashMap<String, JsonNode>();
        JsonNode source = cockpit ? mapper.missingNode() : input.has("data") ? input.path("data") : input;
        for (JsonNode proxy : source.path("proxies")) {
            String key = proxy.path("proxy_key").asText();
            if (!key.isBlank()) proxyEntries.put(key, proxy);
        }
        for (JsonNode item : entries) {
            row++;
            if (!(cockpit ? CockpitImport.supports(item) : Sub2ApiImport.supports(item))) {
                skipped++;
                continue;
            }
            try {
                var registration = cockpit ? CockpitImport.decode(mapper, item) : Sub2ApiImport.decode(mapper, item);
                JsonNode settings = cockpit ? mapper.missingNode() : item;
                var mappings = Sub2ApiImport.modelMappings(settings);
                String proxyKey = settings.path("proxy_key").asText();
                Long proxyId = null;
                if (!proxyKey.isBlank()) {
                    var proxy = proxyEntries.get(proxyKey);
                    if (proxy == null) throw problem(HttpStatus.BAD_REQUEST, "账号引用的代理未包含在导出数据中");
                    String status = proxy.path("status").asText("active");
                    long expiresAt = proxy.path("expires_at").asLong(0);
                    if ((!status.isBlank() && !"active".equals(status)) || (expiresAt > 0 && expiresAt <= Instant.now().getEpochSecond())) {
                        throw problem(HttpStatus.BAD_REQUEST, "账号引用的代理已停用或过期");
                    }
                    proxyId = proxies.importProxy(com.zhouyu.gateway.routing.RouteTable.sha256(proxyKey), new AccountProxies.Input(
                            proxy.path("name").asText(), proxy.path("protocol").asText(), proxy.path("host").asText(),
                            proxy.path("port").asInt(), proxy.path("username").asText(), proxy.path("password").asText()));
                }
                var options = new GatewayConfigLoader.AccountOptions(settings.path("notes").asText(null), null,
                        settings.has("priority") ? settings.path("priority").asInt() : null,
                        settings.has("concurrency") ? settings.path("concurrency").asInt() : null, null, proxyId);
                ids.add(config.importAccount(this, registration, mappings, options));
                if (settings.path("credentials").path("model_mapping").size() > mappings.size()) {
                    warnings.add(new ImportIssue(row, "部分映射需在账号编辑中填写具体模型名称"));
                }
            } catch (ResponseStatusException e) {
                errors.add(new ImportIssue(row, e.getReason()));
            }
        }
        return new ImportResult(ids.stream().distinct().toList(), skipped, List.copyOf(errors), List.copyOf(warnings));
    }

    public void validateModel(long id, String upstreamModel) {
        Account account = byId(id);
        if (WORKBUDDY.equals(account.provider()) && !upstreamModel.startsWith(account.realm() + ":")) {
            throw problem(HttpStatus.BAD_REQUEST, "上游模型需包含账号的站点前缀 " + account.realm() + ":");
        }
    }

    public String enabledProvider(long id, String upstreamModel) {
        Account account = byId(id);
        if (!account.enabled()) throw problem(HttpStatus.CONFLICT, "请先启用账号");
        if (CODEX.equals(account.provider())) AccountAuthorization.requireCodex(secret(account));
        if (account.provider().equals(WORKBUDDY) && !upstreamModel.startsWith(account.realm() + ":")) {
            throw problem(HttpStatus.BAD_REQUEST, "上游模型需包含账号的站点前缀 " + account.realm() + ":");
        }
        return account.provider();
    }

    public void bind(long keyId, long accountId) {
        checkBinding(keyId, accountId, null);
        jdbc.update("INSERT INTO gateway_account_binding(gateway_key_id,account_id) VALUES(?,?) ON DUPLICATE KEY UPDATE account_id=VALUES(account_id)",
                keyId, accountId);
    }

    public void bindForPublish(long keyId, long accountId, String model) {
        checkBinding(keyId, accountId, model);
        jdbc.update("INSERT INTO gateway_account_binding(gateway_key_id,account_id) VALUES(?,?) ON DUPLICATE KEY UPDATE account_id=account_id", keyId, accountId);
        Long bound = jdbc.queryForObject("SELECT account_id FROM gateway_account_binding WHERE gateway_key_id=? FOR UPDATE", Long.class, keyId);
        if (!java.util.Objects.equals(bound, accountId)) throw problem(HttpStatus.CONFLICT, "该 Key 已绑定其他账号，请选择其他 Key 或手动切换绑定");
    }

    private void checkBinding(long keyId, long accountId, String model) {
        var key = config.getKeys().stream().filter(k -> k.getId() == keyId && k.getSecret() != null && !k.getSecret().isBlank())
                .findFirst().orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "Gateway Key 不存在"));
        if ("sk-lg-demo-key-local".equals(key.getSecret())) {
            throw problem(HttpStatus.BAD_REQUEST, "绑定订阅账号前请设置自己的 DEMO_API_KEY");
        }
        if (model != null && key.getAllowedModels() != null && !key.getAllowedModels().isEmpty() && !key.getAllowedModels().contains(model)) {
            throw problem(HttpStatus.BAD_REQUEST, "该 Gateway Key 无权调用此模型名称");
        }
        Account account = byId(accountId);
        if (!CODEX.equals(account.provider()) || "api".equals(account.realm()) || !account.enabled()) {
            throw problem(HttpStatus.BAD_REQUEST, "请选择已启用的订阅账号");
        }
    }

    public void checkEnabled(long accountId) {
        if (!byId(accountId).enabled()) throw problem(HttpStatus.BAD_REQUEST, "请选择已启用的账号");
    }

    public void edit(long accountId, String name, boolean enabled) {
        if (name == null || name.isBlank() || name.length() > 128) throw problem(HttpStatus.BAD_REQUEST, "账号名称须为 1–128 个字符");
        synchronized (lock(accountId)) {
            if (jdbc.update("UPDATE gateway_upstream_account SET name=?,enabled=? WHERE id=?", name.trim(), enabled, accountId) == 0) throw missing();
        }
    }

    public void status(long id, boolean enabled) {
        synchronized (lock(id)) {
            if (jdbc.update("UPDATE gateway_upstream_account SET enabled=? WHERE id=?", enabled, id) == 0) throw missing();
        }
    }

    public void remove(long id) {
        synchronized (lock(id)) {
            Account account = byId(id);
            if (account.provider().equals(CODEX) && !"api".equals(account.realm()) && !account.externalId().startsWith("sub2:")) {
                ObjectNode token = secret(account);
                if (!token.path("codex_oauth").asBoolean()) authorization.revoke(token, proxies.connection(account.proxyId()));
            }
            jdbc.update("DELETE FROM gateway_account_binding WHERE account_id=?", id);
            jdbc.update("DELETE FROM gateway_account_model_cooldown WHERE account_id=?", id);
            jdbc.update("DELETE FROM gateway_upstream_account WHERE id=?", id);
        }
    }

    public Long proxyId(long id) { return byId(id).proxyId(); }

    public void bindProxy(long accountId, long proxyId) { proxies.bind(accountId, proxyId); }

    public String provider(long id) { return byId(id).provider(); }

    public String realm(long id) { return byId(id).realm(); }

    boolean isCodexSubscription(long id) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_upstream_account WHERE id=? AND provider=? AND realm<>'api'",
                Integer.class, id, CODEX);
        return count != null && count > 0;
    }

    List<CodexAccountStatus.Window> wakeupQuotaWindows(long id) {
        return codexWindows.get(id).join();
    }

    public JsonNode models(long id) {
        synchronized (lock(id)) {
            var account = byId(id);
            return authorization.models(account.provider(), account.realm(), validCredential(account), proxies.connection(account.proxyId()));
        }
    }

    public record UsageRow(String day, String model, long requests, long inputTokens, long outputTokens,
                           long costMicro, long durationMs, long timedRequests) {}
    public record UsageStats(String today, List<UsageRow> rows) {}

    public UsageStats usageStats(long id) {
        byId(id);
        var today = jdbc.queryForObject("SELECT CURRENT_DATE", java.time.LocalDate.class);
        var rows = jdbc.query("""
                SELECT DATE(l.created_at) AS day, l.virtual_model, COUNT(*) AS requests,
                       SUM(l.input_tokens) AS input_tokens, SUM(l.output_tokens) AS output_tokens,
                       SUM(l.cost_micro) AS cost_micro, SUM(l.total_ms) AS duration_ms, COUNT(l.total_ms) AS timed_requests
                FROM relay_log l JOIN gateway_account_channel a ON a.channel_id=l.channel_id
                WHERE a.account_id=? AND l.created_at>=? AND l.created_at<DATE_ADD(?, INTERVAL 1 DAY)
                GROUP BY DATE(l.created_at), l.virtual_model ORDER BY day, l.virtual_model
                """, (row, index) -> new UsageRow(row.getString("day"), row.getString("virtual_model"),
                row.getLong("requests"), row.getLong("input_tokens"), row.getLong("output_tokens"),
                row.getLong("cost_micro"), row.getLong("duration_ms"), row.getLong("timed_requests")),
                id, today.minusDays(29), today);
        return new UsageStats(today.toString(), rows);
    }

    public CodexAccountStatus codexStatus(long id) {
        ObjectNode credential;
        AccountProxy proxy;
        synchronized (lock(id)) {
            var account = byId(id);
            if (!CODEX.equals(account.provider()) || "api".equals(account.realm())) throw problem(HttpStatus.BAD_REQUEST, "请选择 ChatGPT 订阅账号");
            credential = validCredential(account);
            proxy = proxies.connection(account.proxyId());
        }
        var status = authorization.codexStatus(credential, proxy);
        if (status.usageError() == null) codexWindows.put(id, CompletableFuture.completedFuture(status.windows()));
        return status;
    }

    public List<CodexAccountStatus.Window> cachedCodexWindows(long id) {
        return codexWindows.get(id).getNow(List.of());
    }

    private List<CodexAccountStatus.Window> refreshCodexWindows(long id) {
        ObjectNode credential;
        AccountProxy proxy;
        synchronized (lock(id)) {
            var account = byId(id);
            if (!CODEX.equals(account.provider()) || "api".equals(account.realm()) || !account.enabled() || !account.schedulable()
                    || account.cooldown() > Instant.now().getEpochSecond()) return List.of();
            credential = validCredential(account);
            proxy = proxies.connection(account.proxyId());
        }
        JsonNode usage = authorization.codexUsage(credential, proxy);
        return CodexAccountStatus.windows(usage.path("rate_limit"), Instant.now().getEpochSecond());
    }

    public int resetCodexQuota(long id, String creditId) {
        synchronized (lock(id)) {
            var account = byId(id);
            if (!CODEX.equals(account.provider())) throw problem(HttpStatus.BAD_REQUEST, "请选择 ChatGPT 订阅账号");
            int windows = authorization.resetCodexQuota(validCredential(account), proxies.connection(account.proxyId()), creditId);
            if (windows > 0) codexWindows.synchronous().invalidate(id);
            return windows;
        }
    }

    public Long updateCredits(long id) {
        synchronized (lock(id)) {
            var account = byId(id);
            if (!account.provider().equals(WORKBUDDY)) throw problem(HttpStatus.BAD_REQUEST, "订阅额度在 ChatGPT 中查看");
            Long credits = authorization.credits(account.realm(), validCredential(account), proxies.connection(account.proxyId()));
            if (credits != null) jdbc.update("UPDATE gateway_upstream_account SET credits=? WHERE id=?", credits, id);
            return credits;
        }
    }

    public Lease acquire(String provider, long keyId, String model, Set<Long> attempted, Long preferred) {
        long now = Instant.now().getEpochSecond();
        List<Account> accounts;
        if (provider.equals(CODEX)) {
            accounts = jdbc.query("""
                    SELECT a.* FROM gateway_upstream_account a JOIN gateway_account_binding b ON b.account_id=a.id
                    WHERE b.gateway_key_id=? AND a.provider=? AND a.enabled=1 AND a.schedulable=1 AND a.cooldown_until<=?
                    """, this::read, keyId, CODEX, now);
        } else {
            String realm = model.startsWith("global:") ? "global" : "cn";
            accounts = jdbc.query("""
                    SELECT a.* FROM gateway_upstream_account a WHERE a.provider=? AND a.realm=? AND a.enabled=1 AND a.schedulable=1 AND a.cooldown_until<=?
                    AND NOT EXISTS(SELECT 1 FROM gateway_account_model_cooldown c WHERE c.account_id=a.id AND c.model=? AND c.until_epoch>?)
                    """, this::read, WORKBUDDY, realm, now, model, now);
        }
        return acquireFrom(accounts, provider, attempted, preferred, now);
    }

    public Lease acquireForAccount(String provider, long accountId, String model, Set<Long> attempted) {
        long now = Instant.now().getEpochSecond();
        var selected = jdbc.query("""
                SELECT a.*, EXISTS(SELECT 1 FROM gateway_account_model_cooldown c
                    WHERE c.account_id=a.id AND c.model=? AND c.until_epoch>?) AS model_cooling
                FROM gateway_upstream_account a WHERE a.id=? AND a.provider=?
                """, (row, index) -> {
            Account account = read(row, index);
            if (!account.enabled()) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "账号已停用");
            if (!account.schedulable()) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "账号已暂停调度");
            if (account.cooldown() > now) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "账号冷却中");
            if (row.getBoolean("model_cooling")) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "该账号的模型冷却中");
            return account;
        }, model, now, accountId, provider);
        return acquireFrom(selected, provider, attempted, null, now);
    }

    private Lease acquireFrom(List<Account> accounts, String provider, Set<Long> attempted, Long preferred, long now) {
        var eligible = new java.util.ArrayList<>(accounts.stream().filter(account -> !attempted.contains(account.id())).toList());
        while (!eligible.isEmpty()) {
            Account selected = eligible.stream().filter(a -> java.util.Objects.equals(preferred, a.id())).findFirst()
                    .orElseGet(() -> weighted(eligible));
            eligible.remove(selected);
            try {
                Lease lease = tryLease(selected.id(), now, true);
                if (lease != null) return lease;
            } catch (ResponseStatusException e) {
                if (provider.equals(CODEX)) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "订阅授权已过期或暂不可用");
            }
        }
        throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE,
                provider.equals(CODEX) ? "当前 Key 未绑定可用订阅账号，或该账号正在冷却" : "WorkBuddy 站点暂无可用账号");
    }

    // A manual test may target a paused account and shares the account pool concurrency limit.
    public Lease acquireForTest(long id, String upstreamModel) {
        validateModel(id, upstreamModel);
        Lease lease = tryLease(id, Instant.now().getEpochSecond(), false);
        if (lease == null) throw problem(HttpStatus.TOO_MANY_REQUESTS, "账号并发已满，请稍后重试");
        return lease;
    }

    private Lease tryLease(long id, long now, boolean scheduled) {
        record AccountCapacity(Account account, int concurrency) {}
        synchronized (lock(id)) {
            var settings = jdbc.query("""
                    SELECT u.*, COALESCE(c.max_inflight, ?) AS concurrency FROM gateway_upstream_account u
                    LEFT JOIN gateway_account_channel a ON a.account_id=u.id
                    LEFT JOIN channel c ON c.id=a.channel_id WHERE u.id=?
                    """, (row, index) -> new AccountCapacity(read(row, index), row.getInt("concurrency")),
                    defaultConcurrency, id).stream().findFirst().orElseThrow(AccountPool::missing);
            var account = settings.account();
            if (scheduled && (!account.enabled() || !account.schedulable() || account.cooldown() > now)) return null;
            var current = inflight.computeIfAbsent(id, unused -> new AtomicInteger());
            if (current.get() >= settings.concurrency()) return null;
            var credential = validCredential(account);
            var proxy = proxies.connection(account.proxyId());
            current.incrementAndGet();
            return new Lease(id, account.provider(), account.realm(), credential, current, proxy);
        }
    }

    private static Account weighted(List<Account> accounts) {
        var total = accounts.stream().mapToLong(AccountPool::weight).sum();
        long remaining = ThreadLocalRandom.current().nextLong(total);
        for (Account account : accounts) {
            remaining -= weight(account);
            if (remaining < 0) return account;
        }
        throw new IllegalStateException("Empty weighted account selection");
    }

    private static long weight(Account account) {
        return account.credits() == null ? 10 : 1 + (long) Math.sqrt(Math.max(0, account.credits()));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT1M")
    void maintainWorkBuddyAccounts() {
        for (Summary summary : summaries()) {
            if (summary.enabled() && summary.provider().equals(WORKBUDDY)) {
                try { updateCredits(summary.id()); }
                catch (ResponseStatusException ignored) { }
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 9,21 * * *", zone = "Asia/Shanghai")
    void checkInWorkBuddyAccounts() {
        if (!checkInEnabled) return;
        for (Summary summary : summaries()) {
            if (!summary.enabled() || !summary.provider().equals(WORKBUDDY)) continue;
            synchronized (lock(summary.id())) {
                try {
                    Account account = byId(summary.id());
                    if (account.enabled()) authorization.checkIn(account.realm(), validCredential(account), proxies.connection(account.proxyId()));
                } catch (ResponseStatusException ignored) { }
            }
        }
    }

    public void result(Lease lease, String model, int status, int input, int output, boolean modelLimit, long resetAt) {
        long now = Instant.now().getEpochSecond();
        if (status == 429 && modelLimit && lease.provider().equals(WORKBUDDY)) {
            jdbc.update("""
                    INSERT INTO gateway_account_model_cooldown(account_id,model,until_epoch) VALUES(?,?,?)
                    ON DUPLICATE KEY UPDATE until_epoch=GREATEST(until_epoch,VALUES(until_epoch))
                    """, lease.id(), model, resetAt > now ? resetAt : now + 600);
        }
        long cooldown = 0;
        if (status == 429 && !modelLimit) cooldown = resetAt > now ? resetAt : now + 60;
        if (status == 402) cooldown = resetAt > now ? resetAt : now + 3600;
        jdbc.update("""
                UPDATE gateway_upstream_account SET last_used_at=UNIX_TIMESTAMP(), requests=requests+1, failures=failures+?, input_tokens=input_tokens+?,
                output_tokens=output_tokens+?, cooldown_until=GREATEST(cooldown_until,?), last_error=COALESCE(?,last_error),
                enabled=CASE WHEN ? IN (401,403) THEN 0 ELSE enabled END WHERE id=?
                """, status >= 400 ? 1 : 0, input, output, cooldown, status >= 400 ? "http_" + status : null, status, lease.id());
    }

    public record Lease(long id, String provider, String realm, ObjectNode credential, AtomicInteger counter, AccountProxy proxy) implements AutoCloseable {
        public Lease(long id, String provider, String realm, ObjectNode credential, AtomicInteger counter) {
            this(id, provider, realm, credential, counter, null);
        }
        @Override public void close() { counter.decrementAndGet(); }
        @Override public String toString() { return "AccountLease[" + id + "," + provider + "]"; }
    }

    private Account byId(long id) {
        return jdbc.query("SELECT * FROM gateway_upstream_account WHERE id=?", this::read, id).stream().findFirst().orElseThrow(AccountPool::missing);
    }

    private Account read(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Account(row.getLong("id"), row.getString("provider"), row.getString("external_id"), row.getString("name"),
                row.getString("realm"), row.getBoolean("enabled"), row.getLong("expires_at"), row.getLong("cooldown_until"),
                row.getBytes("credential"), row.getObject("credits", Long.class), row.getBoolean("schedulable"), row.getObject("proxy_id", Long.class));
    }

    private ObjectNode validCredential(Account a) {
        ObjectNode data = secret(a);
        if ("api".equals(a.realm())) return data;
        if (CODEX.equals(a.provider())) AccountAuthorization.requireCodex(data);
        long now = Instant.now().getEpochSecond();
        if (a.expiresAt() <= now + 60 && data.path("earliest_refresh_at").asLong() <= now) {
            if (data.path("refresh_token").asText().isBlank() && a.expiresAt() > now) return data;
            try {
                data = authorization.refresh(a.provider(), a.realm(), data, proxies.connection(a.proxyId()));
                long expiry = data.path("expires_at").asLong(now + 3600);
                jdbc.update("UPDATE gateway_upstream_account SET credential=?, expires_at=?, last_error=NULL WHERE id=?",
                        cipher.encrypt(data.toString(), scope(a.provider(), a.externalId())), expiry, a.id());
            } catch (ResponseStatusException e) {
                if (e.getStatusCode().value() == 401) {
                    jdbc.update("UPDATE gateway_upstream_account SET enabled=0,last_error='reauthorization_required' WHERE id=?", a.id());
                }
                throw e;
            }
        } else if (a.expiresAt() <= now) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "刷新窗口尚未开放");
        }
        return data;
    }

    private ObjectNode secret(Account a) {
        try { return (ObjectNode) mapper.readTree(cipher.decrypt(a.secret(), scope(a.provider(), a.externalId()))); }
        catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored account credential", e); }
    }

    private Object lock(long id) { return locks[Math.floorMod(id, locks.length)]; }
    private static String scope(String provider, String id) { return "account:" + provider + ":" + id; }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static ResponseStatusException missing() { return problem(HttpStatus.NOT_FOUND, "账号不存在"); }
    static ResponseStatusException problem(HttpStatus status, String msg) { return new ResponseStatusException(status, msg); }
}
