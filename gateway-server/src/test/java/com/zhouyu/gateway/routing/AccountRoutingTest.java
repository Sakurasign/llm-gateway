package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.accounts.CodexAccountStatus.Window;
import com.zhouyu.gateway.domain.AccountRoutingMode;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.IpRules;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountRoutingTest {
    private final AccountPool accounts = mock(AccountPool.class);
    private final AccountRouting routing = new AccountRouting(accounts, new SessionKeyExtractor());
    private final ApiKeyView key = key(12);
    private final RouteTable table = new RouteTable(1, List.of(route(1), route(2), route(3)), List.of(key),
            Map.of(), Set.of(), Map.of(5L, Set.of(1L, 2L, 3L)), Map.of(5L, List.of(1L, 2L, 3L)),
            Map.of(5L, AccountRoutingMode.SMART));

    @Test
    void smartUsesTheLongestActiveWindowAndHandlesMissingShortWindows() {
        long now = Instant.now().getEpochSecond();
        when(accounts.cachedCodexWindows(1)).thenReturn(List.of(new Window(18_000, 70.0, now + 60),
                new Window(604_800, 80.0, now + 7_200)));
        when(accounts.cachedCodexWindows(2)).thenReturn(List.of(new Window(604_800, 88.0, now + 3_600)));
        when(accounts.cachedCodexWindows(3)).thenReturn(List.of());
        assertThat(ids(plan(key))).containsExactly(2L, 1L, 3L);
    }

    @Test
    void smartKeepsAffinityWithinOneKeyAndMovesExhaustedAccountsBack() {
        long now = Instant.now().getEpochSecond();
        routing.remember(plan(key).session(), 3L);
        assertThat(ids(plan(key))).containsExactly(3L, 1L, 2L);
        assertThat(ids(plan(key(13)))).containsExactly(1L, 2L, 3L);
        when(accounts.cachedCodexWindows(3)).thenReturn(List.of(new Window(18_000, 100.0, now + 60)));
        assertThat(ids(plan(key))).containsExactly(1L, 2L, 3L);
        when(accounts.cachedCodexWindows(3)).thenReturn(List.of(new Window(18_000, 100.0, now - 1)));
        assertThat(ids(plan(key))).containsExactly(3L, 1L, 2L);
    }

    private AccountRouting.Plan plan(ApiKeyView key) {
        return routing.plan(table, key, "alias", table.candidates("alias", key),
                MockServerHttpRequest.post("/v1/responses").header("session_id", "fixture").build(), new byte[0]);
    }

    private static List<Long> ids(AccountRouting.Plan plan) {
        return plan.candidates().stream().map(c -> c.route().accountId()).toList();
    }

    private static ApiKeyView key(long id) {
        return new ApiKeyView(id, "hash", 1, 5, Set.of(), null, null, null, AccountPool.CODEX, IpRules.NONE);
    }

    private static ModelRoute route(long id) {
        var channel = new Channel(id, "channel-" + id, Protocol.CODEX_SUBSCRIPTION, "https://chatgpt.com", "",
                100, 1, 1, Duration.ofSeconds(1), false, true);
        return new ModelRoute(id, "alias", channel, "upstream", 1, 1, id, AccountPool.CODEX);
    }
}
