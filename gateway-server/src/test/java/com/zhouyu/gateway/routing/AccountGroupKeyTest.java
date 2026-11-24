package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.IpRules;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountGroupKeyTest {
    @Test
    void groupKeysSeeOnlyTheirGroupWhileLegacyKeysKeepTheirScope() {
        var first = route(10, "WORKBUDDY");
        var second = route(11, "WORKBUDDY");
        var codex = route(12, "CODEX_SUBSCRIPTION");
        var shared = route(13, null);
        var group = key(null, "WORKBUDDY");
        var legacy = key(10L, null);
        var configured = key(null, null);
        var table = new RouteTable(1, List.of(first, second, codex, shared), List.of(group), java.util.Map.of(),
                Set.of(), java.util.Map.of(group.groupId(), Set.of(10L, 12L)));

        assertThat(table.candidates("alias", group)).extracting(RouteTable.Candidate::route).containsExactly(first);
        assertThat(table.candidates("alias", legacy)).extracting(RouteTable.Candidate::route).containsExactly(first);
        assertThat(table.candidates("alias", configured)).extracting(RouteTable.Candidate::route).containsExactly(shared);
    }

    @Test
    void multiGroupKeyPreservesGroupOrderSkipsDisabledGroupsAndDeduplicatesRoutes() {
        var work = route(10, "WORKBUDDY");
        var codex = route(20, "CODEX_SUBSCRIPTION");
        var claude = route(30, "ANTHROPIC");
        var excluded = route(40, "ANTHROPIC");
        var key = new ApiKeyView(1, "hash", 1, 1, Set.of(), null, null, null, "WORKBUDDY", IpRules.NONE,
                null, com.zhouyu.gateway.quota.KeySpend.Amounts.ZERO, List.of(3L, 2L, 5L, 6L));
        var table = new RouteTable(1, List.of(work, codex, claude, excluded), List.of(key), java.util.Map.of(), Set.of(),
                java.util.Map.of(1L, Set.of(10L), 2L, Set.of(20L), 3L, Set.of(30L), 6L, Set.of(10L)));

        assertThat(table.candidates("alias", key)).extracting(RouteTable.Candidate::route).containsExactly(work, claude, codex);
        assertThat(table.candidates("alias", key)).extracting(RouteTable.Candidate::groupId).containsExactly(1L, 3L, 2L);
    }

    @Test
    void ipRulesMatchBothFamiliesAndDenyTakesPrecedence() {
        var rules = IpRules.parse(List.of("192.0.2.0/25", "2001:db8::/32"), List.of("192.0.2.10", "2001:db8:1::/48"));
        assertThat(rules.permits(peer("192.0.2.127"))).isTrue();
        assertThat(rules.permits(peer("192.0.2.128"))).isFalse();
        assertThat(rules.permits(peer("192.0.2.10"))).isFalse();
        assertThat(rules.permits(peer("2001:db8:2::1"))).isTrue();
        assertThat(rules.permits(peer("2001:db8:1::1"))).isFalse();
        assertThat(rules.permits(null)).isFalse();
        assertThat(IpRules.NONE.permits(null)).isTrue();
        assertThat(IpRules.parse(List.of("::ffff:127.0.0.1"), null).permits(peer("127.0.0.1"))).isTrue();
        assertThatThrownBy(() -> IpRules.parse(List.of("127.0.0.1/33"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpRules.parse(List.of("example.test"), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private static InetSocketAddress peer(String address) {
        return new InetSocketAddress(address, 12345);
    }

    private static ApiKeyView key(Long accountId, String group) {
        return new ApiKeyView(1, "test-hash", 1, 1, Set.of(), null, null, accountId, group, IpRules.NONE);
    }

    private static ModelRoute route(long id, String group) {
        var channel = new Channel(id, "channel-" + id, Protocol.OPENAI_CHAT, "http://127.0.0.1", "", 100, 1, 4,
                Duration.ofSeconds(1), false, true);
        return new ModelRoute(id, "alias", channel, "model", 1, 1, group == null ? null : id, group);
    }
}
