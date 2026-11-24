package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.auth.ApiKeyAuthenticator;
import com.zhouyu.gateway.billing.CostCalculator;
import com.zhouyu.gateway.billing.PricingService;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.quota.QuotaService;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.SessionKeyExtractor;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.RouteResolver;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import com.zhouyu.gateway.usage.UsagePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CodexRequestTest {
    @Test
    void adaptsPlainResponsesInputWithoutModifyingClientRequest() throws Exception {
        var original = (ObjectNode) new ObjectMapper().readTree("""
                {"model":"alias","input":"hello","stream":false,"store":true,"max_output_tokens":200}
                """);
        var request = ResponsesController.codexRequest(original, "gpt-upstream");
        assertThat(request.path("input").at("/0/content/0/text").asText()).isEqualTo("hello");
        assertThat(request.path("model").asText()).isEqualTo("gpt-upstream");
        assertThat(request.path("instructions").asText()).isNotBlank();
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.has("max_output_tokens")).isFalse();
        assertThat(original.path("input").asText()).isEqualTo("hello");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routesCodexResponsesToTheFixedUpstreamAndRecordsUsage(boolean stream) {
        var mapper = new ObjectMapper();
        var key = new ApiKeyView(12, "gateway-key-hash", 1, 1, Set.of(), null, null);
        var auth = mock(ApiKeyAuthenticator.class);
        when(auth.authenticate(any())).thenReturn(key);
        var channel = new Channel(10, "test", Protocol.CODEX_SUBSCRIPTION, "https://untrusted.example",
                "", 1, 1, 10, Duration.ofSeconds(1), false, true);
        var route = new ModelRoute(22, "alias", channel, "gpt-upstream", 1, 1);
        var table = new RouteTable(1, List.of(route), List.of(key));
        var tables = new RouteTableHolder();
        tables.swap(table);
        var resolver = mock(RouteResolver.class);
        var candidates = table.candidates("alias");
        when(resolver.resolve("alias", candidates, Protocol.OPENAI_RESPONSES))
                .thenReturn(new RouteResolver.Decision(candidates.get(0), candidates, Map.of(), 1, false));
        var quota = mock(QuotaService.class);
        when(quota.reserve(eq(1L), eq(12L), anyString(), eq(0L))).thenReturn(Mono.just(QuotaService.ReserveResult.OK));
        when(quota.settle(eq(1L), anyString(), eq(0L), anyString())).thenReturn(Mono.just(1L));
        var accounts = mock(AccountPool.class);
        var lease = new AccountPool.Lease(7, AccountPool.CODEX, "chatgpt", mapper.createObjectNode()
                .put("access_token", "fixture-token").put("chatgpt_account_id", "fixture-account"), new AtomicInteger(1));
        when(accounts.acquire(AccountPool.CODEX, 12L, "gpt-upstream", Set.of(), null)).thenReturn(lease);
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("https://chatgpt.com/backend-api/codex/responses");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer fixture-token");
            assertThat(request.headers().getFirst("ChatGPT-Account-Id")).isEqualTo("fixture-account");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream").body("""
                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_fixture","object":"response","status":"completed","usage":{"input_tokens":6,"output_tokens":3}}}

                    """).build());
        }).build());
        var controller = new ResponsesController(auth, tables, resolver, mock(CircuitBreaker.class), relay, mapper,
                quota, mock(CostCalculator.class), mock(PricingService.class), mock(UsagePublisher.class), accounts, new AccountRouting(accounts, new SessionKeyExtractor()));
        var request = MockServerHttpRequest.post("/v1/responses").header("Authorization", "Bearer client-key")
                .body("{\"model\":\"alias\",\"input\":\"test\",\"stream\":" + stream + "}");
        var response = new MockServerHttpResponse();
        controller.openAi(request, response).block(Duration.ofSeconds(5));
        assertThat(response.getHeaders().getContentType().toString()).startsWith(stream ? "text/event-stream" : "application/json");
        assertThat(response.getBodyAsString().block()).contains("resp_fixture");
        verify(accounts).result(lease, "gpt-upstream", 200, 6, 3, false, 0);
    }
}
