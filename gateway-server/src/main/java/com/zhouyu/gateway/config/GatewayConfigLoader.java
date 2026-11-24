package com.zhouyu.gateway.config;

import com.zhouyu.gateway.accounts.AccountAuthorization;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.accounts.AccountGroups;
import com.zhouyu.gateway.domain.AccountRoutingMode;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.quota.KeySpend;
import com.zhouyu.gateway.quota.KeySpend.Amounts;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.IpRules;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Configuration(proxyBeanMethods = false)
public class GatewayConfigLoader {
    private static final String CHANNEL_KEY = "gateway-managed";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RouteTableHolder holder;
    private final GatewayProperties bootstrap;
    private final CredentialCipher cipher;
    private final AccountGroups groups;
    private final KeySpend spend;

    public GatewayConfigLoader(JdbcTemplate jdbc, TransactionTemplate tx, RouteTableHolder holder,
                               GatewayProperties bootstrap, CredentialCipher cipher, AccountGroups groups, KeySpend spend) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.holder = holder;
        this.bootstrap = bootstrap;
        this.cipher = cipher;
        this.groups = groups;
        this.spend = spend;
    }

    @Bean
    @org.springframework.core.annotation.Order(10)
    ApplicationRunner loadRouteTable() {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS gateway_client_key (
                      key_id BIGINT PRIMARY KEY, account_id BIGINT NOT NULL DEFAULT 0, cipher VARBINARY(512) NOT NULL,
                      account_group VARCHAR(24), ip_allow TEXT, ip_deny TEXT
                    ) ENGINE=InnoDB
                    """);
            // Existing rows keep their account restriction until explicitly moved to a group.
            var columns = jdbc.queryForList("""
                    SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gateway_client_key'
                    """, String.class);
            if (!columns.contains("account_group")) jdbc.execute("ALTER TABLE gateway_client_key ADD account_group VARCHAR(24)");
            if (!columns.contains("ip_allow")) jdbc.execute("ALTER TABLE gateway_client_key ADD ip_allow TEXT");
            if (!columns.contains("ip_deny")) jdbc.execute("ALTER TABLE gateway_client_key ADD ip_deny TEXT");
            if (!columns.contains("last_used_ip")) jdbc.execute("ALTER TABLE gateway_client_key ADD last_used_ip VARCHAR(45)");
            if (!columns.contains("spend_total")) jdbc.execute("ALTER TABLE gateway_client_key ADD spend_total BIGINT NOT NULL DEFAULT 0");
            if (!columns.contains("spend_5h")) jdbc.execute("ALTER TABLE gateway_client_key ADD spend_5h BIGINT NOT NULL DEFAULT 0");
            if (!columns.contains("spend_1d")) jdbc.execute("ALTER TABLE gateway_client_key ADD spend_1d BIGINT NOT NULL DEFAULT 0");
            if (!columns.contains("spend_7d")) jdbc.execute("ALTER TABLE gateway_client_key ADD spend_7d BIGINT NOT NULL DEFAULT 0");
            if (!columns.contains("quota_reset_at")) jdbc.execute("ALTER TABLE gateway_client_key ADD quota_reset_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00'");
            if (!columns.contains("window_reset_at")) jdbc.execute("ALTER TABLE gateway_client_key ADD window_reset_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00'");
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='relay_log' AND INDEX_NAME='idx_key_time'
                    """, Integer.class) == 0) jdbc.execute("CREATE INDEX idx_key_time ON relay_log(api_key_id,created_at)");
            var logColumns = jdbc.queryForList("""
                    SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='relay_log'
                    """, String.class);
            var usageColumns = java.util.Map.of(
                    "account_id", "BIGINT",
                    "group_id", "BIGINT",
                    "inbound_endpoint", "VARCHAR(128)",
                    "upstream_endpoint", "VARCHAR(256)",
                    "reasoning_effort", "VARCHAR(32)",
                    "billing_mode", "VARCHAR(16)",
                    "cache_creation_tokens", "INT",
                    "uncached_input_tokens", "INT",
                    "account_cost_micro", "BIGINT");
            var missing = usageColumns.entrySet().stream().filter(column -> !logColumns.contains(column.getKey()))
                    .map(column -> "ADD " + column.getKey() + " " + column.getValue()).toList();
            if (!missing.isEmpty()) jdbc.execute("ALTER TABLE relay_log " + String.join(", ", missing));
            groups.initialize();
            if (version() == 0) {
                publish(0L, "bootstrap", () -> importConfig(bootstrap));
            } else {
                holder.swap(load(version()));
            }
        };
    }

    public record ChannelInput(String name, Protocol protocol, String baseUrl, String apiKey,
                               Integer priority, Integer weight, Integer maxInflight, Long timeoutMs,
                               Boolean passthrough, Boolean enabled, Boolean apiKeyInQuery) {}

    public record RouteInput(String virtualModel, String upstreamModel, Long channelId,
                             Integer weight, Double priceRatio) {}

    public long saveChannel(Long id, ChannelInput input, long expectedVersion) {
        check(input);
        return publish(expectedVersion, id == null ? "channel.create" : "channel.update", () -> {
            if (id != null && !holder.table().channels().containsKey(id)) throw missing("渠道不存在");
            if (id != null) requirePublicChannel(id);
            return writeChannel(id, input);
        });
    }

    public long setEnabled(long id, boolean enabled, long expectedVersion) {
        return publish(expectedVersion, "channel.status", () -> {
            requirePublicChannel(id);
            if (jdbc.update("UPDATE channel SET status=? WHERE id=?", enabled, id) == 0) throw missing("渠道不存在");
            return id;
        });
    }

    public long deleteChannel(long id, long expectedVersion) {
        return publish(expectedVersion, "channel.delete", () -> {
            requirePublicChannel(id);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM model_route WHERE channel_id=?", Integer.class, id) > 0) {
                throw conflict("请先移除该渠道的模型绑定");
            }
            if (jdbc.update("DELETE FROM channel WHERE id=?", id) == 0) throw missing("渠道不存在");
            jdbc.update("DELETE FROM channel_key WHERE channel_id=?", id);
            return id;
        });
    }

    public long saveRoute(Long id, RouteInput input, long expectedVersion) {
        check(input);
        return publish(expectedVersion, id == null ? "route.create" : "route.update", () -> {
            requirePublicChannel(input.channelId());
            if (id != null) requirePublicRoute(id);
            return writeRoute(id, input);
        });
    }

    public long publishAccountModel(AccountPool accounts, long accountId, String model, String upstream, Long keyId, long expectedVersion) {
        check(new RouteInput(model, upstream, 0L, 1, 1.0));
        return publish(expectedVersion, "account.model.publish", () -> {
            Protocol protocol = Protocol.valueOf(accounts.enabledProvider(accountId, upstream));
            if (protocol == Protocol.ANTHROPIC || (protocol == Protocol.CODEX_SUBSCRIPTION && "api".equals(accounts.realm(accountId)))) {
                throw invalid("请在账号中保存模型映射");
            }
            if (protocol == Protocol.CODEX_SUBSCRIPTION) {
                if (keyId == null) throw invalid("请选择用于调用的 Gateway Key");
                accounts.bindForPublish(keyId, accountId, model);
            }
            String name = protocol == Protocol.WORKBUDDY ? "WorkBuddy 账号池" : "Codex 订阅账号池";
            String base = protocol == Protocol.WORKBUDDY ? AccountAuthorization.workBase("cn") : AccountAuthorization.CODEX_BASE;
            var channels = holder.table().channels().values().stream().filter(c -> c.name().equals(name)).toList();
            long channelId;
            if (channels.isEmpty()) {
                channelId = writeChannel(null, new ChannelInput(name, protocol, base, null, 100, 1, 16, 120_000L, false, true, false));
            } else {
                var channel = channels.get(0);
                if (channel.protocol() != protocol || !channel.enabled()) throw conflict("同名账号池渠道已存在或已停用");
                channelId = channel.id();
            }
            var routes = jdbc.query("""
                    SELECT r.id, r.channel_id, r.upstream_model, r.enabled, m.status FROM model_route r
                    JOIN virtual_model m ON m.id=r.virtual_model_id WHERE m.name=?
                    AND NOT EXISTS (SELECT 1 FROM gateway_account_channel a WHERE a.channel_id=r.channel_id)
                    """, (row, index) -> new ExistingRoute(row.getLong(1), row.getLong(2), row.getString(3),
                    row.getBoolean(4) && row.getBoolean(5)), model);
            if (!routes.isEmpty()) {
                var route = routes.get(0);
                if (routes.size() == 1 && route.channelId() == channelId && route.enabled() && upstream.equals(route.upstream())) return route.id();
                throw conflict("模型名称已被其他路由占用，请填写新名称");
            }
            return writeRoute(null, new RouteInput(model, upstream, channelId, 1, 1.0));
        });
    }

    public record ModelMapping(String model, String upstreamModel) {}
    public record AccountOptions(String notes, Boolean schedulable, Integer priority, Integer concurrency, List<Long> groupIds, Long proxyId) {}

    public synchronized Map<String, Object> accountSettings(long accountId) {
        var limits = jdbc.query("""
                SELECT c.priority,c.max_inflight FROM channel c JOIN gateway_account_channel a ON c.id=a.channel_id WHERE a.account_id=?
                """, (r, i) -> Map.of("priority", r.getInt(1), "concurrency", r.getInt(2)), accountId)
                .stream().findFirst().orElse(Map.of("priority", 100, "concurrency", 16));
        return Map.of("mappings", accountMappings(accountId), "version", holder.version(),
                "priority", limits.get("priority"), "concurrency", limits.get("concurrency"),
                "proxyId", jdbc.queryForObject("SELECT COALESCE(proxy_id,0) FROM gateway_upstream_account WHERE id=?", Long.class, accountId),
                "groupIds", groups.list().stream().filter(g -> !g.automatic() && g.accountIds().contains(accountId))
                        .map(AccountGroups.Group::id).toList());
    }

    private List<ModelMapping> accountMappings(long accountId) {
        return jdbc.query("""
                SELECT m.name,r.upstream_model FROM gateway_account_channel a
                JOIN model_route r ON r.channel_id=a.channel_id JOIN virtual_model m ON m.id=r.virtual_model_id
                WHERE a.account_id=? AND r.enabled=1 AND m.status=1 ORDER BY r.id
                """, (row, index) -> new ModelMapping(row.getString(1), row.getString(2)), accountId);
    }

    public long editAccount(AccountPool accounts, long accountId, String name, boolean enabled,
                            List<ModelMapping> mappings, AccountOptions options, long expectedVersion) {
        if (mappings == null || mappings.size() > 100) throw invalid("每个账号最多配置 100 个模型映射");
        return publish(expectedVersion, "account.edit", () -> {
            accounts.edit(accountId, name, enabled);
            writeAccountMappings(accounts, accountId, mappings, false);
            if (options != null) applyAccountOptions(accounts, accountId, options);
            return accountId;
        });
    }

    private void applyAccountOptions(AccountPool accounts, long accountId, AccountOptions options) {
        if ((options.notes() != null && options.notes().length() > 512)
                || (options.priority() != null && (options.priority() < 0 || options.priority() > 10000))
                || (options.concurrency() != null && (options.concurrency() < 1 || options.concurrency() > 1000))) {
            throw invalid("备注最多 512 字，优先级范围 0–10000，并发范围 1–1000");
        }
        jdbc.update("UPDATE gateway_upstream_account SET notes=COALESCE(?,notes),schedulable=COALESCE(?,schedulable) WHERE id=?",
                options.notes(), options.schedulable(), accountId);
        jdbc.update("""
                UPDATE channel c JOIN gateway_account_channel a ON c.id=a.channel_id
                SET c.priority=COALESCE(?,c.priority),c.max_inflight=COALESCE(?,c.max_inflight) WHERE a.account_id=?
                """, options.priority(), options.concurrency(), accountId);
        groups.assign(accountId, accounts.provider(accountId), options.groupIds());
        if (options.proxyId() != null) accounts.bindProxy(accountId, options.proxyId());
    }

    public void bulkAccountState(AccountPool accounts, List<Long> ids, Boolean enabled, Boolean schedulable) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull) || (enabled == null && schedulable == null)) {
            throw invalid("请选择 1–100 个账号和操作");
        }
        publish(null, "account.bulk", () -> {
            for (long id : new java.util.HashSet<>(ids)) {
                accounts.provider(id);
                jdbc.update("UPDATE gateway_upstream_account SET enabled=COALESCE(?,enabled),schedulable=COALESCE(?,schedulable) WHERE id=?",
                        enabled, schedulable, id);
            }
            return 0L;
        });
    }

    public List<AccountGroups.Group> accountGroups() { return groups.list(); }

    public void preferGroupAccount(long groupId, Long accountId) {
        publish(null, "group.preferred-account", () -> { groups.preferAccount(groupId, accountId); return groupId; });
    }

    public void reorderGroupAccounts(long groupId, List<Long> accountIds) {
        publish(null, "group.account-order", () -> { groups.reorderAccounts(groupId, accountIds); return groupId; });
    }

    public long saveAccountGroup(Long id, AccountGroups.Input input) {
        long[] saved = new long[1];
        publish(null, "group.save", () -> saved[0] = groups.save(id, input));
        return saved[0];
    }

    public void deleteAccountGroup(long id) {
        publish(null, "group.delete", () -> { groups.delete(id); return id; });
    }

    public long importAccount(AccountPool accounts, AccountPool.Registration registration, List<ModelMapping> mappings, AccountOptions options) {
        long[] accountId = new long[1];
        publish(null, "account.import", () -> {
            accountId[0] = accounts.save(registration);
            writeAccountMappings(accounts, accountId[0], mappings, true);
            applyAccountOptions(accounts, accountId[0], options);
            return accountId[0];
        });
        return accountId[0];
    }

    public void changeAccountStatus(AccountPool accounts, long accountId, boolean enabled) {
        publish(null, "account.status", () -> {
            accounts.status(accountId, enabled);
            return accountId;
        });
    }

    public long saveAuthorizedAccount(java.util.function.LongSupplier save) {
        long[] accountId = new long[1];
        publish(null, "account.authorize", () -> accountId[0] = save.getAsLong());
        return accountId[0];
    }

    private void writeAccountMappings(AccountPool accounts, long accountId, List<ModelMapping> mappings, boolean merge) {
        var modelNames = new java.util.HashSet<String>();
        for (var mapping : mappings) {
            if (mapping == null) throw invalid("模型映射不能为空");
            check(new RouteInput(mapping.model(), mapping.upstreamModel(), 0L, 1, 1.0));
            if (!modelNames.add(mapping.model().toLowerCase(java.util.Locale.ROOT))) throw invalid("客户端模型名不能重复");
            if (mapping.model().contains("*") || mapping.upstreamModel().contains("*")) throw invalid("请填写具体的模型名称");
            accounts.validateModel(accountId, mapping.upstreamModel());
        }
        Long channelId = jdbc.query("SELECT channel_id FROM gateway_account_channel WHERE account_id=?",
                (row, index) -> row.getLong(1), accountId).stream().findFirst().orElse(null);
        if (channelId == null) {
            Protocol protocol = Protocol.valueOf(accounts.provider(accountId));
            if (protocol == Protocol.CODEX_SUBSCRIPTION && "api".equals(accounts.realm(accountId))) protocol = Protocol.OPENAI_RESPONSES;
            String base = switch (protocol) {
                case ANTHROPIC -> AccountAuthorization.ANTHROPIC_BASE;
                case OPENAI_RESPONSES -> AccountAuthorization.OPENAI_BASE;
                case WORKBUDDY -> AccountAuthorization.workBase("cn");
                default -> AccountAuthorization.CODEX_BASE;
            };
            channelId = writeChannel(null, new ChannelInput("账号 #" + accountId, protocol, base, null,
                    100, 1, 16, 120_000L, false, true, false));
            jdbc.update("INSERT INTO gateway_account_channel(account_id,channel_id) VALUES(?,?)", accountId, channelId);
        }
        record StoredMapping(long id, String upstream) {}
        Map<String, StoredMapping> old = new HashMap<>();
        jdbc.query("""
                SELECT r.id,m.name,r.upstream_model FROM model_route r JOIN virtual_model m ON m.id=r.virtual_model_id WHERE r.channel_id=?
                """, row -> {
            old.put(row.getString(2).toLowerCase(java.util.Locale.ROOT), new StoredMapping(row.getLong(1), row.getString(3)));
        }, channelId);
        for (var mapping : mappings) {
            var stored = old.remove(mapping.model().toLowerCase(java.util.Locale.ROOT));
            if (stored == null || !merge && !stored.upstream().equals(mapping.upstreamModel())) {
                writeRoute(stored == null ? null : stored.id(), new RouteInput(mapping.model(), mapping.upstreamModel(), channelId, 1, 1.0));
            }
        }
        if (!merge) for (var orphan : old.values()) jdbc.update("DELETE FROM model_route WHERE id=?", orphan.id());
    }

    public void deleteAccount(AccountPool accounts, long accountId) {
        publish(null, "account.delete", () -> {
            accounts.remove(accountId);
            groups.removeAccount(accountId);
            jdbc.update("DELETE r FROM model_route r JOIN gateway_account_channel a ON r.channel_id=a.channel_id WHERE a.account_id=?", accountId);
            jdbc.update("DELETE c FROM channel c JOIN gateway_account_channel a ON c.id=a.channel_id WHERE a.account_id=?", accountId);
            jdbc.update("DELETE FROM gateway_account_channel WHERE account_id=?", accountId);
            jdbc.update("UPDATE api_key k JOIN gateway_client_key m ON k.id=m.key_id SET k.status=0 WHERE m.account_id=?", accountId);
            return accountId;
        });
    }

    public record ClientKeyInput(String name, String accountGroup, Long accountId, Boolean enabled,
                                 List<String> ipAllow, List<String> ipDeny, Long groupId, String customKey,
                                 Long expiresAt, Integer rpmLimit, Amounts spendLimits, List<Long> groupIds) {}
    public record ClientKey(long id, String name, String prefix, String accountGroup, Long accountId, Long groupId,
                            boolean enabled, boolean groupAvailable, List<String> ipAllow, List<String> ipDeny,
                            Long createdAt, Long expiresAt, Long lastUsedAt, String lastUsedIp, Integer rpmLimit, Amounts spendLimits, Amounts spendUsed, List<Long> groupIds) {
        ClientKey withSpend(Amounts spent) {
            return new ClientKey(id, name, prefix, accountGroup, accountId, groupId, enabled, groupAvailable, ipAllow, ipDeny,
                    createdAt, expiresAt, lastUsedAt, lastUsedIp, rpmLimit, spendLimits, spent, groupIds);
        }
    }
    public record ClientKeyUse(String name, String apiKey, List<ModelMapping> models) {}
    public record ClientKeyIpInput(List<String> ipAllow, List<String> ipDeny) {}

    private Map<Long, List<Long>> extraKeyGroups() {
        var result = new HashMap<Long, List<Long>>();
        jdbc.query("SELECT key_id,group_id FROM gateway_client_key_group ORDER BY key_id,position", row -> {
            result.computeIfAbsent(row.getLong(1), ignored -> new java.util.ArrayList<>()).add(row.getLong(2));
        });
        return result;
    }

    private void replaceExtraKeyGroups(long keyId, List<Long> groupIds) {
        jdbc.update("DELETE FROM gateway_client_key_group WHERE key_id=?", keyId);
        for (int index = 1; index < groupIds.size(); index++) {
            jdbc.update("INSERT INTO gateway_client_key_group(key_id,group_id,position) VALUES(?,?,?)", keyId, groupIds.get(index), index);
        }
    }

    public List<ClientKey> clientKeys() {
        var extras = extraKeyGroups();
        return jdbc.query("""
                SELECT k.id,k.name,k.key_prefix,k.status,NULLIF(m.account_id,0),COALESCE(g.account_provider,a.provider),g.id,
                    CASE WHEN g.id IS NULL THEN COALESCE(a.enabled AND a.schedulable,0) ELSE EXISTS(
                        SELECT 1 FROM api_group selected JOIN gateway_upstream_account u ON u.provider=selected.account_provider
                        WHERE selected.enabled=1 AND u.enabled=1 AND u.schedulable=1
                        AND (selected.id=g.id OR EXISTS(SELECT 1 FROM gateway_client_key_group x WHERE x.key_id=k.id AND x.group_id=selected.id))
                        AND (selected.automatic=1 OR EXISTS(SELECT 1 FROM gateway_account_group_member b WHERE b.group_id=selected.id AND b.account_id=u.id))
                    ) END, m.ip_allow,m.ip_deny,UNIX_TIMESTAMP(k.created_at),UNIX_TIMESTAMP(k.expires_at),
                    UNIX_TIMESTAMP(k.last_used_at),m.last_used_ip,k.rpm_limit,m.spend_total,m.spend_5h,m.spend_1d,m.spend_7d
                FROM api_key k JOIN gateway_client_key m ON k.id=m.key_id
                LEFT JOIN gateway_upstream_account a ON a.id=m.account_id
                LEFT JOIN api_group g ON g.id=k.group_id AND g.account_provider IS NOT NULL ORDER BY k.id DESC
                """, (row, index) -> {
            Amounts limits = amounts(row, 16);
            Long primaryGroup = row.getObject(7, Long.class);
            var groupIds = new java.util.ArrayList<Long>();
            if (primaryGroup != null) groupIds.add(primaryGroup);
            groupIds.addAll(extras.getOrDefault(row.getLong(1), List.of()));
            return new ClientKey(row.getLong(1), row.getString(2), row.getString(3), row.getString(6),
                row.getObject(5, Long.class), row.getObject(7, Long.class), row.getBoolean(4), row.getBoolean(8),
                ipList(row.getString(9)), ipList(row.getString(10)), row.getObject(11, Long.class),
                row.getObject(12, Long.class), row.getObject(13, Long.class), row.getString(14), row.getObject(15, Integer.class),
                limits, Amounts.ZERO, List.copyOf(groupIds));
        }).stream().map(key -> key.spendLimits().enabled() ? key.withSpend(spend.used(key.id())) : key).toList();
    }

    public synchronized ClientKeyUse useClientKey(long id) {
        record StoredKey(String name, byte[] encrypted, String hash) {}
        var stored = jdbc.query("""
                SELECT k.name,m.cipher,k.key_hash FROM api_key k JOIN gateway_client_key m ON k.id=m.key_id WHERE k.id=?
                """, (row, index) -> new StoredKey(row.getString(1), row.getBytes(2), row.getString(3)), id).stream().findFirst()
                .orElseThrow(() -> missing("密钥不存在"));
        var table = holder.table();
        var key = table.findKeyHash(stored.hash()).filter(k -> !k.expired()).orElseThrow(() -> missing("密钥、分组或账号不可用"));
        var models = table.knownModels().stream().sorted()
                .flatMap(model -> table.candidates(model, key).stream().limit(1)
                        .map(candidate -> new ModelMapping(model, candidate.route().upstreamModel())))
                .toList();
        return new ClientKeyUse(stored.name(), cipher.decrypt(stored.encrypted(), "client-key:" + id), models);
    }

    public long saveClientKey(AccountPool accounts, Long id, ClientKeyInput input) {
        var groupIds = input.groupIds();
        if (groupIds != null && (groupIds.isEmpty() || groupIds.size() > 100
                || groupIds.stream().anyMatch(groupId -> groupId == null || groupId <= 0)
                || new java.util.HashSet<>(groupIds).size() != groupIds.size())) {
            throw invalid("请选择 1–100 个不同的分组");
        }
        if (blank(input.name(), 64) || input.enabled() == null
                || (groupIds == null && input.groupId() == null && input.accountGroup() == null && input.accountId() == null)) {
            throw invalid("请填写名称并选择分组");
        }
        checkIps(input.ipAllow(), input.ipDeny());
        if (input.rpmLimit() != null && (input.rpmLimit() < 0 || input.rpmLimit() > 100000)) throw invalid("每分钟请求数须为 0–100000");
        if (input.expiresAt() != null && (input.expiresAt() < 0 || input.expiresAt() > 4102444799L)) throw invalid("到期时间无效");
        if (input.spendLimits() != null && !input.spendLimits().valid()) throw invalid("额度须为 0–1000000 美元");
        String customKey = input.customKey();
        if (customKey != null && (id != null || !customKey.matches("[a-zA-Z0-9_-]{24,128}"))) {
            throw invalid("新建自定义密钥须由 24–128 位字母、数字、连字符或下划线组成");
        }
        long[] result = new long[1];
        publish(null, id == null ? "key.create" : "key.update", () -> {
            Long selectedGroup = groupIds == null ? input.groupId() : groupIds.get(0);
            if (groupIds != null) groupIds.stream().skip(1).forEach(groups::require);
            if (selectedGroup == null && input.accountGroup() != null) selectedGroup = groups.defaultId(input.accountGroup());
            var group = selectedGroup == null ? null : groups.require(selectedGroup);
            long groupId = group == null ? 1 : group.id();
            String provider = group == null ? null : group.provider();
            long accountId = group == null ? input.accountId() : 0;
            if (group == null) accounts.provider(accountId);
            if (id == null) {
                long configuredMax = bootstrap.getKeys().stream().mapToLong(GatewayProperties.KeyConfig::getId).max().orElse(0);
                long storedMax = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM api_key", Long.class);
                long keyId = Math.max(configuredMax, storedMax) + 1;
                String key = customKey != null ? customKey : "sk-lg-" + java.util.UUID.randomUUID().toString().replace("-", "")
                        + java.util.UUID.randomUUID().toString().replace("-", "");
                String hash = RouteTable.sha256(key);
                if (holder.table().findKeyHash(hash).isPresent() || jdbc.queryForObject("SELECT COUNT(*) FROM api_key WHERE key_hash=? OR key_prefix=?",
                        Integer.class, hash, key.substring(0, 16)) > 0
                        || bootstrap.getKeys().stream().anyMatch(k -> key.equals(k.getSecret()))) throw conflict("密钥或前缀已存在");
                jdbc.update("""
                        INSERT INTO api_key(id,tenant_id,name,key_prefix,key_hash,group_id,status,expires_at,rpm_limit)
                        VALUES(?,1,?,?,?,?,?,FROM_UNIXTIME(NULLIF(?,0)),NULLIF(?,0))
                        """, keyId, input.name().strip(), key.substring(0, 16), hash, groupId, input.enabled(), input.expiresAt(), input.rpmLimit());
                jdbc.update("INSERT INTO gateway_client_key(key_id,account_id,cipher,account_group,ip_allow,ip_deny) VALUES(?,?,?,?,?,?)",
                        keyId, accountId, cipher.encrypt(key, "client-key:" + keyId), provider, ipText(input.ipAllow()), ipText(input.ipDeny()));
                result[0] = keyId;
            } else {
                requireClientKey(id);
                jdbc.update("""
                        UPDATE gateway_client_key SET account_id=?,account_group=?,
                        ip_allow=COALESCE(?,ip_allow),ip_deny=COALESCE(?,ip_deny) WHERE key_id=?
                        """, accountId, provider, ipText(input.ipAllow()), ipText(input.ipDeny()), id);
                jdbc.update("""
                        UPDATE api_key SET name=?,status=?,group_id=?,
                          expires_at=IF(? IS NULL,expires_at,FROM_UNIXTIME(NULLIF(?,0))),
                          rpm_limit=IF(? IS NULL,rpm_limit,NULLIF(?,0)) WHERE id=?
                        """, input.name().strip(), input.enabled(), groupId, input.expiresAt(), input.expiresAt(),
                        input.rpmLimit(), input.rpmLimit(), id);
                result[0] = id;
            }
            replaceExtraKeyGroups(result[0], groupIds == null ? List.of() : groupIds);
            if (input.spendLimits() != null) {
                Amounts limits = input.spendLimits();
                jdbc.update("UPDATE gateway_client_key SET spend_total=?,spend_5h=?,spend_1d=?,spend_7d=? WHERE key_id=?",
                        limits.total(), limits.fiveHours(), limits.oneDay(), limits.sevenDays(), result[0]);
            }
            return result[0];
        });
        return result[0];
    }

    public void resetClientKeySpend(long id, boolean total) {
        requireClientKey(id);
        jdbc.update(total ? "UPDATE gateway_client_key SET quota_reset_at=NOW(3) WHERE key_id=?"
                : "UPDATE gateway_client_key SET window_reset_at=NOW(3) WHERE key_id=?", id);
    }

    private static Amounts amounts(java.sql.ResultSet row, int start) throws java.sql.SQLException {
        return new Amounts(row.getLong(start), row.getLong(start + 1), row.getLong(start + 2), row.getLong(start + 3));
    }

    public void saveClientKeyIps(long id, ClientKeyIpInput input) {
        checkIps(input.ipAllow(), input.ipDeny());
        publish(null, "key.ip", () -> {
            requireClientKey(id);
            jdbc.update("UPDATE gateway_client_key SET ip_allow=?,ip_deny=? WHERE key_id=?",
                    ipText(input.ipAllow()), ipText(input.ipDeny()), id);
            return id;
        });
    }

    public void bulkClientKeys(List<Long> ids, Boolean enabled, Long groupId) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull)
                || (enabled == null && groupId == null)) throw invalid("请选择 1–100 个密钥和操作");
        publish(null, "key.bulk", () -> {
            var group = groupId == null ? null : groups.require(groupId);
            for (long id : new java.util.HashSet<>(ids)) {
                requireClientKey(id);
                jdbc.update("UPDATE api_key SET status=COALESCE(?,status),group_id=COALESCE(?,group_id) WHERE id=?", enabled, groupId, id);
                if (group != null) {
                    jdbc.update("UPDATE gateway_client_key SET account_id=0,account_group=? WHERE key_id=?", group.provider(), id);
                    replaceExtraKeyGroups(id, List.of());
                }
            }
            return 0L;
        });
    }

    private static void checkIps(List<String> allow, List<String> deny) {
        try { IpRules.parse(allow, deny); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
    }

    private static List<String> ipList(String value) {
        return value == null || value.isEmpty() ? List.of() : value.lines().toList();
    }

    private static String ipText(List<String> values) {
        return values == null ? null : String.join("\n", values.stream().map(String::strip).distinct().toList());
    }

    public void deleteClientKey(long id) {
        publish(null, "key.delete", () -> {
            requireClientKey(id);
            // Keep the reserved id and metadata in the existing api_key table for usage history.
            jdbc.update("UPDATE api_key SET status=0 WHERE id=?", id);
            replaceExtraKeyGroups(id, List.of());
            jdbc.update("DELETE FROM gateway_client_key WHERE key_id=?", id);
            return id;
        });
    }

    private void requireClientKey(long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM gateway_client_key WHERE key_id=?", Integer.class, id) == 0) {
            throw missing("密钥不存在");
        }
    }

    private void requirePublicChannel(long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM gateway_account_channel WHERE channel_id=?", Integer.class, id) > 0) {
            throw conflict("请到账号编辑中管理该渠道的模型映射");
        }
    }

    private void requirePublicRoute(long id) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM model_route r JOIN gateway_account_channel a ON a.channel_id=r.channel_id WHERE r.id=?
                """, Integer.class, id) > 0) throw conflict("请到账号编辑中管理该模型映射");
    }

    private record ExistingRoute(long id, long channelId, String upstream, boolean enabled) {}

    public long deleteRoute(long id, long expectedVersion) {
        return publish(expectedVersion, "route.delete", () -> {
            requirePublicRoute(id);
            if (jdbc.update("DELETE FROM model_route WHERE id=?", id) == 0) throw missing("模型绑定不存在");
            return id;
        });
    }

    public long importYaml(String yaml, long expectedVersion) {
        if (yaml == null || yaml.isBlank() || yaml.length() > 128_000) throw invalid("YAML 为空或超过大小限制");
        GatewayProperties properties;
        try {
            var doc = new YamlPropertySourceLoader().load("gateway-import", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
            var environment = new StandardEnvironment();
            doc.forEach(source -> environment.getPropertySources().addLast(source));
            properties = new Binder(ConfigurationPropertySources.from(doc), new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(environment))
                    .bind("gateway", Bindable.of(GatewayProperties.class)).orElseThrow(() -> invalid("缺少 gateway 配置"));
        } catch (IOException | IllegalArgumentException | org.springframework.boot.context.properties.bind.BindException e) {
            throw invalid("YAML 格式或配置字段无效");
        }
        if (properties.getChannels().isEmpty()) throw invalid("至少需要一个渠道");
        return publish(expectedVersion, "config.import", () -> importConfig(properties));
    }

    private long importConfig(GatewayProperties properties) {
        var ids = new java.util.HashSet<Long>();
        for (var channel : properties.getChannels()) {
            if (channel.getId() <= 0 || !ids.add(channel.getId())) throw invalid("渠道 ID 必须为正数且唯一");
            requirePublicChannel(channel.getId());
            var input = new ChannelInput(channel.getName(), parseProtocol(channel.getProtocol()), channel.getBaseUrl(),
                    channel.getApiKey(), channel.getPriority(), channel.getWeight(), channel.getMaxInflight(),
                    channel.getTimeout().toMillis(), channel.isPassthrough(), channel.isEnabled(), channel.isApiKeyInQuery());
            check(input);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM channel WHERE id=?", Integer.class, channel.getId()) == 0) {
                jdbc.update("INSERT INTO channel(id,name,provider,protocol,base_url,group_id) VALUES(?,?,?, ?,?,1)",
                        channel.getId(), channel.getName(), "custom", input.protocol().name(), input.baseUrl());
            }
            writeChannel(channel.getId(), input);
        }
        for (var route : properties.getRoutes()) {
            if (!ids.contains(route.getChannelId())) throw invalid("模型绑定引用未导入的渠道");
            var input = new RouteInput(route.getVirtualModel(), route.getUpstreamModel(), route.getChannelId(),
                    route.getWeight(), route.getPriceRatio());
            check(input);
            List<Long> matching = jdbc.queryForList("""
                    SELECT r.id FROM model_route r JOIN virtual_model m ON m.id=r.virtual_model_id
                    WHERE m.name=? AND r.channel_id=? AND r.upstream_model=?
                    """, Long.class, input.virtualModel(), input.channelId(), input.upstreamModel());
            writeRoute(matching.isEmpty() ? null : matching.get(0), input);
        }
        return 0;
    }

    private synchronized long publish(Long expected, String action, LongSupplier write) {
        RouteTable next = tx.execute(status -> {
            long active = version();
            if (expected != null && active != expected) throw conflict("配置已更新，请刷新后重试");
            long resource = write.getAsLong();
            long published = active + 1;
            jdbc.update("INSERT INTO config_version(version,changed_by) VALUES(?,1)", published);
            jdbc.update("INSERT INTO audit_log(user_id,action,resource,resource_id) VALUES(1,?,'routing',?)",
                    action, resource == 0 ? null : Long.toString(resource));
            return load(published);
        });
        holder.swap(java.util.Objects.requireNonNull(next));
        return next.version();
    }

    private long version() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM config_version", Long.class);
    }

    private RouteTable load(long version) {
        Map<Long, Channel> channels = new HashMap<>();
        jdbc.query("""
                SELECT c.*, k.cipher, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra,'$.apiKeyInQuery')),'false') query_style
                FROM channel c LEFT JOIN channel_key k ON k.channel_id=c.id AND k.label=? AND k.status=1
                """, row -> {
            long id = row.getLong("id");
            byte[] encrypted = row.getBytes("cipher");
            channels.put(id, new Channel(id, row.getString("name"), parseProtocol(row.getString("protocol")),
                    row.getString("base_url"), encrypted == null ? "" : cipher.decrypt(encrypted, "channel:" + id),
                    row.getInt("priority"), row.getInt("weight"), row.getInt("max_inflight"),
                    Duration.ofMillis(row.getLong("timeout_ms")), row.getBoolean("passthrough"),
                    row.getInt("status") == 1, Boolean.parseBoolean(row.getString("query_style"))));
        }, CHANNEL_KEY);
        var routes = jdbc.query("""
                SELECT r.*, m.name, a.account_id, u.provider FROM model_route r JOIN virtual_model m ON m.id=r.virtual_model_id
                LEFT JOIN gateway_account_channel a ON a.channel_id=r.channel_id
                LEFT JOIN gateway_upstream_account u ON u.id=a.account_id
                WHERE r.enabled=1 AND m.status=1 AND (a.account_id IS NULL OR (u.enabled=1 AND u.schedulable=1)) ORDER BY r.id
                """, (row, index) -> new ModelRoute(row.getLong("id"), row.getString("name"),
                java.util.Objects.requireNonNull(channels.get(row.getLong("channel_id"))), row.getString("upstream_model"),
                row.getInt("weight"), row.getDouble("price_ratio"), row.getObject("account_id", Long.class), row.getString("provider")));
        List<ApiKeyView> keys = bootstrap.getKeys().stream().filter(key -> key.getSecret() != null && !key.getSecret().isBlank())
                .map(key -> new ApiKeyView(key.getId(), RouteTable.sha256(key.getSecret()), key.getTenantId(), key.getGroupId(),
                        key.getAllowedModels() == null ? Set.of() : Set.copyOf(key.getAllowedModels()), key.getRpmLimit(), key.getBalanceMicro()))
                .toList();
        var allKeys = new java.util.ArrayList<>(keys);
        var extras = extraKeyGroups();
        var managed = jdbc.query("""
                SELECT k.id,k.key_hash,k.tenant_id,k.group_id,NULLIF(m.account_id,0),m.account_group,m.ip_allow,m.ip_deny,k.rpm_limit,UNIX_TIMESTAMP(k.expires_at),
                       m.spend_total,m.spend_5h,m.spend_1d,m.spend_7d
                FROM api_key k JOIN gateway_client_key m ON k.id=m.key_id
                LEFT JOIN gateway_upstream_account a ON a.id=m.account_id
                LEFT JOIN api_group g ON g.id=k.group_id AND g.account_provider IS NOT NULL
                WHERE k.status=1 AND (g.enabled=1 OR (m.account_group IS NULL AND a.enabled=1) OR EXISTS(
                    SELECT 1 FROM gateway_client_key_group x JOIN api_group selected ON selected.id=x.group_id
                    WHERE x.key_id=k.id AND selected.enabled=1 AND selected.account_provider IS NOT NULL))
                """, (row, index) -> new ApiKeyView(row.getLong(1), row.getString(2), row.getLong(3), row.getLong(4),
                Set.of(), row.getObject(9, Integer.class), null, row.getObject(5, Long.class), row.getString(6),
                IpRules.parse(ipList(row.getString(7)), ipList(row.getString(8))), row.getObject(10, Long.class), amounts(row, 11),
                extras.getOrDefault(row.getLong(1), List.of())));
        var keyIds = new java.util.HashSet<Long>();
        keys.forEach(key -> keyIds.add(key.id()));
        for (var key : managed) {
            if (!keyIds.add(key.id())) throw conflict("配置文件密钥 ID 与管理台密钥冲突");
            allKeys.add(key);
        }
        var privateChannels = Set.copyOf(jdbc.queryForList("SELECT channel_id FROM gateway_account_channel", Long.class));
        Map<Long, Set<Long>> groupAccounts = new HashMap<>();
        Map<Long, List<Long>> groupAccountOrders = new HashMap<>();
        Map<Long, AccountRoutingMode> groupRoutingModes = new HashMap<>();
        for (var group : groups.list()) {
            if (!group.enabled()) continue;
            groupAccounts.put(group.id(), Set.copyOf(group.accountIds()));
            groupRoutingModes.put(group.id(), group.routingMode());
            var order = new java.util.LinkedHashSet<Long>();
            if (group.preferredAccountId() != null) order.add(group.preferredAccountId());
            order.addAll(group.accountOrder());
            if (!order.isEmpty()) groupAccountOrders.put(group.id(), List.copyOf(order));
        }
        return new RouteTable(version, routes, allKeys, channels, privateChannels, groupAccounts, groupAccountOrders, groupRoutingModes);
    }

    private long writeChannel(Long id, ChannelInput input) {
        long channelId = id == null ? insert("INSERT INTO channel(name,provider,protocol,base_url,group_id) VALUES(?,'custom',?,?,1)",
                input.name(), input.protocol().name(), input.baseUrl()) : id;
        jdbc.update("""
                UPDATE channel SET name=?, protocol=?, base_url=?, priority=?, weight=?, max_inflight=?, timeout_ms=?,
                passthrough=?, status=?, extra=JSON_SET(COALESCE(extra,JSON_OBJECT()),'$.apiKeyInQuery',?) WHERE id=?
                """, input.name(), input.protocol().name(), input.baseUrl(), input.priority(), input.weight(),
                input.maxInflight(), input.timeoutMs(), input.passthrough(), input.enabled(),
                input.apiKeyInQuery().toString(), channelId);
        if (input.apiKey() != null && !input.apiKey().isBlank()) {
            byte[] encrypted = cipher.encrypt(input.apiKey(), "channel:" + channelId);
            if (jdbc.update("UPDATE channel_key SET cipher=?,status=1 WHERE channel_id=? AND label=?",
                    encrypted, channelId, CHANNEL_KEY) == 0) {
                jdbc.update("INSERT INTO channel_key(channel_id,label,cipher) VALUES(?,?,?)", channelId, CHANNEL_KEY, encrypted);
            }
        }
        return channelId;
    }

    private long writeRoute(Long id, RouteInput input) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM channel WHERE id=?", Integer.class, input.channelId()) == 0) {
            throw missing("渠道不存在");
        }
        jdbc.update("INSERT INTO virtual_model(name) VALUES(?) ON DUPLICATE KEY UPDATE name=name", input.virtualModel());
        long model = jdbc.queryForObject("SELECT id FROM virtual_model WHERE name=?", Long.class, input.virtualModel());
        if (id == null) {
            return insert("INSERT INTO model_route(virtual_model_id,channel_id,upstream_model,weight,price_ratio) VALUES(?,?,?,?,?)",
                    model, input.channelId(), input.upstreamModel(), input.weight(), input.priceRatio());
        }
        if (jdbc.update("UPDATE model_route SET virtual_model_id=?,channel_id=?,upstream_model=?,weight=?,price_ratio=?,enabled=1 WHERE id=?",
                model, input.channelId(), input.upstreamModel(), input.weight(), input.priceRatio(), id) == 0) throw missing("模型绑定不存在");
        return id;
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }

    private static Protocol parseProtocol(String value) {
        if ("OPENAI".equalsIgnoreCase(value)) return Protocol.OPENAI_CHAT;
        if ("RESPONSES".equalsIgnoreCase(value)) return Protocol.OPENAI_RESPONSES;
        try {
            return Protocol.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("未知上游协议");
        }
    }

    private static void check(ChannelInput input) {
        if (blank(input.name(), 64) || blank(input.baseUrl(), 255) || input.protocol() == null
                || input.apiKey() != null && input.apiKey().getBytes(StandardCharsets.UTF_8).length > 484
                || input.priority() == null || input.priority() < 0 || input.weight() == null || input.weight() < 1
                || input.maxInflight() == null || input.maxInflight() < 1 || input.maxInflight() > 100_000
                || input.timeoutMs() == null || input.timeoutMs() < 100 || input.timeoutMs() > 600_000
                || input.enabled() == null || input.passthrough() == null || input.apiKeyInQuery() == null) {
            throw invalid("渠道字段无效");
        }
        try {
            URI url = URI.create(input.baseUrl());
            if (input.protocol() == Protocol.CODEX_SUBSCRIPTION || input.protocol() == Protocol.WORKBUDDY) {
                String expected = input.protocol() == Protocol.CODEX_SUBSCRIPTION ? AccountAuthorization.CODEX_BASE : AccountAuthorization.workBase("cn");
                boolean legacy = input.protocol() == Protocol.CODEX_SUBSCRIPTION && "https://api.openai.com".equals(input.baseUrl());
                if ((!input.baseUrl().equals(expected) && !legacy) || input.apiKey() != null && !input.apiKey().isBlank()) {
                    throw invalid("账号池渠道通过授权账号提供固定上游地址和凭证");
                }
            }
            if (!Set.of("https", "http").contains(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) throw invalid("上游地址无效");
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("上游地址无效");
        }
    }

    private static void check(RouteInput input) {
        if (blank(input.virtualModel(), 64) || blank(input.upstreamModel(), 128) || input.channelId() == null
                || input.weight() == null || input.weight() < 1 || input.priceRatio() == null
                || !Double.isFinite(input.priceRatio()) || input.priceRatio() <= 0 || input.priceRatio() > 99_999) {
            throw invalid("模型绑定字段无效");
        }
    }

    private static boolean blank(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
    private static ResponseStatusException conflict(String msg) { return new ResponseStatusException(HttpStatus.CONFLICT, msg); }
    private static ResponseStatusException missing(String msg) { return new ResponseStatusException(HttpStatus.NOT_FOUND, msg); }
    private static ResponseStatusException invalid(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
}
