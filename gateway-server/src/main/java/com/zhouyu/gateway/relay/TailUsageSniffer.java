package com.zhouyu.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式用量嗅探器。
 *
 * <p><b>每请求一个实例</b>（非单例）：内部持有 request 级状态，共享会导致串数据。
 *
 * <p>透传模式下不解析每一帧，只在尾部保留窗口，流结束后提取 usage：
 * <ul>
 *   <li>Anthropic：{@code message_start} 给 input_tokens，{@code message_delta} 给 output_tokens</li>
 *   <li>OpenAI：最后一个含 {@code "usage":{...}} 的帧</li>
 * </ul>
 */
public final class TailUsageSniffer {

    private static final int TAIL_LIMIT = 16 * 1024;

    private static final Pattern INPUT_TOKENS = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern OUTPUT_TOKENS = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CACHE_READ = Pattern.compile("\"cache_read_input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern PROMPT_TOKENS = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    private final ObjectMapper mapper;
    private final StringBuilder tail = new StringBuilder();
    private int inputTokens;
    private int cacheCreationTokens;
    private int reasoningTokens;
    private boolean inputIncludesCache;
    private long firstOutputAt;
    private String upstreamEndpoint;

    public void upstreamEndpoint(String path) { upstreamEndpoint = path.split("\\?", 2)[0].replaceFirst("^https?://[^/]+", ""); }
    public String upstreamEndpoint() { return upstreamEndpoint; }
    public void markOutput() { if (firstOutputAt == 0) firstOutputAt = System.currentTimeMillis(); }
    public int ttftMs(long startedAt) { return firstOutputAt == 0 ? 0 : (int) Math.max(1, firstOutputAt - startedAt); }
    public int cacheCreationTokens() { return cacheCreationTokens; }
    public int reasoningTokens() { return reasoningTokens; }
    public int uncachedInputTokens() { return Math.max(0, inputTokens - (inputIncludesCache ? cachedTokens : 0)); }

    public void parseUsage(JsonNode usage) {
        if (!usage.isObject()) return;
        usageSource = "UPSTREAM";
        if (usage.has("input_tokens")) inputTokens = usage.path("input_tokens").asInt();
        if (usage.has("prompt_tokens")) inputTokens = usage.path("prompt_tokens").asInt();
        if (usage.has("output_tokens")) outputTokens = usage.path("output_tokens").asInt();
        if (usage.has("completion_tokens")) outputTokens = usage.path("completion_tokens").asInt();
        if (usage.has("input_tokens_details") || usage.has("prompt_tokens_details") || usage.has("prompt_tokens")) {
            inputIncludesCache = true;
            cachedTokens = usage.path("input_tokens_details").path("cached_tokens").asInt(
                    usage.path("prompt_tokens_details").path("cached_tokens").asInt());
        }
        if (usage.has("cache_read_input_tokens")) cachedTokens = usage.path("cache_read_input_tokens").asInt();
        if (usage.has("cache_creation_input_tokens")) cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt();
        reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens").asInt(
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(reasoningTokens));
    }
    private int outputTokens;
    private int cachedTokens;
    private String usageSource = "ESTIMATED";
    private long totalBytes;
    private String responseId;
    private String responseStatus;
    private final java.io.ByteArrayOutputStream responseLine = new java.io.ByteArrayOutputStream();
    private boolean skipResponseLine;

    public void acceptResponses(DataBuffer buffer) {
        totalBytes += buffer.readableByteCount();
        // Read only a copy of the view, leaving the buffer position intact for relay.
        try (var buffers = buffer.readableByteBuffers()) {
            while (buffers.hasNext()) {
                var view = buffers.next().asReadOnlyBuffer();
                while (view.hasRemaining()) {
                    byte b = view.get();
                    if (b == '\n') {
                        if (!skipResponseLine) parseResponseLine();
                        responseLine.reset();
                        skipResponseLine = false;
                    } else if (responseLine.size() == 2 * 1024 * 1024) {
                        skipResponseLine = true;
                    } else if (!skipResponseLine) {
                        responseLine.write(b);
                    }
                }
            }
        }
    }

    private void parseResponseLine() {
        String line = responseLine.toString(StandardCharsets.UTF_8);
        if (!line.startsWith("data:")) return;
        try {
            JsonNode event = mapper.readTree(line.substring(5));
            JsonNode response = event.path("response");
            if (response.isObject()) parseResponse(response);
            parseUsage(event.path("message").path("usage"));
            parseUsage(event.path("usage"));
            if (event.path("type").asText().equals("error")) responseStatus = "failed";
        } catch (Exception ignored) { }
    }

    private void parseResponse(JsonNode response) {
        if (!response.path("object").asText().equals("response")) return;
        if (response.hasNonNull("id")) responseId = response.get("id").asText();
        if (response.hasNonNull("status")) responseStatus = response.get("status").asText();
        JsonNode usage = response.path("usage");
        parseUsage(usage);
        if (usage.isObject()) inputIncludesCache = true;
    }

