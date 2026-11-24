package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.relay.TailUsageSniffer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponsesChatAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesChatAdapter adapter = new ResponsesChatAdapter(mapper);

    @Test
    void preservesToolCallIdsAcrossRounds() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"input":[{"role":"user","content":"look up"},
                {"type":"function_call","name":"lookup","call_id":"call_lookup","arguments":"{}"},
                {"type":"function_call_output","call_id":"call_lookup","output":"found"}],
                "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}],"max_output_tokens":12}
                """);
        JsonNode out = mapper.readTree(adapter.request(request, "chat-upstream", true));
        assertThat(out.at("/messages/1/tool_calls/0/id").asText()).isEqualTo("call_lookup");
        assertThat(out.at("/messages/2/tool_call_id").asText()).isEqualTo("call_lookup");
        assertThat(out.at("/tools/0/function/name").asText()).isEqualTo("lookup");
        assertThat(out.path("max_tokens").asInt()).isEqualTo(12);
        assertThat(out.at("/stream_options/include_usage").asBoolean()).isTrue();
    }

    @Test
    void reportsUsageAndReassemblesUtf8ToolArguments() {
        var meter = new TailUsageSniffer(mapper);
        byte[] stream = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"v\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"中文\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        Flux<byte[]> split = Flux.fromStream(IntStream.range(0, stream.length).mapToObj(i -> new byte[]{stream[i]}));
        var emitted = adapter.stream(split, "model", meter).collectList().block();
        assertThat(emitted).anyMatch(event -> event.startsWith("event: response.function_call_arguments.done\n")
                && event.contains("中文") && event.contains("lookup"));
        assertThat(emitted.get(emitted.size() - 1)).startsWith("event: response.completed\n");
        assertThat(meter.inputTokens()).isEqualTo(11);
        assertThat(meter.outputTokens()).isEqualTo(7);
    }

    @Test
    void doesNotReportAnInterruptedStreamAsCompleted() {
        var meter = new TailUsageSniffer(mapper);
        var raw = Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
        var emitted = adapter.stream(raw, "model", meter).collectList().block();
        assertThat(emitted.get(emitted.size() - 1)).startsWith("event: response.failed\n");
        assertThat(meter.responseStatus()).isEqualTo("failed");
    }

    @Test
    void convertsClaudeToolsAndPreservesCacheBreakdown() throws Exception {
        var input = (ObjectNode) mapper.readTree("""
                {"instructions":"Be concise","input":[{"role":"user","content":"check"},
                {"type":"function_call","call_id":"call_a","name":"lookup","arguments":"{}"},
                {"type":"function_call_output","call_id":"call_a","output":"found"}],
                "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}
                """);
        var request = mapper.readTree(adapter.anthropicRequest(input, "claude-fixture"));
        assertThat(request.path("system").asText()).isEqualTo("Be concise");
        assertThat(request.path("messages").path(1).path("content").path(0).path("id").asText()).isEqualTo("call_a");
        assertThat(request.path("messages").path(2).path("content").path(0).path("tool_use_id").asText()).isEqualTo("call_a");
        var meter = new TailUsageSniffer(mapper);
        var frames = Flux.just("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":70,"cache_creation_input_tokens":10}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_claude","name":"lookup","input":{}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}

                data: {"type":"message_stop"}

                """.getBytes(StandardCharsets.UTF_8));
        var events = adapter.anthropicStream(frames, "claude-alias", meter).collectList().block();
        assertThat(events).anyMatch(event -> event.startsWith("event: response.function_call_arguments.done") && event.contains("lookup"));
        assertThat(events.get(events.size() - 1)).contains("response.completed", "call_claude", "\"input_tokens\":100");
        assertThat(meter.uncachedInputTokens()).isEqualTo(20);
        assertThat(meter.cachedTokens()).isEqualTo(70);
        assertThat(meter.cacheCreationTokens()).isEqualTo(10);
    }

    @Test
    void rejectsUpstreamStateAndUntranslatedToolsOnChatRoutes() {
        ObjectNode state = mapper.createObjectNode().put("input", "hello").put("previous_response_id", "resp_foreign");
        assertThatThrownBy(() -> adapter.request(state, "model", false)).isInstanceOf(GatewayException.class);
        ObjectNode tools = mapper.createObjectNode().put("input", "hello");
        tools.putArray("tools").addObject().put("type", "web_search");
        assertThatThrownBy(() -> adapter.request(tools, "model", false)).isInstanceOf(GatewayException.class);
    }
}
