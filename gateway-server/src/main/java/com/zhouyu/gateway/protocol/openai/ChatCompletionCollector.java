package com.zhouyu.gateway.protocol.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.relay.UpstreamRelay.UpstreamError;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/** WorkBuddy only sends SSE. Assemble a Chat Completions response for non-streaming clients. */
public final class ChatCompletionCollector {
    private final ObjectMapper mapper;
    private final ObjectNode response;
    private final TreeMap<Integer, Choice> choices = new TreeMap<>();
    private long totalChars;

    public ChatCompletionCollector(ObjectMapper mapper, String model) {
        this.mapper = mapper;
        this.response = mapper.createObjectNode().put("object", "chat.completion").put("model", model);
    }

    public void accept(String data) {
        if (data.isBlank() || "[DONE]".equals(data)) return;
        totalChars += data.length();
        if (totalChars > 8 * 1024 * 1024L) throw new UpstreamError(502, "Upstream completion exceeds size limit");
        JsonNode chunk;
        try {
            chunk = mapper.readTree(data);
        } catch (JsonProcessingException e) {
            throw new UpstreamError(502, "Invalid upstream completion frame");
        }
        if (chunk.hasNonNull("error")) throw new UpstreamError(502, "Upstream completion failed");
        for (String field : new String[]{"id", "created", "system_fingerprint", "service_tier", "usage"}) {
            if (chunk.hasNonNull(field)) response.set(field, chunk.get(field));
        }
        for (JsonNode item : chunk.path("choices")) {
            choices.computeIfAbsent(item.path("index").asInt(), index -> new Choice(mapper, index)).accept(item);
        }
    }

    public byte[] finish() {
        if (choices.isEmpty() || choices.values().stream().anyMatch(choice -> !choice.value.hasNonNull("finish_reason"))) {
            throw new UpstreamError(502, "Incomplete upstream completion");
        }
        var output = response.putArray("choices");
        choices.values().forEach(choice -> output.add(choice.finish()));
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class Choice {
        private final ObjectMapper mapper;
        private final ObjectNode value;
        private final ObjectNode message;
        private final TreeMap<Integer, ObjectNode> tools = new TreeMap<>();
        private final java.util.Map<String, StringBuilder> text = new java.util.LinkedHashMap<>();
        private final java.util.Map<Integer, StringBuilder> arguments = new java.util.HashMap<>();

        Choice(ObjectMapper mapper, int index) {
            this.mapper = mapper;
            value = mapper.createObjectNode().put("index", index).putNull("logprobs");
            message = value.putObject("message").put("role", "assistant").putNull("content");
        }

        void accept(JsonNode item) {
            var delta = item.path("delta");
            for (String field : new String[]{"content", "reasoning_content", "refusal"}) {
                if (delta.path(field).isTextual()) text.computeIfAbsent(field, ignored -> new StringBuilder()).append(delta.get(field).asText());
            }
            for (JsonNode call : delta.path("tool_calls")) {
                int index = call.path("index").asInt();
                ObjectNode tool = tools.computeIfAbsent(index, ignored -> mapper.createObjectNode().put("type", "function"));
                if (call.hasNonNull("id")) tool.set("id", call.get("id"));
                if (call.path("function").isObject()) {
                    var function = tool.withObjectProperty("function");
                    var piece = call.get("function");
                    if (piece.hasNonNull("name")) function.set("name", piece.get("name"));
                    if (piece.path("arguments").isTextual()) {
                        arguments.computeIfAbsent(index, ignored -> new StringBuilder()).append(piece.get("arguments").asText());
                    }
                }
            }
            if (item.hasNonNull("finish_reason")) value.set("finish_reason", item.get("finish_reason"));
        }

        ObjectNode finish() {
            text.forEach((field, content) -> message.put(field, content.toString()));
            if (!tools.isEmpty()) {
                var calls = message.putArray("tool_calls");
                tools.forEach((index, tool) -> {
                    tool.withObjectProperty("function").put("arguments", arguments.getOrDefault(index, new StringBuilder()).toString());
                    calls.add(tool);
                });
            }
            return value;
        }
    }
}
