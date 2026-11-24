package com.zhouyu.gateway.protocol.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.relay.TailUsageSniffer;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.relay.UpstreamRelay.UpstreamError;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionCollectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void combinesToolArgumentsAndUsageFromSeparateFrames() throws Exception {
        var collector = new ChatCompletionCollector(mapper, "work-qa");
        collector.accept("""
                {"id":"chat_test","created":100,"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{"}}]}}]}
                """);
        collector.accept("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}
                """);
        collector.accept("""
                {"choices":[],"usage":{"prompt_tokens":8,"completion_tokens":3,"total_tokens":11}}
                """);
        collector.accept("[DONE]");
        var response = mapper.readTree(collector.finish());
        assertThat(response.path("model").asText()).isEqualTo("work-qa");
        assertThat(response.at("/usage/total_tokens").asInt()).isEqualTo(11);
        assertThat(response.at("/choices/0/finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(response.at("/choices/0/message/tool_calls/0/function/arguments").asText()).isEqualTo("{}");
    }

    @Test
    void readsUpstreamSseAsJsonAndMetersUsage() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v2/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("""
                        data: {"id":"chat_qa","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}

                        data: [DONE]

                        """.getBytes(StandardCharsets.UTF_8));
            }
        });
        upstream.start();
        try {
            var channel = new Channel(1, "work-qa", Protocol.WORKBUDDY,
                    "http://127.0.0.1:" + upstream.getAddress().getPort(), "synthetic", 1, 1, 1, Duration.ofSeconds(3), true, true);
            var inbound = MockServerHttpRequest.post("/v1/chat/completions").build();
            var out = new MockServerHttpResponse();
            var collector = new ChatCompletionCollector(mapper, "work-qa");
            var meter = new TailUsageSniffer(mapper);
            new UpstreamRelay().relaySseToJson(inbound, out, channel, "/v2/chat/completions",
                    "{}".getBytes(StandardCharsets.UTF_8), new UpstreamRelay.FirstByteFlag(), meter,
                    Map.of(), collector::accept, collector::finish).block(Duration.ofSeconds(5));
            var response = mapper.readTree(out.getBodyAsString().block(Duration.ofSeconds(2)));
            assertThat(response.at("/choices/0/message/content").asText()).isEqualTo("hello");
            assertThat(meter.inputTokens()).isEqualTo(7);
            assertThat(meter.outputTokens()).isEqualTo(2);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void doesNotReportSuccessWhenUpstreamEndsBeforeFinish() {
        var collector = new ChatCompletionCollector(mapper, "work-qa");
        collector.accept("""
                {"choices":[{"index":0,"delta":{"content":"partial"}}]}
                """);
        assertThatThrownBy(collector::finish).isInstanceOf(UpstreamError.class);
    }
}
