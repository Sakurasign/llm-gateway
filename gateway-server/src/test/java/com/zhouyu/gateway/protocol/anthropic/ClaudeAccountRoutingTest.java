package com.zhouyu.gateway.protocol.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.auth.ApiKeyAuthenticator;
import com.zhouyu.gateway.billing.CostCalculator;
import com.zhouyu.gateway.billing.MultimodalUsageExtractor;
import com.zhouyu.gateway.billing.PricingService;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.IpRules;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.observability.GatewayMetrics;
import com.zhouyu.gateway.quota.QuotaService;
import com.zhouyu.gateway.relay.RequestRewriter;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.ChannelGates;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.DynamicWeightManager;
import com.zhouyu.gateway.routing.RouteResolver;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import com.zhouyu.gateway.routing.SessionKeyExtractor;
import com.zhouyu.gateway.routing.SmoothWeightedRoundRobin;
import com.zhouyu.gateway.usage.UsagePublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaudeAccountRoutingTest {
    @ParameterizedTest
    @CsvSource({"0,false", "401,false", "403,false", "429,false", "503,false", "429,true"})
    void followsGroupOrderAndRetriesBeforeWritingToTheClient(int failure, boolean multiGroup) {
        var mapper = new ObjectMapper();
        var key = multiGroup
                ? new ApiKeyView(12, "hash", 1, 4, Set.of(), null, null, null, AccountPool.CODEX, IpRules.NONE,
                    null, com.zhouyu.gateway.quota.KeySpend.Amounts.ZERO, List.of(5L))
                : new ApiKeyView(12, "hash", 1, 5, Set.of(), null, null, null, AccountPool.ANTHROPIC, IpRules.NONE);
        var tables = new RouteTableHolder();
        tables.swap(new RouteTable(1, List.of(route(1), route(2)), List.of(key), Map.of(), Set.of(),
                Map.of(5L, Set.of(1L, 2L)), Map.of(5L, List.of(2L, 1L))));
        var accounts = mock(AccountPool.class);
        var backup = lease(mapper, 1);
        var preferred = lease(mapper, 2);
        when(accounts.acquireForAccount(AccountPool.ANTHROPIC, 1, "claude-fixture", Set.of())).thenReturn(backup);
        var firstAttempt = when(accounts.acquireForAccount(AccountPool.ANTHROPIC, 2, "claude-fixture", Set.of()));
        if (failure == 0) firstAttempt.thenThrow(new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE));
        else firstAttempt.thenReturn(preferred);

        var attempted = new ArrayList<String>();
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
            assertThat(request.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
            var token = request.headers().getFirst("x-api-key");
            attempted.add(token);
            if ("fixture-2".equals(token)) {
                return Mono.just(ClientResponse.create(HttpStatus.valueOf(failure)).header("Retry-After", "60").body("unavailable").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"id\":\"msg_backup\",\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}").build());
        }).build());
        var auth = mock(ApiKeyAuthenticator.class);
        when(auth.authenticate(any())).thenReturn(key);
        var quota = mock(QuotaService.class);
        when(quota.reserve(eq(1L), eq(12L), anyString(), anyLong())).thenReturn(Mono.just(QuotaService.ReserveResult.OK));
        when(quota.settle(eq(1L), anyString(), anyLong(), anyString())).thenReturn(Mono.just(1L));
        when(quota.release(eq(1L), anyString())).thenReturn(Mono.just(1L));
        var pricing = mock(PricingService.class);
        var costs = mock(CostCalculator.class);
        when(costs.calculate(isNull(), anyInt(), anyInt(), anyInt(), any())).thenReturn(new CostCalculator.Cost(0, null));
        var breaker = new CircuitBreaker();
        var dynamic = mock(DynamicWeightManager.class);
        var resolver = new RouteResolver(new SmoothWeightedRoundRobin(), breaker, new ChannelGates(), dynamic);
        var controller = new AnthropicController(auth, accounts, new AccountRouting(accounts, new SessionKeyExtractor()), tables, resolver, quota, new RequestRewriter(mapper), relay,
                breaker, mapper, null, null, null, mock(GatewayMetrics.class), mock(UsagePublisher.class),
                mock(SessionKeyExtractor.class), null, pricing, costs, null, dynamic, new MultimodalUsageExtractor(mapper));
        var request = MockServerHttpRequest.post("/v1/messages").header("x-api-key", "client-credential")
                .body("{\"model\":\"claude-alias\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        var response = new MockServerHttpResponse();
        controller.messages(request, response).block(Duration.ofSeconds(5));

        assertThat(response.getBodyAsString().block()).contains("msg_backup");
        assertThat(attempted).containsExactlyElementsOf(failure == 0 ? List.of("fixture-1") : List.of("fixture-2", "fixture-1"));
        var order = inOrder(accounts);
        order.verify(accounts).acquireForAccount(AccountPool.ANTHROPIC, 2, "claude-fixture", Set.of());
        if (failure == 429) {
            order.verify(accounts).result(eq(preferred), eq("claude-fixture"), eq(429), eq(0), eq(0), eq(false),
                    longThat(value -> value > Instant.now().getEpochSecond()));
        }
        order.verify(accounts).acquireForAccount(AccountPool.ANTHROPIC, 1, "claude-fixture", Set.of());
        order.verify(accounts).result(backup, "claude-fixture", 200, 4, 2, false, 0);
    }

    private static AccountPool.Lease lease(ObjectMapper mapper, long id) {
        return new AccountPool.Lease(id, AccountPool.ANTHROPIC, "api", mapper.createObjectNode()
                .put("api_key", "fixture-" + id), new AtomicInteger(1));
    }

    private static ModelRoute route(long id) {
        var channel = new Channel(id, "Claude " + id, Protocol.ANTHROPIC, "https://api.anthropic.com", "", 100, 1, 1,
                Duration.ofSeconds(1), false, true);
        return new ModelRoute(id, "claude-alias", channel, "claude-fixture", 1, 1, id, AccountPool.ANTHROPIC);
    }
}