    public String responseId() { return responseId; }
    public String responseStatus() { return responseStatus; }
    public void responseStatus(String status) { responseStatus = status; }

    public TailUsageSniffer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 每帧调用。使用 {@code toString} 读取而不移动读指针，
     * 保证下游仍能正常消费同一份 DataBuffer。
     */
    public void accept(DataBuffer buf) {
        totalBytes += buf.readableByteCount();
        String s = buf.toString(StandardCharsets.UTF_8);
        synchronized (tail) {
            tail.append(s);
            if (tail.length() > TAIL_LIMIT) {
                tail.delete(0, tail.length() - TAIL_LIMIT);
            }
        }
        parseChunk(s);
    }

    public void finishChatStream() {
        // 最后的 usage 帧可能被拆到多个网络分片。
        synchronized (tail) {
            for (String line : tail.toString().split("\\R")) {
                if (line.startsWith("data:")) parseWhole(line.substring(5).trim().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void parseChunk(String chunk) {
        if (chunk.contains("\"usage\"")) {
            usageSource = "UPSTREAM";
        }
        Matcher mi = INPUT_TOKENS.matcher(chunk);
        if (mi.find()) {
            inputTokens = Math.max(inputTokens, Integer.parseInt(mi.group(1)));
        }
        Matcher mo = OUTPUT_TOKENS.matcher(chunk);
        if (mo.find()) {
            outputTokens = Math.max(outputTokens, Integer.parseInt(mo.group(1)));
        }
        Matcher mc = CACHE_READ.matcher(chunk);
        if (mc.find()) {
            cachedTokens = Math.max(cachedTokens, Integer.parseInt(mc.group(1)));
        }
        Matcher mp = PROMPT_TOKENS.matcher(chunk);
        if (mp.find()) {
            inputTokens = Math.max(inputTokens, Integer.parseInt(mp.group(1)));
        }
        Matcher mq = COMPLETION_TOKENS.matcher(chunk);
        if (mq.find()) {
            outputTokens = Math.max(outputTokens, Integer.parseInt(mq.group(1)));
        }
    }

    /**
     * 非流式响应：整包解析。
     *
     * <p>兼容三种格式（因为转换后的响应可能是任一协议）：
     * <ul>
     *   <li>OpenAI：{@code usage.prompt_tokens / completion_tokens}</li>
     *   <li>Anthropic：{@code usage.input_tokens / output_tokens}（转换后的结果走这里）</li>
     *   <li>Gemini：{@code usageMetadata.promptTokenCount / candidatesTokenCount}</li>
     * </ul>
     */
    public void parseWhole(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);

            if (root.path("object").asText().equals("response")) {
                parseResponse(root);
                return;
            }

            // Anthropic 格式（转换后的响应）
            parseUsage(root.path("usage"));

            JsonNode usageMeta = root.get("usageMetadata");
            if (usageMeta != null) {
                usageSource = "UPSTREAM";
                inputTokens = usageMeta.path("promptTokenCount").asInt(0);
                outputTokens = usageMeta.path("candidatesTokenCount").asInt(0);
                cachedTokens = usageMeta.path("cachedContentTokenCount").asInt(0);
                inputIncludesCache = true;
            }
        } catch (Exception ignored) {
            // 保持 ESTIMATED
        }
    }

    public int inputTokens() { return inputTokens; }
    public int outputTokens() { return outputTokens; }
    public int cachedTokens() { return cachedTokens; }
    public String usageSource() { return usageSource; }
    public long totalBytes() { return totalBytes; }

    /**
     * 翻译链路专用：直接从规范事件流累计用量。
     * 透传链路的字节嗅探拿不到已解码的 usage，两条路径必须分开记。
     */
    public void acceptCanonical(com.zhouyu.gateway.protocol.canonical.CanonicalChunk chunk) {
        if (chunk instanceof com.zhouyu.gateway.protocol.canonical.CanonicalChunk.UsageChunk u) {
            if (u.inputTokens() > 0 || u.outputTokens() > 0) {
                this.inputTokens = Math.max(this.inputTokens, u.inputTokens());
                this.outputTokens = Math.max(this.outputTokens, u.outputTokens());
                this.cachedTokens = Math.max(this.cachedTokens, u.cachedTokens());
                this.reasoningTokens = Math.max(this.reasoningTokens, u.reasoningTokens());
                this.usageSource = "UPSTREAM";
            }
        } else if (chunk instanceof com.zhouyu.gateway.protocol.canonical.CanonicalChunk.TextDelta t) {
            // 兜底估算：按字符数粗估 token（无 usage 时）。
            // 中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token，这里用保守的 3 字符/token。
            this.estimatedOutputChars += t.text().length();
            if (this.estimatedOutputChars > 0 && "ESTIMATED".equals(this.usageSource)) {
                this.outputTokens = Math.max(1, this.estimatedOutputChars / 3);
            }
        }
    }

    private int estimatedOutputChars;
}
