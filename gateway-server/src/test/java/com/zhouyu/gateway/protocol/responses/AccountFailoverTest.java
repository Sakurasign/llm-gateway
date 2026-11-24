package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.auth.ApiKeyAuthenticator;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.AccountRoutingMode;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.IpRules;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.billing.CostCalculator;
import com.zhouyu.gateway.billing.PricingService;
import com.zhouyu.gateway.quota.QuotaService;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.SessionKeyExtractor;
import com.zhouyu.gateway.routing.ChannelGates;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.DynamicWeightManager;
import com.zhouyu.gateway.routing.RouteResolver;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import com.zhouyu.gateway.routing.SmoothWeightedRoundRobin;
import com.zhouyu.gateway.usage.UsageEvent;
import com.zhouyu.gateway.usage.UsagePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountFailoverTest {
    @Test
    void groupedResponsesUsePreferredAccountThenOrderedBackupAndReturnAfterRecovery() throws Exception {
        var mapper = new ObjectMapper();
        var key = new ApiKeyView(12, "hash", 1, 5, Set.of(), null, null, null, AccountPool.CODEX, IpRules.NONE);
        var routes = List.of(route(11), route(10), route(14), route(12), route(13), route(15));
        var tables = new RouteTableHolder();
        tables.swap(new RouteTable(1, routes, List.of(key), Map.of(), Set.of(),
                Map.of(5L, Set.of(10L, 11L, 12L, 13L, 14L)), Map.of(5L, List.of(14L, 13L, 12L))));
        var accounts = mock(AccountPool.class);
        var now = Instant.now().getEpochSecond();
        var resumeAt = new AtomicLong();
        var clock = new AtomicLong(now);
        for (long id : List.of(10L, 11L)) {
            when(accounts.acquireForAccount(AccountPool.CODEX, id, "gpt-upstream", Set.of()))
                    .thenThrow(new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE));
        }
        for (long id : List.of(12L, 13L, 14L)) {
            when(accounts.acquireForAccount(AccountPool.CODEX, id, "gpt-upstream", Set.of())).thenAnswer(invocation -> {
                if (id == 14 && resumeAt.get() > clock.get()) throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE);
                return new AccountPool.Lease(id, AccountPool.CODEX, "chatgpt", mapper.createObjectNode()
                        .put("access_token", "fixture-" + id), new AtomicInteger(1));
            });
        }
        doAnswer(invocation -> {
            resumeAt.set(invocation.getArgument(6));
            return null;
        }).when(accounts).result(argThat(lease -> lease.id() == 14), eq("gpt-upstream"), eq(429), eq(0), eq(0), eq(false), anyLong());
        var attempted = new ArrayList<String>();
        var responseCount = new AtomicInteger();
        long reset = now + 7200;
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(request -> {
            var header = request.headers().getFirst("Authorization");
            attempted.add(header);
            if (responseCount.incrementAndGet() == 2) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60").body("{\"error\":{\"type\":\"usage_limit_reached\",\"resets_at\":" + reset + "}}").build());
            }
            return Mono.just(completedResponse());
        }).build());
        var usage = mock(UsagePublisher.class);
        var controller = controller(mapper, key, tables, accounts, relay, usage);

        invoke(controller, false);
        invoke(controller, true);
        assertThat(resumeAt.get()).isEqualTo(reset);
        invoke(controller, false);
        clock.set(reset);
        invoke(controller, false);
        assertThat(attempted).containsExactly("Bearer fixture-14", "Bearer fixture-14", "Bearer fixture-13",
                "Bearer fixture-13", "Bearer fixture-14");
        verify(accounts, never()).acquireForAccount(eq(AccountPool.CODEX), eq(12L), any(), anySet());
        verify(accounts, never()).acquireForAccount(eq(AccountPool.CODEX), eq(15L), any(), anySet());
        var capture = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usage, times(4)).publish(capture.capture(), anyBoolean());
        var failover = capture.getAllValues().get(1);
        assertThat(failover.retryCount()).isEqualTo(1);
        var trace = mapper.readTree(failover.routeTrace());
        assertThat(trace.get(0).path("accountId").asLong()).isEqualTo(14);
        assertThat(trace.get(0).path("status").asInt()).isEqualTo(429);
        assertThat(trace.get(1).path("accountId").asLong()).isEqualTo(13);
        assertThat(trace.get(1).path("status").asInt()).isEqualTo(200);
        var duringCooldown = mapper.readTree(capture.getAllValues().get(2).routeTrace());
        assertThat(duringCooldown.get(0).path("accountId").asLong()).isEqualTo(14);
        assertThat(duringCooldown.get(0).path("reason").asText()).isNotBlank();
        assertThat(duringCooldown.get(1).path("status").asInt()).isEqualTo(200);
    }

    @Test
    void recordsEachAttemptWhenAllUpstreamsFail() throws Exception {
        var usage = mock(UsagePublisher.class);
        var attempted = new ArrayList<String>();
        var controller = twoAccountController(attempted, ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build(),
                () -> ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build(), AccountRoutingMode.ORDER, usage);
        var response = new MockServerHttpResponse();
        controller.openAi(request(false), response).block(Duration.ofSeconds(5));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        var capture = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usage).publish(capture.capture(), anyBoolean());
        var event = capture.getValue();
        assertThat(event.costMicro()).isZero();
        assertThat(event.statusCode()).isEqualTo(503);
        assertThat(event.retryCount()).isEqualTo(1);
        var trace = new ObjectMapper().readTree(event.routeTrace());
        assertThat(trace.size()).isEqualTo(2);
        assertThat(trace.get(0).path("status").asInt()).isEqualTo(429);
        assertThat(trace.get(1).path("status").asInt()).isEqualTo(503);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503, 403})
    void groupedResponsesHandleUpstreamErrorsBeforeOutput(int status) {
        var attempted = new ArrayList<String>();
        var controller = twoAccountController(attempted, ClientResponse.create(HttpStatus.valueOf(status)).build());
        var response = new MockServerHttpResponse();
        controller.openAi(request(false), response).block(Duration.ofSeconds(5));
        assertThat(response.getStatusCode() == null ? 200 : response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBodyAsString().block()).contains("resp_fixture");
        assertThat(attempted).containsExactly("Bearer fixture-14", "Bearer fixture-13");
    }

    @Test
    void smartModeKeepsTheAccountThatSucceededOnThePreviousTurn() {
        var attempted = new ArrayList<String>();
        var controller = twoAccountController(attempted, ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build(), AccountRoutingMode.SMART);
        invoke(controller, false);
        invoke(controller, false);
        assertThat(attempted).containsExactly("Bearer fixture-14", "Bearer fixture-13", "Bearer fixture-13");
    }

    @Test
    void rotateModeStartsEachRequestOnTheNextAccount() {
        var attempted = new ArrayList<String>();
        var controller = twoAccountController(attempted, completedResponse(), AccountRoutingMode.ROTATE);
        invoke(controller, false);
        invoke(controller, false);
        invoke(controller, false);
        assertThat(attempted).containsExactly("Bearer fixture-14", "Bearer fixture-13", "Bearer fixture-14");
    }

    @Test
    void switchesWhenFirstAccountOnlySendsMetadataThenStalls() {
        var attempted = new ArrayList<String>();
        var metadata = DefaultDataBufferFactory.sharedInstance.wrap("""
                data: {"type":"response.created","response":{"id":"resp_stalled","object":"response","status":"in_progress"}}

                """.getBytes(StandardCharsets.UTF_8));
        var stalled = ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                .body(Flux.concat(Flux.just(metadata), Flux.never())).build();
        var controller = twoAccountController(attempted, stalled, () -> ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream").body("""
                        data: {"type":"response.output_text.delta","delta":"from backup"}

                        data: {"type":"response.completed","response":{"id":"resp_backup","object":"response","status":"completed"}}

                        """).build(), AccountRoutingMode.ORDER, mock(UsagePublisher.class));
        var response = new MockServerHttpResponse();
        controller.openAi(request(true), response).block(Duration.ofSeconds(5));
        assertThat(response.getBodyAsString().block()).contains("from backup").doesNotContain("resp_stalled");
        assertThat(attempted).containsExactly("Bearer fixture-14", "Bearer fixture-13");
    }

    @Test
    void returnsCodexManifestOnlyForDiscoveryRequests() {
        var controller = twoAccountController(new ArrayList<>(), completedResponse());
        var standard = new MockServerHttpResponse();
        var codex = new MockServerHttpResponse();
        controller.models(MockServerHttpRequest.get("/v1/models").build(), standard).block();
        controller.models(MockServerHttpRequest.get("/v1/models?client_version=0.157.0").build(), codex).block();
        assertThat(standard.getBodyAsString().block()).contains("\"data\"", "\"id\":\"alias\"");
        assertThat(codex.getBodyAsString().block()).contains("\"models\"", "\"slug\":\"alias\"");
    }

    @Test
    void routesResponsesThroughManagedClaudeAccountAndLogsAnthropicEndpoint() {
        var mapper = new ObjectMapper();
        var key = new ApiKeyView(12, "hash", 1, 5, Set.of(), null, null, null, AccountPool.ANTHROPIC, IpRules.NONE);
        var tables = new RouteTableHolder();
        var channel = new Channel(13, "Claude fixture", Protocol.ANTHROPIC, "https://example.invalid", "", 100, 1, 1,
                Duration.ofSeconds(2), false, true);
        var route = new ModelRoute(13, "alias", channel, "claude-upstream", 1, 1, 13L, AccountPool.ANTHROPIC);
        tables.swap(new RouteTable(1, List.of(route), List.of(key), Map.of(), Set.of(),
                Map.of(5L, Set.of(13L)), Map.of(5L, List.of(13L)), Map.of(5L, AccountRoutingMode.ORDER)));
        var accounts = mock(AccountPool.class);
        when(accounts.acquireForAccount(AccountPool.ANTHROPIC, 13L, "claude-upstream", Set.of())).thenReturn(
                new AccountPool.Lease(13, AccountPool.ANTHROPIC, "api", mapper.createObjectNode()
                        .put("api_key", "fixture-key"), new AtomicInteger(1)));
        var usage = mock(UsagePublisher.class);
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/v1/messages");
            assertThat(request.headers().getFirst("x-api-key")).isEqualTo("fixture-key");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream").body("""
                    data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":4}}}

                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude reply"}}

                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                    data: {"type":"message_stop"}

                    """).build());
        }).build());
        var controller = controller(mapper, key, tables, accounts, relay, usage);
        var response = new MockServerHttpResponse();
        controller.openAi(request(true), response).block(Duration.ofSeconds(5));
        assertThat(response.getBodyAsString().block()).contains("Claude reply", "response.completed");
        var capture = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usage).publish(capture.capture(), anyBoolean());
        var event = capture.getValue();
        assertThat(event.accountId()).isEqualTo(13L);
        assertThat(event.groupId()).isEqualTo(5L);
        assertThat(event.inboundEndpoint()).isEqualTo("/v1/responses");
        assertThat(event.upstreamEndpoint()).isEqualTo("/v1/messages");
        assertThat(event.cacheCreationTokens()).isEqualTo(4);
        assertThat(event.ttftMs()).isPositive();
    }

    @Test
    void groupedResponsesDoNotSwitchAccountsAfterStreamingStarts() {
        var attempted = new ArrayList<String>();
        var firstFrame = DefaultDataBufferFactory.sharedInstance.wrap("""
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"partial"}

                """.getBytes(StandardCharsets.UTF_8));
        var brokenStream = ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                .body(Flux.concat(Flux.just(firstFrame), Flux.error(new IllegalStateException("fixture connection dropped"))))
                .build();
        var controller = twoAccountController(attempted, brokenStream);
        var response = new MockServerHttpResponse();
        assertThatThrownBy(() -> controller.openAi(request(true), response).block(Duration.ofSeconds(5)))
                .isInstanceOf(UpstreamRelay.UpstreamError.class);
        assertThat(response.isCommitted()).isTrue();
        assertThat(attempted).containsExactly("Bearer fixture-14");
    }

    private static ResponsesController twoAccountController(List<String> attempted, ClientResponse firstResponse) {
        return twoAccountController(attempted, firstResponse, AccountRoutingMode.ORDER);
    }

    private static ResponsesController twoAccountController(List<String> attempted, ClientResponse firstResponse, AccountRoutingMode mode) {
        return twoAccountController(attempted, firstResponse, AccountFailoverTest::completedResponse, mode, mock(UsagePublisher.class));
    }

    private static ResponsesController twoAccountController(List<String> attempted, ClientResponse firstResponse,
                                                            Supplier<ClientResponse> secondResponse, AccountRoutingMode mode, UsagePublisher usage) {
        var mapper = new ObjectMapper();
        var key = new ApiKeyView(12, "hash", 1, 5, Set.of(), null, null, null, AccountPool.CODEX, IpRules.NONE);
        var tables = new RouteTableHolder();
        tables.swap(new RouteTable(1, List.of(route(13), route(14)), List.of(key), Map.of(), Set.of(),
                Map.of(5L, Set.of(13L, 14L)), Map.of(5L, List.of(14L, 13L)), Map.of(5L, mode)));
        var accounts = mock(AccountPool.class);
        for (long id : List.of(13L, 14L)) {
            when(accounts.acquireForAccount(AccountPool.CODEX, id, "gpt-upstream", Set.of())).thenAnswer(invocation ->
                    new AccountPool.Lease(id, AccountPool.CODEX, "chatgpt", mapper.createObjectNode()
                            .put("access_token", "fixture-" + id), new AtomicInteger(1)));
        }
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(request -> {
            var header = request.headers().getFirst("Authorization");
            attempted.add(header);
            return Mono.just("Bearer fixture-14".equals(header) ? firstResponse : secondResponse.get());
        }).build());
        return controller(mapper, key, tables, accounts, relay, usage);
    }

    private static ResponsesController controller(ObjectMapper mapper, ApiKeyView key, RouteTableHolder tables,
                                                  AccountPool accounts, UpstreamRelay relay, UsagePublisher usage) {
        var auth = mock(ApiKeyAuthenticator.class);
        when(auth.authenticate(any())).thenReturn(key);
        var quota = mock(QuotaService.class);
        when(quota.reserve(eq(1L), eq(12L), anyString(), eq(0L))).thenReturn(Mono.just(QuotaService.ReserveResult.OK));
        when(quota.settle(eq(1L), anyString(), eq(0L), anyString())).thenReturn(Mono.just(1L));
        when(quota.release(eq(1L), anyString())).thenReturn(Mono.just(1L));
        var breaker = new CircuitBreaker();
        var resolver = new RouteResolver(new SmoothWeightedRoundRobin(), breaker, new ChannelGates(), mock(DynamicWeightManager.class));
        return new ResponsesController(auth, tables, resolver, breaker, relay, mapper,
                quota, mock(CostCalculator.class), mock(PricingService.class), usage, accounts, new AccountRouting(accounts, new SessionKeyExtractor()));
    }

    private static ClientResponse completedResponse() {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream").body("""
                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_fixture","object":"response","status":"completed"}}

                """).build();
    }

    private static MockServerHttpRequest request(boolean stream) {
        return MockServerHttpRequest.post("/v1/responses").header("Authorization", "Bearer key").header("session_id", "fixture-session")
                .body("{\"model\":\"alias\",\"input\":\"test\",\"stream\":" + stream + "}");
    }

    private static void invoke(ResponsesController controller, boolean stream) {
        var response = new MockServerHttpResponse();
        controller.openAi(request(stream), response).block(Duration.ofSeconds(5));
        assertThat((response.getStatusCode() == null ? 200 : response.getStatusCode().value())).isEqualTo(200);
        assertThat(response.getBodyAsString().block()).contains("resp_fixture");
    }

    private static ModelRoute route(long id) {
        var channel = new Channel(id, "channel-" + id, Protocol.CODEX_SUBSCRIPTION, "https://chatgpt.com", "", 100, 1, 1,
                Duration.ofSeconds(1), false, true);
        return new ModelRoute(id, "alias", channel, "gpt-upstream", 1, 1, id, AccountPool.CODEX);
    }
}
