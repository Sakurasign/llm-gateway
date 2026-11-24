package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.SessionKeyExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AccountPool accounts = mock(AccountPool.class);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testsTheSelectedAccountAndWaitsForText(boolean workBuddy) {
        String model = workBuddy ? "cn:fixture" : "fixture";
        when(accounts.acquireForTest(7, model)).thenReturn(lease(workBuddy));
        var controller = controller(request -> {
            assertThat(request.url().getPath()).isEqualTo(workBuddy ? "/v2/chat/completions" : "/backend-api/codex/responses");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer fixture-token");
            String events = workBuddy ? """
                    data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """ : """
                    data: {"type":"response.completed","response":{"status":"completed","output":[{"content":[{"type":"output_text","text":"OK"}]}]}}

                    """;
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream").body(events).build());
        });
        var result = controller.testModel(7, new ResponsesController.ModelTestInput(model)).block(Duration.ofSeconds(5));
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("OK");
        verify(accounts).acquireForTest(7, model);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void testsClaudeWithItsApiKeyAndReturnsTheTextContent() {
        var credential = mapper.createObjectNode().put("api_key", "fixture-claude-key");
        when(accounts.acquireForTest(8, "claude-fixture")).thenReturn(new AccountPool.Lease(8, AccountPool.ANTHROPIC,
                "api", credential, new AtomicInteger(1)));
        var controller = controller(request -> {
            assertThat(request.url().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
            assertThat(request.headers().getFirst("x-api-key")).isEqualTo("fixture-claude-key");
            assertThat(request.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}").build());
        });
        var result = controller.testModel(8, new ResponsesController.ModelTestInput("claude-fixture")).block(Duration.ofSeconds(5));
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("OK");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testsOpenAiApiKeyAndClaudeOAuthOnTheirNativeEndpoints(boolean openAi) {
        String provider = openAi ? AccountPool.CODEX : AccountPool.ANTHROPIC;
        when(accounts.acquireForTest(9, "fixture")).thenReturn(new AccountPool.Lease(9, provider, openAi ? "api" : "oauth",
                mapper.createObjectNode().put(openAi ? "api_key" : "access_token", "fixture-token"), new AtomicInteger(1)));
        var controller = controller(request -> {
            assertThat(request.url().toString()).isEqualTo(openAi
                    ? "https://api.openai.com/v1/responses" : "https://api.anthropic.com/v1/messages");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer fixture-token");
            assertThat(request.headers().containsKey("x-api-key")).isFalse();
            if (!openAi) assertThat(request.headers().getFirst("anthropic-beta")).isEqualTo("oauth-2025-04-20");
            String body = openAi ? """
                    {"output":[{"content":[{"type":"output_text","text":"OK"}]}]}
                    """ : """
                    {"content":[{"type":"text","text":"OK"}]}
                    """;
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
        });
        var result = controller.testModel(9, new ResponsesController.ModelTestInput("fixture")).block(Duration.ofSeconds(5));
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("OK");
    }

    @Test
    void reportsAnUpstreamAuthorizationFailureWithoutReturningTheBody() {
        when(accounts.acquireForTest(7, "fixture")).thenReturn(lease(false));
        var controller = controller(request -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).body("fixture-sensitive-details").build()));
        var result = controller.testModel(7, new ResponsesController.ModelTestInput("fixture")).block(Duration.ofSeconds(5));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("HTTP 401").doesNotContain("fixture-sensitive-details");
    }

    private AccountPool.Lease lease(boolean workBuddy) {
        return new AccountPool.Lease(7, workBuddy ? AccountPool.WORKBUDDY : AccountPool.CODEX,
                workBuddy ? "cn" : "chatgpt", mapper.createObjectNode().put("access_token", "fixture-token"), new AtomicInteger(1));
    }

    private ResponsesController controller(ExchangeFunction exchange) {
        var relay = new UpstreamRelay();
        ReflectionTestUtils.setField(relay, "webClient", WebClient.builder().exchangeFunction(exchange).build());
        return new ResponsesController(null, null, null, null, relay, mapper, null, null, null, null, accounts, new AccountRouting(accounts, new SessionKeyExtractor()));
    }
}
