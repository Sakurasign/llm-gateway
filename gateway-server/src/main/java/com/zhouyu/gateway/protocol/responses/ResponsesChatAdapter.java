package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.protocol.sse.SseFrameParser;
import com.zhouyu.gateway.relay.TailUsageSniffer;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ResponsesChatAdapter {
    private final ObjectMapper mapper;

    ResponsesChatAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    byte[] request(ObjectNode body, String model, boolean stream) {
        for (String field : List.of("previous_response_id", "conversation", "background")) {
            if (body.hasNonNull(field) && !body.path(field).isBoolean()) {
                throw GatewayException.invalidRequest("Chat 渠道需要在 input 中发送完整上下文，不支持 " + field);
            }
        }
        if (body.path("background").asBoolean()) throw GatewayException.invalidRequest("Chat 渠道不支持后台运行");
        ObjectNode chat = mapper.createObjectNode().put("model", model).put("stream", stream);
        ArrayNode messages = chat.putArray("messages");
        if (body.path("instructions").isTextual()) {
            messages.addObject().put("role", "system").put("content", body.path("instructions").asText());
        }
        JsonNode input = body.path("input");
        if (input.isTextual()) {
            messages.addObject().put("role", "user").put("content", input.asText());
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                switch (item.path("type").asText("message")) {
                    case "message" -> {
                        ObjectNode message = messages.addObject().put("role", item.path("role").asText("user"));
                        message.set("content", content(item.path("content")));
                    }
                    case "function_call" -> {
                        JsonNode last = messages.get(messages.size() - 1);
                        ArrayNode calls = last != null && last.has("tool_calls") ? (ArrayNode) last.get("tool_calls")
                                : messages.addObject().put("role", "assistant").putArray("tool_calls");
                        calls.addObject().put("id", item.path("call_id").asText()).put("type", "function").putObject("function")
                                .put("name", item.path("name").asText()).put("arguments", item.path("arguments").asText());
                    }
                    case "function_call_output" -> messages.addObject().put("role", "tool")
                            .put("tool_call_id", item.path("call_id").asText()).set("content", content(item.path("output")));
                    case "reasoning" -> { }
                    default -> throw GatewayException.invalidRequest("Chat 渠道不支持输入项 " + item.path("type").asText());
                }
            }
        } else {
            throw GatewayException.invalidRequest("input 必须是字符串或数组");
        }
        if (messages.isEmpty()) throw GatewayException.invalidRequest("input 不能为空");
        for (String field : List.of("temperature", "top_p", "parallel_tool_calls")) {
            if (body.hasNonNull(field)) chat.set(field, body.get(field));
        }
        if (body.hasNonNull("max_output_tokens")) chat.set("max_tokens", body.get("max_output_tokens"));
        if (body.path("reasoning").hasNonNull("effort")) chat.set("reasoning_effort", body.path("reasoning").get("effort"));
        if (stream) chat.putObject("stream_options").put("include_usage", true);
        if (body.path("tools").isArray()) {
            ArrayNode tools = chat.putArray("tools");
            for (JsonNode tool : body.path("tools")) {
                if (!tool.path("type").asText().equals("function")) {
                    throw GatewayException.invalidRequest("Chat 渠道只支持 function 工具");
                }
                ObjectNode function = ((ObjectNode) tool).deepCopy();
                function.remove("type");
                tools.addObject().put("type", "function").set("function", function);
            }
        }
        JsonNode choice = body.path("tool_choice");
        if (choice.isTextual()) chat.set("tool_choice", choice);
        else if (choice.isObject() && choice.path("type").asText().equals("function")) {
            chat.putObject("tool_choice").put("type", "function").putObject("function").set("name", choice.path("name"));
        } else if (!choice.isMissingNode() && !choice.isNull()) throw GatewayException.invalidRequest("Chat 渠道不支持此 tool_choice");
        JsonNode format = body.path("text").path("format");
        if (format.isObject()) {
            String type = format.path("type").asText();
            ObjectNode target = chat.putObject("response_format").put("type", type);
            if (type.equals("json_schema")) {
                ObjectNode schema = ((ObjectNode) format).deepCopy();
                schema.remove("type");
                target.set("json_schema", schema);
            }
        }
        return chat.toString().getBytes(StandardCharsets.UTF_8);
    }

    byte[] anthropicRequest(ObjectNode body, String upstreamModel) {
        JsonNode chat = parse(request(body, upstreamModel, true));
        if (chat.has("response_format")) throw GatewayException.invalidRequest("Claude 渠道暂不支持 Responses 结构化输出");
        ObjectNode request = mapper.createObjectNode().put("model", upstreamModel).put("stream", true)
                .put("max_tokens", chat.path("max_tokens").asInt(4096));
        var messages = request.putArray("messages");
        var system = new StringBuilder();
        for (JsonNode message : chat.path("messages")) {
            String role = message.path("role").asText();
            if (role.equals("system") || role.equals("developer")) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(message.path("content").asText());
                continue;
            }
            String targetRole = role.equals("assistant") ? "assistant" : "user";
            JsonNode previous = messages.get(messages.size() - 1);
            ArrayNode blocks = previous != null && previous.path("role").asText().equals(targetRole)
                    ? (ArrayNode) previous.path("content") : messages.addObject().put("role", targetRole).putArray("content");
            JsonNode content = message.path("content");
            if (role.equals("tool")) {
                var result = blocks.addObject().put("type", "tool_result").put("tool_use_id", message.path("tool_call_id").asText());
                if (content.isTextual()) result.set("content", content);
                else appendAnthropicContent(result.putArray("content"), content);
            } else {
                appendAnthropicContent(blocks, content);
                for (JsonNode call : message.path("tool_calls")) {
                    var function = call.path("function");
                    JsonNode input;
                    try { input = mapper.readTree(function.path("arguments").asText("{}")); }
                    catch (java.io.IOException e) { throw GatewayException.invalidRequest("工具参数必须是 JSON 对象"); }
                    if (input == null || !input.isObject()) throw GatewayException.invalidRequest("工具参数必须是 JSON 对象");
                    blocks.addObject().put("type", "tool_use").put("id", call.path("id").asText())
                            .put("name", function.path("name").asText()).set("input", input);
                }
            }
        }
        if (messages.isEmpty()) throw GatewayException.invalidRequest("Claude 渠道需要至少一条消息");
        if (!system.isEmpty()) request.put("system", system.toString());
        for (String field : List.of("temperature", "top_p")) if (chat.has(field)) request.set(field, chat.get(field));
        if (!chat.path("tools").isEmpty()) {
            var tools = request.putArray("tools");
            for (JsonNode tool : chat.path("tools")) {
                var function = tool.path("function");
                var target = tools.addObject().put("name", function.path("name").asText());
                if (function.hasNonNull("description")) target.set("description", function.get("description"));
                target.set("input_schema", function.hasNonNull("parameters") ? function.get("parameters") : mapper.createObjectNode().put("type", "object"));
            }
            JsonNode choice = chat.path("tool_choice");
            if (!choice.isMissingNode() || body.path("parallel_tool_calls").isBoolean()) {
                var target = request.putObject("tool_choice");
                target.put("type", choice.isObject() ? "tool" : switch (choice.asText()) {
                    case "required" -> "any";
                    case "none" -> "none";
                    default -> "auto";
                });
                if (choice.isObject()) target.set("name", choice.path("function").path("name"));
                if (!target.path("type").asText().equals("none") && !body.path("parallel_tool_calls").asBoolean(true)) target.put("disable_parallel_tool_use", true);
            }
        }
        return request.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendAnthropicContent(ArrayNode blocks, JsonNode content) {
        if (content.isTextual() && !content.asText().isEmpty()) blocks.addObject().put("type", "text").set("text", content);
        for (JsonNode part : content) {
            if (part.path("type").asText().equals("text")) {
                blocks.addObject().put("type", "text").set("text", part.path("text"));
            } else if (part.path("type").asText().equals("image_url")) {
                String url = part.path("image_url").path("url").asText();
                var source = blocks.addObject().put("type", "image").putObject("source");
                int split = url.indexOf(";base64,");
                if (url.startsWith("data:image/") && split > 0) {
                    source.put("type", "base64").put("media_type", url.substring(5, split)).put("data", url.substring(split + 8));
                } else if (url.startsWith("https://") || url.startsWith("http://")) {
                    source.put("type", "url").put("url", url);
                } else throw GatewayException.invalidRequest("Claude 渠道图片地址无效");
            }
        }
    }

    Flux<String> anthropicStream(Flux<byte[]> raw, String model, TailUsageSniffer meter) {
        return stream(raw, model, meter, true);
    }

    private JsonNode content(JsonNode content) {
        if (content.isTextual()) return content;
        if (!content.isArray()) throw GatewayException.invalidRequest("消息内容无效");
        ArrayNode parts = mapper.createArrayNode();
        for (JsonNode item : content) {
            switch (item.path("type").asText()) {
                case "input_text", "output_text" -> parts.addObject().put("type", "text").set("text", item.path("text"));
                case "input_image" -> {
                    if (!item.path("image_url").isTextual()) throw GatewayException.invalidRequest("Chat 渠道需要 image_url");
                    ObjectNode image = parts.addObject().put("type", "image_url").putObject("image_url");
                    image.set("url", item.path("image_url"));
                    if (item.has("detail")) image.set("detail", item.get("detail"));
                }
                default -> throw GatewayException.invalidRequest("Chat 渠道不支持内容类型 " + item.path("type").asText());
            }
        }
        return parts;
    }

    byte[] single(byte[] bytes, String model) {
        State state = new State(model);
        JsonNode chat = parse(bytes);
        JsonNode choice = chat.path("choices").path(0);
        if (choice.isMissingNode()) throw new IllegalArgumentException("Missing Chat choice");
        state.update(choice.path("message"), new ArrayList<>());
        state.finish = choice.path("finish_reason").asText();
        state.tokenUsage(chat.path("usage"));
        return state.response(state.result()).toString().getBytes(StandardCharsets.UTF_8);
    }

    Flux<String> stream(Flux<byte[]> raw, String model, TailUsageSniffer meter) {
        return stream(raw, model, meter, false);
    }

    private Flux<String> stream(Flux<byte[]> raw, String model, TailUsageSniffer meter, boolean anthropic) {
        return Flux.defer(() -> {
            State state = new State(model);
            SseFrameParser parser = new SseFrameParser();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            Flux<String> events = raw.concatMapIterable(bytes -> {
                List<String> emitted = new ArrayList<>();
                for (byte value : bytes) {
                    line.write(value);
                    if (line.size() > 1_048_576) throw new IllegalArgumentException("Chat SSE frame exceeds limit");
                    if (value != '\n') continue;
                    for (var frame : parser.feed(line.toString(StandardCharsets.UTF_8))) {
                        state.receive(frame.data(), emitted, meter, anthropic);
                    }
                    line.reset();
                }
                return emitted;
            });
            return events.concatWith(Flux.defer(() -> {
                if (state.done) return Flux.empty();
                List<String> terminal = new ArrayList<>();
                if (line.size() != 0) parser.feed(line.toString(StandardCharsets.UTF_8));
                for (var frame : parser.flush()) state.receive(frame.data(), terminal, meter, anthropic);
                if (!state.done) state.terminate(terminal, state.finish.isEmpty() ? "failed" : state.result(), meter);
                return Flux.fromIterable(terminal);
            })).onErrorResume(error -> {
                if (!state.begun || state.done) return Flux.error(error);
                List<String> terminal = new ArrayList<>();
                state.terminate(terminal, "failed", meter);
                return Flux.fromIterable(terminal);
            });
        });
    }

    private JsonNode parse(byte[] bytes) {
        try { return mapper.readTree(bytes); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid Chat JSON", e); }
    }

    private final class State {
        private final String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        private final String model;
        private final long created = Instant.now().getEpochSecond();
        private final List<ObjectNode> output = new ArrayList<>();
        private final Map<Integer, Integer> tools = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();
        private Integer textIndex;
        private ObjectNode usage;
        private int sequence;
        private boolean begun;
        private boolean done;
        private String finish = "";
        private boolean anthropicUsage;

        State(String model) { this.model = model; }

        void receive(String data, List<String> events, TailUsageSniffer meter, boolean anthropic) {
            if (data.isBlank() || done) return;
            if (data.equals("[DONE]")) {
                terminate(events, finish.isEmpty() ? "failed" : result(), meter);
                return;
            }
            JsonNode root = parse(data.getBytes(StandardCharsets.UTF_8));
            if (root.hasNonNull("error")) {
                String type = root.path("error").path("type").asText();
                throw new com.zhouyu.gateway.relay.UpstreamRelay.UpstreamError(type.contains("rate_limit") ? 429 : 502,
                        "上游流返回错误");
            }
            if (anthropic) {
                receiveAnthropic(root, events, meter);
                return;
            }
            begin(events);
            tokenUsage(root.path("usage"));
            JsonNode choice = root.path("choices").path(0);
            if (choice.hasNonNull("finish_reason")) finish = choice.get("finish_reason").asText();
            update(choice.path("delta"), events);
        }

        void receiveAnthropic(JsonNode event, List<String> events, TailUsageSniffer meter) {
            anthropicUsage = true;
            meter.parseUsage(event.path("message").path("usage"));
            meter.parseUsage(event.path("usage"));
            switch (event.path("type").asText()) {
                case "message_start" -> begin(events);
                case "content_block_start", "content_block_delta" -> {
                    begin(events);
                    JsonNode block = event.path("content_block");
                    JsonNode delta = event.path("delta");
                    var update = mapper.createObjectNode();
                    if (block.path("type").asText().equals("tool_use")) {
                        var call = update.putArray("tool_calls").addObject().put("index", event.path("index").asInt())
                                .put("id", block.path("id").asText());
                        var function = call.putObject("function").put("name", block.path("name").asText());
                        if (!block.path("input").isEmpty()) function.put("arguments", block.path("input").toString());
                    } else if (delta.path("type").asText().equals("input_json_delta")) {
                        update.putArray("tool_calls").addObject().put("index", event.path("index").asInt())
                                .putObject("function").put("arguments", delta.path("partial_json").asText());
                    } else if (delta.hasNonNull("text") || block.hasNonNull("text")) {
                        update.put("content", delta.path("text").asText(block.path("text").asText()));
                    }
                    update(update, events);
                }
                case "message_delta" -> finish = event.path("delta").path("stop_reason").asText().equals("max_tokens") ? "length" : "stop";
                case "message_stop" -> terminate(events, finish.isEmpty() ? "failed" : result(), meter);
                default -> { }
            }
        }

        void begin(List<String> events) {
            if (begun) return;
            begun = true;
            emit(events, "response.created", mapper.createObjectNode().set("response", response("in_progress")));
            emit(events, "response.in_progress", mapper.createObjectNode().set("response", response("in_progress")));
        }

        void update(JsonNode delta, List<String> events) {
            if (delta.path("content").isTextual() && !delta.path("content").asText().isEmpty()) {
                if (textIndex == null) {
                    ObjectNode message = mapper.createObjectNode().put("type", "message").put("id", "msg_" + id.substring(5))
                            .put("role", "assistant").put("status", "in_progress");
                    message.putArray("content").addObject().put("type", "output_text").put("text", "").putArray("annotations");
                    textIndex = add(message, events);
                    emit(events, "response.content_part.added", itemFields(textIndex).put("content_index", 0)
                            .set("part", message.path("content").path(0)));
                }
                String deltaText = delta.path("content").asText();
                text.append(deltaText);
                emit(events, "response.output_text.delta", itemFields(textIndex).put("content_index", 0).put("delta", deltaText));
            }
            int index = 0;
            for (JsonNode call : delta.path("tool_calls")) {
                int toolIndex = call.path("index").asInt(index++);
                JsonNode function = call.path("function");
                Integer out = tools.get(toolIndex);
                if (out == null) {
                    String callId = call.path("id").asText();
                    if (callId.isBlank()) callId = "call_" + id.substring(5) + "_" + toolIndex;
                    ObjectNode item = mapper.createObjectNode().put("type", "function_call").put("id", "fc_" + id.substring(5) + "_" + toolIndex)
                            .put("call_id", callId).put("name", function.path("name").asText()).put("arguments", "").put("status", "in_progress");
                    out = add(item, events);
                    tools.put(toolIndex, out);
                } else if (function.hasNonNull("name")) {
                    ObjectNode item = output.get(out);
                    item.put("name", item.path("name").asText() + function.path("name").asText());
                }
                String args = function.path("arguments").asText();
                if (!args.isEmpty()) {
                    ObjectNode item = output.get(out);
                    item.put("arguments", item.path("arguments").asText() + args);
                    emit(events, "response.function_call_arguments.delta", itemFields(out).put("delta", args));
                }
            }
        }

        private int add(ObjectNode item, List<String> events) {
            int position = output.size();
            output.add(item);
            emit(events, "response.output_item.added", mapper.createObjectNode().put("output_index", position).set("item", item));
            return position;
        }

        private ObjectNode itemFields(int index) {
            return mapper.createObjectNode().put("output_index", index).put("item_id", output.get(index).path("id").asText());
        }

        void tokenUsage(JsonNode value) {
            if (!value.isObject()) return;
            int input = value.path("prompt_tokens").asInt();
            int tokens = value.path("completion_tokens").asInt();
            usage = mapper.createObjectNode().put("input_tokens", input).put("output_tokens", tokens).put("total_tokens", input + tokens);
            usage.putObject("input_tokens_details").put("cached_tokens", value.path("prompt_tokens_details").path("cached_tokens").asInt());
            usage.putObject("output_tokens_details").put("reasoning_tokens", value.path("completion_tokens_details").path("reasoning_tokens").asInt());
        }

        String result() { return finish.equals("length") || finish.equals("content_filter") ? "incomplete" : "completed"; }

        ObjectNode response(String status) {
            ObjectNode response = mapper.createObjectNode().put("id", id).put("object", "response").put("created_at", created)
                    .put("model", model).put("status", status).putNull("error").putNull("incomplete_details");
            if (textIndex != null) ((ObjectNode) output.get(textIndex).path("content").path(0)).put("text", text.toString());
            ArrayNode items = response.putArray("output");
            for (ObjectNode item : output) items.add(item.deepCopy().put("status", status.equals("completed") ? "completed" : status));
            response.set("usage", usage);
            if (status.equals("incomplete")) response.putObject("incomplete_details").put("reason", finish.equals("length") ? "max_output_tokens" : "content_filter");
            if (status.equals("failed")) response.putObject("error").put("code", "upstream_error").put("message", "上游流未正常结束");
            return response;
        }

        void terminate(List<String> events, String status, TailUsageSniffer meter) {
            begin(events);
            done = true;
            if (!status.equals("failed")) {
                for (int index = 0; index < output.size(); index++) {
                    ObjectNode item = output.get(index);
                    item.put("status", status);
                    if (item.path("type").asText().equals("function_call")) {
                        emit(events, "response.function_call_arguments.done", itemFields(index)
                                .put("name", item.path("name").asText()).put("arguments", item.path("arguments").asText()));
                    } else {
                        JsonNode part = item.path("content").path(0);
                        ((ObjectNode) part).put("text", text.toString());
                        emit(events, "response.output_text.done", itemFields(index).put("content_index", 0).put("text", text.toString()));
                        emit(events, "response.content_part.done", itemFields(index).put("content_index", 0).set("part", part));
                    }
                    emit(events, "response.output_item.done", mapper.createObjectNode().put("output_index", index).set("item", item));
                }
            }
            if (anthropicUsage && "UPSTREAM".equals(meter.usageSource())) {
                int input = meter.inputTokens() + meter.cachedTokens() + meter.cacheCreationTokens();
                usage = mapper.createObjectNode().put("input_tokens", input).put("output_tokens", meter.outputTokens())
                        .put("total_tokens", input + meter.outputTokens());
                usage.putObject("input_tokens_details").put("cached_tokens", meter.cachedTokens());
            }
            ObjectNode response = response(status);
            if (anthropicUsage) meter.responseStatus(status);
            else meter.parseWhole(response.toString().getBytes(StandardCharsets.UTF_8));
            emit(events, "response." + status, mapper.createObjectNode().set("response", response));
        }

        private void emit(List<String> events, String type, ObjectNode payload) {
            payload.put("type", type).put("sequence_number", sequence++);
            events.add("event: " + type + "\ndata: " + payload + "\n\n");
        }
    }
}
