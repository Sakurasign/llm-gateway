package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.domain.AccountRoutingMode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

@Component
public class AccountGroups {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AccountGroups(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void initialize() {
        var columns = jdbc.queryForList("""
                SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='api_group'
                """, String.class);
        if (!columns.contains("account_provider")) jdbc.execute("ALTER TABLE api_group ADD account_provider VARCHAR(24)");
        if (!columns.contains("automatic")) jdbc.execute("ALTER TABLE api_group ADD automatic BOOLEAN NOT NULL DEFAULT FALSE");
        if (!columns.contains("enabled")) jdbc.execute("ALTER TABLE api_group ADD enabled BOOLEAN NOT NULL DEFAULT TRUE");
        if (!columns.contains("sort_order")) jdbc.execute("ALTER TABLE api_group ADD sort_order INT NOT NULL DEFAULT 100");
        if (!columns.contains("preferred_account_id")) jdbc.execute("ALTER TABLE api_group ADD preferred_account_id BIGINT");
        if (!columns.contains("account_order")) jdbc.execute("ALTER TABLE api_group ADD account_order JSON");
        if (!columns.contains("routing_mode")) jdbc.execute("ALTER TABLE api_group ADD routing_mode VARCHAR(12) NOT NULL DEFAULT 'ORDER'");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway_account_group_member (
                    group_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                    PRIMARY KEY(group_id, account_id), KEY idx_member_account(account_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway_client_key_group (
                    key_id BIGINT NOT NULL, group_id BIGINT NOT NULL, position INT NOT NULL,
                    PRIMARY KEY(key_id, group_id), KEY idx_key_group(group_id)
                ) ENGINE=InnoDB
                """);
        insertDefault(AccountPool.CODEX, "ChatGPT 订阅", 10);
        insertDefault(AccountPool.WORKBUDDY, "WorkBuddy", 20);
        insertDefault(AccountPool.ANTHROPIC, "Claude", 30);
        jdbc.update("""
                UPDATE api_key k JOIN gateway_client_key m ON m.key_id=k.id
                JOIN api_group g ON g.tenant_id=k.tenant_id AND g.automatic=1 AND g.account_provider=m.account_group
                SET k.group_id=g.id WHERE k.group_id=1 AND m.account_id=0
                """);
    }

    private void insertDefault(String provider, String name, int order) {
        jdbc.update("""
                INSERT INTO api_group(tenant_id,name,account_provider,automatic,sort_order)
                SELECT 1,?,?,1,? FROM DUAL WHERE NOT EXISTS (
                    SELECT 1 FROM api_group WHERE tenant_id=1 AND account_provider=? AND automatic=1
                )
                """, name, provider, order, provider);
    }

    public record Group(long id, String name, String description, String provider, boolean automatic,
                        boolean enabled, int sortOrder, List<Long> accountIds, int activeAccounts, int keyCount,
                        Long preferredAccountId, List<Long> accountOrder, AccountRoutingMode routingMode) {}
    public record Input(String name, String description, String provider, Boolean enabled, Integer sortOrder,
                        List<Long> accountIds, AccountRoutingMode routingMode) {}
    private record Member(long groupId, long accountId, boolean active) {}

    public List<Group> list() {
        var members = jdbc.query("""
                SELECT g.id,a.id,a.enabled AND a.schedulable FROM api_group g
                JOIN gateway_upstream_account a ON a.provider=g.account_provider
                LEFT JOIN gateway_account_group_member m ON m.account_id=a.id AND m.group_id=g.id
                WHERE g.tenant_id=1 AND (g.automatic=1 OR m.account_id IS NOT NULL)
                ORDER BY a.id
                """, (r, i) -> new Member(r.getLong(1), r.getLong(2), r.getBoolean(3))).stream()
                .collect(java.util.stream.Collectors.groupingBy(Member::groupId));
        return jdbc.query("""
                SELECT g.id,g.name,g.description,g.account_provider,g.automatic,g.enabled,g.sort_order,
                    (SELECT COUNT(*) FROM api_key k JOIN gateway_client_key m ON m.key_id=k.id WHERE k.group_id=g.id OR EXISTS(
                      SELECT 1 FROM gateway_client_key_group x WHERE x.key_id=k.id AND x.group_id=g.id)), g.preferred_account_id, g.account_order, g.routing_mode
                FROM api_group g WHERE g.tenant_id=1 AND g.account_provider IS NOT NULL ORDER BY g.sort_order,g.id
                """, (r, i) -> {
                    long id = r.getLong(1);
                    var assigned = members.getOrDefault(id, List.of());
                    Long preferred = r.getObject(9, Long.class);
                    var accountIds = assigned.stream().map(Member::accountId).toList();
                    return new Group(id, r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5),
                            r.getBoolean(6), r.getInt(7), accountIds,
                            (int) assigned.stream().filter(Member::active).count(), r.getInt(8),
                            preferred != null && accountIds.contains(preferred) ? preferred : null,
                            readOrder(r.getString(10)).stream().filter(accountIds::contains).toList(), AccountRoutingMode.valueOf(r.getString(11)));
                });
    }

    public void preferAccount(long groupId, Long accountId) {
        Group group = require(groupId);
        if (accountId != null && !group.accountIds().contains(accountId)) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "首选账号必须属于此分组");
        }
        jdbc.update("UPDATE api_group SET preferred_account_id=? WHERE id=?", accountId, groupId);
    }

    public void reorderAccounts(long groupId, List<Long> accountIds) {
        Group group = require(groupId);
        if (accountIds == null) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请提供账号顺序");
        if (accountIds.isEmpty()) {
            jdbc.update("UPDATE api_group SET account_order=NULL,preferred_account_id=NULL WHERE id=?", groupId);
            return;
        }
        var members = new HashSet<>(group.accountIds());
        if (accountIds.size() != members.size() || !new HashSet<>(accountIds).equals(members)) {
            throw AccountPool.problem(HttpStatus.CONFLICT, "分组成员已变化，请刷新后重试");
        }
        jdbc.update("UPDATE api_group SET account_order=? WHERE id=?", accountIds.toString(), groupId);
    }

    private List<Long> readOrder(String order) {
        if (order == null) return List.of();
        try {
            return mapper.readValue(order, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid account order", e);
        }
    }

    public long defaultId(String provider) {
        return jdbc.query("SELECT id FROM api_group WHERE tenant_id=1 AND account_provider=? AND automatic=1",
                (r, i) -> r.getLong(1), provider).stream().findFirst().orElseThrow(AccountGroups::missing);
    }

    public Group require(long id) {
        return list().stream().filter(g -> g.id() == id).findFirst().orElseThrow(AccountGroups::missing);
    }

    public long save(Long id, Input input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 64
                || input.description() != null && input.description().length() > 255
                || input.sortOrder() == null || input.sortOrder() < 0 || input.enabled() == null) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请填写分组名称、有效排序和状态");
        }
        Group existing = id == null ? null : require(id);
        String provider = input.provider();
        if (!AccountPool.CODEX.equals(provider) && !AccountPool.WORKBUDDY.equals(provider)
                && !AccountPool.ANTHROPIC.equals(provider)) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择有效平台");
        }
        if (existing != null && !provider.equals(existing.provider())) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "分组平台创建后固定");
        }
        String name = input.name().strip();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM api_group WHERE tenant_id=1 AND account_provider=? AND name=? AND id<>?",
                Integer.class, provider, name, id == null ? 0L : id) > 0) {
            throw AccountPool.problem(HttpStatus.CONFLICT, "该平台已有同名分组");
        }
        AccountRoutingMode mode = input.routingMode();
        if (mode == null) mode = existing == null ? AccountRoutingMode.ORDER : existing.routingMode();
        boolean automatic = existing != null && existing.automatic();
        Long[] accounts = input.accountIds() == null ? new Long[0] : input.accountIds().toArray(Long[]::new);
        if (!automatic && accounts.length > 0) {
            List<Long> eligible = jdbc.queryForList("SELECT id FROM gateway_upstream_account WHERE provider=?", Long.class, provider);
            if (!new HashSet<>(eligible).containsAll(java.util.Arrays.asList(accounts))) {
                throw AccountPool.problem(HttpStatus.BAD_REQUEST, "分组成员必须属于同一平台");
            }
        }
        long groupId;
        if (id == null) {
            jdbc.update("INSERT INTO api_group(tenant_id,name,description,account_provider,enabled,sort_order,routing_mode) VALUES(1,?,?,?,?,?,?)",
                    name, input.description(), provider, input.enabled(), input.sortOrder(), mode.name());
            groupId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbc.update("UPDATE api_group SET name=?,description=?,enabled=?,sort_order=?,routing_mode=? WHERE id=?",
                    name, input.description(), input.enabled(), input.sortOrder(), mode.name(), id);
            groupId = id;
        }
        if (!automatic && input.accountIds() != null) {
            if (existing != null && existing.preferredAccountId() != null && !input.accountIds().contains(existing.preferredAccountId())) {
                jdbc.update("UPDATE api_group SET preferred_account_id=NULL WHERE id=?", groupId);
            }
            jdbc.update("DELETE FROM gateway_account_group_member WHERE group_id=?", groupId);
            for (long accountId : new HashSet<>(input.accountIds())) {
                jdbc.update("INSERT INTO gateway_account_group_member(group_id,account_id) VALUES(?,?)", groupId, accountId);
            }
        }
        return groupId;
    }

    public void assign(long accountId, String provider, List<Long> groupIds) {
        if (groupIds == null) return;
        var eligible = list().stream().filter(g -> provider.equals(g.provider()) && !g.automatic()).map(Group::id).toList();
        var unique = new HashSet<>(groupIds);
        if (!eligible.containsAll(unique)) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择此平台的自定义分组");
        jdbc.update("DELETE FROM gateway_account_group_member WHERE account_id=?", accountId);
        for (long groupId : unique) {
            jdbc.update("INSERT INTO gateway_account_group_member(group_id,account_id) VALUES(?,?)", groupId, accountId);
        }
        jdbc.update("""
                UPDATE api_group g LEFT JOIN gateway_account_group_member m ON m.group_id=g.id AND m.account_id=?
                SET g.preferred_account_id=NULL WHERE g.preferred_account_id=? AND g.automatic=0 AND m.account_id IS NULL
                """, accountId, accountId);
    }

    public void removeAccount(long accountId) {
        jdbc.update("UPDATE api_group SET preferred_account_id=NULL WHERE preferred_account_id=?", accountId);
        jdbc.update("DELETE FROM gateway_account_group_member WHERE account_id=?", accountId);
    }

    public void delete(long id) {
        Group group = require(id);
        if (group.automatic() || group.keyCount() > 0) {
            throw AccountPool.problem(HttpStatus.CONFLICT, group.automatic() ? "默认分组不能删除" : "请先将密钥移到其他分组");
        }
        jdbc.update("DELETE FROM gateway_account_group_member WHERE group_id=?", id);
        jdbc.update("DELETE FROM api_group WHERE id=?", id);
    }

    private static RuntimeException missing() {
        return AccountPool.problem(HttpStatus.NOT_FOUND, "分组不存在");
    }
}
