package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.config.CredentialCipher;
import com.zhouyu.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "GATEWAY_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class AccountPoolMySqlTest {
    @Test
    void switchesSubscriptionBindingAndKeepsPerAccountUsage() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("GATEWAY_TEST_MYSQL_URL"),
                System.getenv().getOrDefault("GATEWAY_TEST_MYSQL_USER", "root"),
                System.getenv().getOrDefault("GATEWAY_TEST_MYSQL_PASSWORD", "")));
        var props = new GatewayProperties();
        var key = new GatewayProperties.KeyConfig();
        long keyId = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 51);
        key.setId(keyId);
        key.setSecret("synthetic-key");
        props.setKeys(List.of(key));
        var mapper = new ObjectMapper();
        var cipher = new CredentialCipher("", "synthetic-jwt-secret-for-account-test");
        var proxies = new AccountProxies(jdbc, cipher, new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())));
        var accounts = new AccountPool(jdbc, cipher, mapper, props, mock(AccountAuthorization.class), proxies, 1, false);
        accounts.accountSchema().run(null);
        var ids = new ArrayList<Long>();
        try {
            for (String name : List.of("account-a", "account-b")) {
                ids.add(accounts.save(new AccountPool.Registration(AccountPool.CODEX, UUID.randomUUID().toString(),
                        name, "chatgpt", Instant.now().plusSeconds(3600).getEpochSecond(),
                        mapper.createObjectNode().put("access_token", name + "-token").put("codex_oauth", true))));
            }
            long first = ids.get(0), second = ids.get(1);
            assertThat(accounts.usageStats(first).rows()).isEmpty();
            verifyUsageStats(jdbc, accounts, first, second, keyId);
            accounts.bind(keyId, first);
            try (var lease = accounts.acquire(AccountPool.CODEX, keyId, "gpt-qa", Set.of(), null)) {
                assertThat(lease.id()).isEqualTo(first);
                assertThat(lease.credential().path("access_token").asText()).isEqualTo("account-a-token");
                assertThatThrownBy(() -> accounts.acquire(AccountPool.CODEX, keyId, "gpt-qa", Set.of(), null))
                        .isInstanceOf(GatewayException.class);
                accounts.result(lease, "gpt-qa", 200, 21, 8, false, 0);
            }
            accounts.bind(keyId, second);
            try (var lease = accounts.acquire(AccountPool.CODEX, keyId, "gpt-qa", Set.of(), first)) {
                assertThat(lease.id()).isEqualTo(second);
                accounts.result(lease, "gpt-qa", 200, 13, 5, false, 0);
            }
            Map<Long, AccountPool.Summary> stored = accounts.summaries().stream()
                    .collect(Collectors.toMap(AccountPool.Summary::id, Function.identity()));
            assertThat(stored.get(first).concurrency()).isEqualTo(1);
            assertThat(stored.get(first).requests()).isEqualTo(1);
            assertThat(stored.get(first).inputTokens()).isEqualTo(21);
            assertThat(stored.get(second).requests()).isEqualTo(1);
            assertThat(stored.get(second).outputTokens()).isEqualTo(5);
            long resetAt = Instant.now().plusSeconds(7200).getEpochSecond();
            try (var lease = accounts.acquireForAccount(AccountPool.CODEX, first, "gpt-qa", Set.of())) {
                accounts.result(lease, "gpt-qa", 429, 0, 0, false, resetAt);
            }
            assertThat(accounts.summaries().stream().filter(a -> a.id() == first).findFirst().orElseThrow().cooldownUntil())
                    .isEqualTo(resetAt);
            assertThatThrownBy(() -> accounts.acquireForAccount(AccountPool.CODEX, first, "gpt-qa", Set.of()))
                    .isInstanceOf(GatewayException.class).hasMessage("账号冷却中");
            jdbc.update("UPDATE gateway_upstream_account SET cooldown_until=UNIX_TIMESTAMP()-1 WHERE id=?", first);
            try (var lease = accounts.acquireForAccount(AccountPool.CODEX, first, "gpt-qa", Set.of())) {
                assertThat(lease.id()).isEqualTo(first);
            }
            verifyAccountConcurrency(jdbc, accounts, first, keyId + 1);
            accounts.status(second, false);
            assertThatThrownBy(() -> accounts.acquire(AccountPool.CODEX, keyId, "gpt-qa", Set.of(), null))
                    .isInstanceOf(GatewayException.class);
        } finally {
            for (long id : ids) accounts.remove(id);
        }
    }

    private static void verifyAccountConcurrency(JdbcTemplate jdbc, AccountPool accounts, long id, long channelId) {
        try {
            jdbc.update("""
                    INSERT INTO channel(id,name,provider,protocol,base_url,group_id,max_inflight)
                    VALUES (?,?,'openai','CODEX_SUBSCRIPTION','https://example.test',1,2)
                    """, channelId, "account-capacity-" + id);
            jdbc.update("INSERT INTO gateway_account_channel(account_id,channel_id) VALUES (?,?)", id, channelId);
            try (var first = accounts.acquireForTest(id, "gpt-qa"); var second = accounts.acquireForTest(id, "gpt-qa")) {
                assertThat(accounts.summaries()).anyMatch(a -> a.id() == id && a.concurrency() == 2 && a.inflight() == 2);
                assertThatThrownBy(() -> accounts.acquireForAccount(AccountPool.CODEX, id, "gpt-qa", Set.of()))
                        .isInstanceOf(GatewayException.class);
                jdbc.update("UPDATE channel SET max_inflight=1000 WHERE id=?", channelId);
                try (var third = accounts.acquireForAccount(AccountPool.CODEX, id, "gpt-qa", Set.of())) {
                    assertThat(accounts.summaries()).anyMatch(a -> a.id() == id && a.concurrency() == 1000 && a.inflight() == 3);
                }
            }
        } finally {
            jdbc.update("DELETE FROM gateway_account_channel WHERE account_id=?", id);
            jdbc.update("DELETE FROM channel WHERE id=?", channelId);
        }
    }

    private static void verifyUsageStats(JdbcTemplate jdbc, AccountPool accounts, long first, long second, long keyId) {
        long firstChannel = keyId + 1;
        long secondChannel = keyId + 2;
        String prefix = "qa-" + keyId + "-";
        try {
            jdbc.update("INSERT INTO gateway_account_channel(account_id,channel_id) VALUES (?,?),(?,?)",
                    first, firstChannel, second, secondChannel);
            String insert = """
                    INSERT INTO relay_log(request_id,tenant_id,api_key_id,virtual_model,channel_id,protocol_in,protocol_out,gateway_stream,
                        status_code,input_tokens,output_tokens,cost_micro,total_ms,created_at)
                    VALUES (?,1,?,'usage-fixture',?,'OPENAI_RESPONSES','CODEX_SUBSCRIPTION',0,200,21,8,2000,450,
                        DATE_SUB(CURRENT_DATE, INTERVAL ? DAY))
                    """;
            jdbc.update(insert, prefix + "current", keyId, firstChannel, 0);
            jdbc.update(insert, prefix + "old", keyId, firstChannel, 30);
            jdbc.update(insert, prefix + "other", keyId, secondChannel, 1);
            var stats = accounts.usageStats(first);
            assertThat(stats.rows()).singleElement().isEqualTo(
                    new AccountPool.UsageRow(stats.today(), "usage-fixture", 1, 21, 8, 2000, 450, 1));
            assertThat(accounts.usageStats(second).rows()).singleElement().satisfies(row ->
                    assertThat(row.day()).isNotEqualTo(stats.today()));
        } finally {
            jdbc.update("DELETE FROM relay_log WHERE request_id IN (?,?,?)", prefix + "current", prefix + "old", prefix + "other");
            jdbc.update("DELETE FROM gateway_account_channel WHERE channel_id IN (?,?)", firstChannel, secondChannel);
        }
    }
}
