package com.zhouyu.gateway.relay;

import com.zhouyu.gateway.domain.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 上游转发管线。
 *
 * <p>两种模式：
 * <ol>
 *   <li><b>透传</b>：同协议，字节级转发，零拷贝、零解析。</li>
 *   <li><b>翻译</b>：跨协议，把上游 SSE 字节流交给 {@code transformer} 转成客户端协议文本。</li>
 * </ol>
 *
 * <p>红线：一旦向客户端写出首字节，任何失败都不再重试。
 */
@Component
public class UpstreamRelay {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(UpstreamRelay.class);

    private final WebClient webClient;
    private final HttpClient httpClient;

    public UpstreamRelay() {
        ConnectionProvider provider = ConnectionProvider.builder("upstream")
                .maxConnections(2000)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(10))
                .lifo()
                .build();

        this.httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofSeconds(600))
                .compress(true)
                .followRedirect(false);

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 透传模式：字节原样转发。
     */
    public Mono<Void> relay(ServerHttpRequest inbound,
                            ServerHttpResponse out,
                            Channel channel,
                            String path,
                            byte[] body,
                            boolean stream,
                            FirstByteFlag firstByte,
                            TailUsageSniffer meter) {
        return relay(inbound, out, channel, path, body, stream, firstByte, meter, java.util.Map.of());
    }

    public Mono<Void> relay(ServerHttpRequest inbound, ServerHttpResponse out, Channel channel, String path, byte[] body,
                            boolean stream, FirstByteFlag firstByte, TailUsageSniffer meter, java.util.Map<String, String> headers) {
        meter.upstreamEndpoint(path);
        Mono<Void> request = exchange(inbound.getHeaders(), channel, path, body, stream, headers, resp -> {
            copyHeaders(resp.headers().asHttpHeaders(), out.getHeaders());
            if (stream) {
                out.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                out.getHeaders().setCacheControl("no-cache");
                out.getHeaders().set("X-Accel-Buffering", "no");
            }
            if (!stream) {
                return resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(raw -> {
                    meter.parseWhole(raw);
                    firstByte.set();
                    return out.writeWith(Mono.just(out.bufferFactory().wrap(raw)));
                });
            }
            Flux<byte[]> frames = resp.bodyToFlux(DataBuffer.class).map(buf -> {
                try {
                    meter.acceptResponses(buf);
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    return bytes;
                } finally {
                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                }
            });
            return writeStream(out, frames, firstByte, meter);
        });
        return stream ? withFirstOutputTimeout(request, firstByte, channel.timeout()) : request;
    }

    /**
     * 翻译模式：上游字节流经 {@code transformer} 转换为客户端 SSE 文本。
     *
     * @param transformer 输入上游字节流，输出客户端 SSE 字符串流
     */
    public Mono<Void> relayTranslated(ServerHttpRequest inbound,
                                      ServerHttpResponse out,
                                      Channel channel,
                                      String path,
                                      byte[] body,
                                      boolean stream,
                                      FirstByteFlag firstByte,
                                      TailUsageSniffer meter,
                                      Function<Flux<byte[]>, Flux<String>> transformer) {
        return relayTranslated(inbound, out, channel, path, body, stream, firstByte, meter, transformer, java.util.Map.of(), true);
    }

    public Mono<Void> relayTranslated(ServerHttpRequest inbound, ServerHttpResponse out, Channel channel, String path, byte[] body,
            boolean stream, FirstByteFlag firstByte, TailUsageSniffer meter, Function<Flux<byte[]>, Flux<String>> transformer,
            java.util.Map<String, String> headers, boolean streamOut) {
        meter.upstreamEndpoint(path);
        Mono<Void> request = exchange(inbound.getHeaders(), channel, path, body, stream, headers, resp -> {
            Flux<byte[]> raw = resp.bodyToFlux(DataBuffer.class)
                    .map(buf -> {
                        try {
                            meter.acceptResponses(buf);
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            return bytes;
                        } finally {
                            org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                        }
                    });

            Flux<String> translated = transformer.apply(raw);
            if (!streamOut) {
                return translated.filter(event -> event.startsWith("event: response.completed\n")
                                || event.startsWith("event: response.incomplete\n") || event.startsWith("event: response.failed\n"))
                        .next().switchIfEmpty(Mono.error(new UpstreamError(502, "Incomplete upstream stream")))
                        .flatMap(event -> {
                            if (event.startsWith("event: response.failed\n")) {
                                return Mono.error(new UpstreamError(502, "上游流未正常结束"));
                            }
                            try {
                                byte[] json = objectMapper.readTree(event.substring(event.indexOf("data: ") + 6))
                                        .path("response").toString().getBytes(StandardCharsets.UTF_8);
                                firstByte.set();
                                out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                return out.writeWith(Mono.just(out.bufferFactory().wrap(json)));
                            } catch (java.io.IOException e) { return Mono.error(new UpstreamError(502, "Invalid translated response")); }
                        });
            }
            out.setStatusCode(HttpStatusCode.valueOf(200));
            out.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            out.getHeaders().setCacheControl("no-cache");
            out.getHeaders().set("X-Accel-Buffering", "no");

            return writeStream(out, translated.map(event -> event.getBytes(StandardCharsets.UTF_8)),
                    firstByte, meter);
        });
        return streamOut ? withFirstOutputTimeout(request, firstByte, channel.timeout()) : request;
    }

    private Mono<Void> writeStream(ServerHttpResponse out, Flux<byte[]> source, FirstByteFlag firstByte,
                                   TailUsageSniffer meter) {
        var gate = new FirstOutput(objectMapper, meter);
        Flux<byte[]> ready = source.handle(gate::accept)
                .concatWith(Mono.defer(() -> gate.open ? Mono.empty()
                        : Mono.error(new UpstreamError(502, "上游在输出前结束"))));
        return ready.switchOnFirst((signal, frames) -> {
            if (!signal.hasValue()) return frames.then();
            firstByte.set();
            return out.writeAndFlushWith(frames.map(bytes -> Mono.just(out.bufferFactory().wrap(bytes))));
        }).then();
    }

    private Mono<Void> withFirstOutputTimeout(Mono<Void> request, FirstByteFlag firstByte, java.time.Duration timeout) {
        Mono<Void> deadline = Mono.delay(timeout)
                .flatMap(ignored -> firstByte.isSet() ? Mono.never() : Mono.error(new UpstreamError(504, "上游首段输出超时")));
        // Race outside exchangeToMono so a stalled response is cancelled, without waiting to drain its body.
        return Mono.firstWithSignal(request, deadline);
    }

    private static final class FirstOutput {
        private static final int MAX_PREFIX = 256 * 1024;
        private final java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        private final ObjectMapper mapper;
        private final TailUsageSniffer meter;
        private boolean open;

        FirstOutput(ObjectMapper mapper, TailUsageSniffer meter) {
            this.mapper = mapper;
            this.meter = meter;
        }

        void accept(byte[] bytes, reactor.core.publisher.SynchronousSink<byte[]> sink) {
            if (open) {
                sink.next(bytes);
                return;
            }
            for (int i = 0; i < bytes.length; i++) {
                byte next = bytes[i];
                prefix.write(next);
                if (next == '\n') {
                    String data = line.toString(StandardCharsets.UTF_8);
                    line.reset();
                    if (data.startsWith("data:")) {
                        try {
                            var event = mapper.readTree(data.substring(5));
                            if (event != null && inspect(event)) {
                                open = true;
                                prefix.write(bytes, i + 1, bytes.length - i - 1);
                                sink.next(prefix.toByteArray());
                                prefix.reset();
                                return;
                            }
                        } catch (java.io.IOException ignored) { }
                    }
                } else line.write(next);
                if (prefix.size() > MAX_PREFIX) {
                    sink.error(new UpstreamError(502, "上游在输出前发送了过多数据"));
                    return;
                }
            }
        }

        private boolean inspect(com.fasterxml.jackson.databind.JsonNode event) {
            String type = event.path("type").asText();
            if (type.equals("error") || type.equals("response.failed") || event.hasNonNull("error")) {
                var error = event.hasNonNull("error") ? event.path("error") : event.path("response").path("error");
                String code = error.path("code").asText(error.path("type").asText(""));
                throw new UpstreamError(code.contains("rate_limit") || code.contains("quota") ? 429 : 502,
                        "上游在输出前返回错误");
            }
            boolean output = type.endsWith(".delta") && (event.path("delta").isObject() || !event.path("delta").asText().isEmpty())
                    || type.equals("content_block_delta") || type.equals("content_block_start") && event.path("content_block").path("type").asText().equals("tool_use")
                    || type.equals("response.output_item.added") && event.path("item").path("type").asText().endsWith("_call")
                    || type.equals("response.completed") && !event.path("response").path("output").isEmpty();
            for (var choice : event.path("choices")) {
                var delta = choice.path("delta");
                output |= !delta.path("content").asText().isEmpty() || !delta.path("reasoning_content").asText().isEmpty()
                        || delta.hasNonNull("tool_calls") || delta.hasNonNull("function_call");
            }
            if (output) meter.markOutput();
            return output || type.equals("response.completed") || type.equals("response.incomplete")
                    || type.equals("message_stop") || event.path("choices").path(0).hasNonNull("finish_reason");
        }
    }

    public Mono<Void> relaySseToJson(ServerHttpRequest inbound, ServerHttpResponse out, Channel channel, String path,
            byte[] body, FirstByteFlag firstByte, TailUsageSniffer meter, java.util.Map<String, String> headers,
            java.util.function.Consumer<String> accept, java.util.function.Supplier<byte[]> finish) {
        meter.upstreamEndpoint(path);
        return collectSse(inbound.getHeaders(), channel, path, body, headers, accept, finish).flatMap(json -> {
            firstByte.set();
            meter.parseWhole(json);
            out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return out.writeWith(Mono.just(out.bufferFactory().wrap(json)));
        });
    }

    public Mono<byte[]> fetchJson(Channel channel, String path, byte[] body) {
        return fetchJson(channel, path, body, java.util.Map.of());
    }

    public Mono<byte[]> fetchJson(Channel channel, String path, byte[] body, java.util.Map<String, String> headers) {
        return exchange(HttpHeaders.EMPTY, channel, path, body, false, headers, response -> response.bodyToMono(byte[].class));
    }

    public Mono<byte[]> collectSse(HttpHeaders inbound, Channel channel, String path, byte[] body, java.util.Map<String, String> headers,
            java.util.function.Consumer<String> accept, java.util.function.Supplier<byte[]> finish) {
        return exchange(inbound, channel, path, body, true, headers, resp ->
                resp.bodyToFlux(new org.springframework.core.ParameterizedTypeReference<org.springframework.http.codec.ServerSentEvent<String>>() {})
                        .doOnNext(event -> {
                            if (event.data() != null) accept.accept(event.data());
                        })
                        .takeUntil(event -> "[DONE]".equals(event.data()))
                        .then(Mono.fromCallable(finish::get)));
    }

    /**
     * 非流式翻译：上游 JSON → 客户端协议 JSON。
     */
    public Mono<Void> relayTranslatedNonStream(ServerHttpRequest inbound,
                                               ServerHttpResponse out,
                                               Channel channel,
                                               String path,
                                               byte[] body,
                                               FirstByteFlag firstByte,
                                               TailUsageSniffer meter,
                                               Function<byte[], byte[]> transformer) {
        meter.upstreamEndpoint(path);
        return exchange(inbound.getHeaders(), channel, path, body, false, java.util.Map.of(), resp ->
                resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(raw -> {
                    firstByte.set();
                    byte[] converted = transformer.apply(raw);
                    meter.parseWhole(converted);
                    out.setStatusCode(HttpStatusCode.valueOf(200));
                    out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return out.writeWith(Mono.just(out.bufferFactory().wrap(converted)));
                }));
    }

    /** 统一的请求发送与错误处理。 */
    private <T> Mono<T> exchange(HttpHeaders inbound,
                                Channel channel,
                                String path,
                                byte[] body,
                                boolean stream,
                                java.util.Map<String, String> headers,
                                Function<org.springframework.web.reactive.function.client.ClientResponse, Mono<T>> handler) {

        String url = channel.resolve(path);

        WebClient client = channel.proxy() == null ? webClient : webClient.mutate()
                .clientConnector(new ReactorClientHttpConnector(channel.proxy().apply(httpClient))).build();
        return client.method(HttpMethod.POST)
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setAccept(stream
                            ? java.util.List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                            : java.util.List.of(MediaType.APPLICATION_JSON));
                    UpstreamHeaders.authHeaders(channel).forEach(h::set);
                    inbound.forEach((name, values) -> {
                        if (UpstreamHeaders.isForwardable(name) && !values.isEmpty()) {
                            h.set(name, values.get(0));
                        }
                    });
                    headers.forEach(h::set);
                })
                .httpRequest(request -> {
                    if (request.getNativeRequest() instanceof reactor.netty.http.client.HttpClientRequest netty) {
                        netty.responseTimeout(channel.timeout());
                    }
                })
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    HttpStatusCode status = resp.statusCode();
                    if (status.isError()) {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(new UpstreamError(status.value(), errBody, resp.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER))));
                    }
                    return handler.apply(resp);
                })
                .onErrorMap(e -> !(e instanceof UpstreamError), e -> {
                    log.warn("upstream relay error, channel={}, msg={}", channel.name(), e.toString());
                    return new UpstreamError(502, e.getMessage() == null ? "upstream error" : e.getMessage());
                });
    }

    private static void copyHeaders(HttpHeaders from, HttpHeaders to) {
        for (String name : new String[]{"content-type", "request-id", "x-request-id", "anthropic-ratelimit-requests-remaining"}) {
            String v = from.getFirst(name);
            if (v != null) {
                to.set(name, v);
            }
        }
    }

    /** 首字节标记：跨重试共享，一旦置位即禁止重试。 */
    public static final class FirstByteFlag {
        private final AtomicBoolean flag = new AtomicBoolean(false);
        public void set() { flag.set(true); }
        public boolean isSet() { return flag.get(); }
    }

    /** 上游错误。 */
    public static final class UpstreamError extends RuntimeException {
        private final int status;
        private final String body;
        private final String retryAfter;
        public UpstreamError(int status, String message) {
            this(status, message, null);
        }
        public UpstreamError(int status, String message, String retryAfter) {
            super(message);
            this.status = status;
            this.body = message;
            this.retryAfter = retryAfter;
        }
        public long retryAt(long now) {
            if (retryAfter == null) return 0;
            try {
                long seconds = Long.parseLong(retryAfter);
                return seconds > 0 && seconds <= Long.MAX_VALUE - now ? now + seconds : 0;
            } catch (NumberFormatException ignored) {
                try {
                    return java.time.ZonedDateTime.parse(retryAfter, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                } catch (java.time.format.DateTimeParseException invalid) {
                    return 0;
                }
            }
        }
        public int status() { return status; }
        public String body() { return body; }
        public boolean retryable() { return status == 429 || status >= 500; }
    }
}
