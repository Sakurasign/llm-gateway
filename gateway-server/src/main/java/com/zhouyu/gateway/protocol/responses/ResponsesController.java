package com.zhouyu.gateway.protocol.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouyu.gateway.auth.ApiKeyAuthenticator;
import com.zhouyu.gateway.accounts.AccountAuthorization;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.billing.CostCalculator;
import com.zhouyu.gateway.billing.PricingService;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.protocol.openai.ChatCompletionCollector;
import com.zhouyu.gateway.quota.QuotaService;
import com.zhouyu.gateway.relay.TailUsageSniffer;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.RouteResolver;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import com.zhouyu.gateway.usage.UsageEvent;
import com.zhouyu.gateway.usage.UsagePublisher;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class ResponsesController {
    private final ApiKeyAuthenticator auth;
    private final RouteTableHolder tables;
    private final RouteResolver resolver;
    private final CircuitBreaker breaker;
    private final UpstreamRelay relay;
    private final ObjectMapper mapper;
    private final QuotaService quota;
    private final CostCalculator costs;
    private final PricingService pricing;
    private final UsagePublisher usage;
    private final ResponsesChatAdapter adapter;
    private final AccountPool accounts;
    private final AccountRouting accountRouting;
    private final Cache<WorkSession, Long> workSessions = Caffeine.newBuilder().maximumSize(20_000)
            .expireAfterAccess(Duration.ofHours(2)).build();
    private final Cache<Continuation, Target> continuations = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterWrite(Duration.ofHours(12)).build();

    public ResponsesController(ApiKeyAuthenticator auth, RouteTableHolder tables, RouteResolver resolver,
                               CircuitBreaker breaker, UpstreamRelay relay, ObjectMapper mapper, QuotaService quota,
                               CostCalculator costs, PricingService pricing, UsagePublisher usage, AccountPool accounts, AccountRouting accountRouting) {
        this.auth = auth;
        this.tables = tables;
        this.resolver = resolver;
        this.breaker = breaker;
        this.relay = relay;
        this.mapper = mapper;
        this.quota = quota;
        this.costs = costs;
        this.pricing = pricing;
        this.usage = usage;
        this.accounts = accounts;
        this.accountRouting = accountRouting;
        this.adapter = new ResponsesChatAdapter(mapper);
    }

    public record ModelTestInput(String model) {}
    public record ModelTestResult(boolean ok, long elapsedMs, String output, String message) {}

    @PostMapping("/admin/api/v1/accounts/{id}/test-model")
    public Mono<ModelTestResult> testModel(@PathVariable long id, @RequestBody ModelTestInput input) {
        String model = input.model() == null ? "" : input.model().strip();
        if (model.isEmpty() || model.length() > 128 || model.contains("*") || model.chars().anyMatch(Character::isISOControl)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择具体的上游模型"));
        }
        return runModelCheck(() -> accounts.acquireForTest(id, model), model, "Reply with OK only.", "");
    }

    public Mono<ModelTestResult> wakeCodex(long id, String model, String prompt, String effort) {
        return runModelCheck(() -> accounts.acquireForAccount(AccountPool.CODEX, id, model, java.util.Set.of()), model, prompt, effort);
    }

    private Mono<ModelTestResult> runModelCheck(java.util.concurrent.Callable<AccountPool.Lease> acquire,
                                                 String model, String prompt, String effort) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return Mono.fromCallable(acquire).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(lease -> Mono.defer(() -> testCompletion(lease, model, prompt, effort)).doFinally(signal -> lease.close()))
                    .doOnDiscard(AccountPool.Lease.class, AccountPool.Lease::close)
                    .timeout(Duration.ofSeconds(30))
                    .map(output -> new ModelTestResult(true, elapsedMs(start), output, null))
                    .onErrorResume(ResponseStatusException.class, error -> Mono.just(testFailure(start, error.getReason())))
                    .onErrorResume(UpstreamRelay.UpstreamError.class, error -> Mono.just(testFailure(start, upstreamTestMessage(error.status()))))
                    .onErrorResume(TimeoutException.class, error -> Mono.just(testFailure(start, "模型测试超时，请检查账号或代理网络")));
        });
    }

    private static long elapsedMs(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private static ModelTestResult testFailure(long start, String message) {
        return new ModelTestResult(false, elapsedMs(start), null, message);
    }

    private static String upstreamTestMessage(int status) {
        return switch (status) {
            case 401, 403 -> "上游拒绝授权（HTTP " + status + "），请检查账号授权和模型权限";
            case 402, 429 -> "上游额度不足或限流（HTTP " + status + "）";
            case 400, 404 -> "上游拒绝该模型或模型不可用（HTTP " + status + "）";
            case 502 -> "上游连接失败或模型未完成响应（HTTP 502）";
            default -> "上游返回 HTTP " + status;
        };
    }

    private Mono<String> testCompletion(AccountPool.Lease lease, String model, String prompt, String effort) {
        Protocol protocol = Protocol.valueOf(lease.provider());
        if (protocol == Protocol.ANTHROPIC) {
            var channel = new Channel(lease.id(), "模型测试", protocol, AccountAuthorization.ANTHROPIC_BASE,
                    lease.credential().path("api_key").asText(), 0, 1, 1, Duration.ofSeconds(25), false, true, false, lease.proxy());
            var body = mapper.createObjectNode().put("model", model).put("max_tokens", 32);
            body.putArray("messages").addObject().put("role", "user").put("content", prompt);
            var result = "oauth".equals(lease.realm())
                    ? relay.fetchJson(channel, "/v1/messages", bytes(body), AccountAuthorization.claudeOAuthHeaders(lease.credential()))
                    : relay.fetchJson(channel, "/v1/messages", bytes(body));
            return result.map(json -> testOutput(json, protocol));
        }
        if (protocol == Protocol.CODEX_SUBSCRIPTION && "api".equals(lease.realm())) {
            var channel = new Channel(lease.id(), "模型测试", Protocol.OPENAI_RESPONSES, AccountAuthorization.OPENAI_BASE,
                    lease.credential().path("api_key").asText(), 0, 1, 1, Duration.ofSeconds(25), false, true, false, lease.proxy());
            var body = mapper.createObjectNode().put("model", model).put("input", prompt).put("store", false);
            return relay.fetchJson(channel, "/v1/responses", bytes(body)).map(json -> testOutput(json, Protocol.OPENAI_RESPONSES));
        }
        var connection = accountConnection(new Channel(lease.id(), "模型测试", protocol, "", "", 0, 1, 1,
                Duration.ofSeconds(25), false, true), lease);
        Mono<byte[]> result;
        if (protocol == Protocol.WORKBUDDY) {
            var body = mapper.createObjectNode();
            body.putArray("messages").addObject().put("role", "user").put("content", prompt);
            var collector = new ChatCompletionCollector(mapper, model);
            result = relay.collectSse(HttpHeaders.EMPTY, connection.channel(), connection.path(), bytes(workChatBody(body, workModel(model))),
                    connection.headers(), collector::accept, collector::finish);
        } else {
            var body = codexRequest(mapper.createObjectNode().put("input", prompt), model);
            if (!effort.isEmpty()) body.putObject("reasoning").put("effort", effort);
            var collector = new CodexCollector();
            result = relay.collectSse(HttpHeaders.EMPTY, connection.channel(), connection.path(), bytes(body), connection.headers(), collector::accept, () -> {
                ObjectNode response = collector.finish();
                if (!"completed".equals(response.path("status").asText())) throw new UpstreamRelay.UpstreamError(502, "Codex test did not complete");
                return bytes(response);
            });
        }
        return result.map(json -> testOutput(json, protocol));
    }

    private String testOutput(byte[] json, Protocol protocol) {
        try {
            JsonNode response = mapper.readTree(json);
            String output;
            if (protocol == Protocol.WORKBUDDY) {
                output = response.at("/choices/0/message/content").asText("");
            } else if (protocol == Protocol.ANTHROPIC) {
                var text = new StringBuilder();
                for (JsonNode content : response.path("content")) {
                    if ("text".equals(content.path("type").asText())) text.append(content.path("text").asText());
                }
                output = text.toString();
            } else {
                var text = new StringBuilder();
                for (JsonNode item : response.path("output")) {
                    for (JsonNode content : item.path("content")) {
                        if ("output_text".equals(content.path("type").asText())) text.append(content.path("text").asText());
                    }
                }
                output = text.toString();
            }
            if (output.isBlank()) throw new UpstreamRelay.UpstreamError(502, "Model returned no text");
            return output.length() > 500 ? output.substring(0, 500) + "…" : output;
        } catch (java.io.IOException error) {
            throw new UpstreamRelay.UpstreamError(502, "Invalid test response");
        }
    }

    @GetMapping("/v1/models")
    public Mono<Void> models(ServerHttpRequest request, ServerHttpResponse response) {
        return Mono.defer(() -> {
            ApiKeyView key = auth.authenticate(request);
            RouteTable table = tables.table();
            if (request.getQueryParams().containsKey("client_version")) {
                var manifest = mapper.createObjectNode();
                var models = manifest.putArray("models");
                for (String name : table.knownModels().stream().filter(key::allowsModel).sorted().toList()) {
                    if (table.candidates(name, key).stream().noneMatch(c -> supportsIngress(c.channel().protocol(), false, c.route().accountId()))) continue;
                    var model = models.addObject().put("slug", name).put("display_name", name).put("visibility", "list")
                            .put("supported_in_api", true).put("priority", models.size()).put("shell_type", "shell_command")
                            .put("default_reasoning_summary", "auto").put("support_verbosity", false)
                            .put("supports_reasoning_summaries", false).put("supports_parallel_tool_calls", false)
                            .put("prefer_websockets", false).put("context_window", 128000);
                    model.putObject("truncation_policy").put("mode", "tokens").put("limit", 10000);
                    model.putArray("experimental_supported_tools");
                    var levels = model.putArray("supported_reasoning_levels");
                    boolean reasoning = table.candidates(name, key).stream().anyMatch(c -> c.channel().protocol() == Protocol.CODEX_SUBSCRIPTION);
                    if (reasoning) {
                        model.put("default_reasoning_level", "medium");
                        for (String effort : List.of("low", "medium", "high", "xhigh")) {
                            levels.addObject().put("effort", effort).put("description", effort);
                        }
                    }
                    model.putArray("input_modalities").add("text");
                }
                return json(response, 200, manifest);
            }
            var data = table.knownModels().stream().filter(key::allowsModel)
                    .filter(name -> !table.candidates(name, key).isEmpty()).sorted()
                    .map(name -> Map.of("id", name, "object", "model", "owned_by", "gateway")).toList();
            return json(response, 200, mapper.valueToTree(Map.of("object", "list", "data", data)));
        }).onErrorResume(error -> writeError(response, error));
    }

    @PostMapping({"/v1/responses", "/v1/chat/completions"})
    public Mono<Void> openAi(ServerHttpRequest request, ServerHttpResponse response) {
        Protocol ingress = request.getPath().pathWithinApplication().value().equals("/v1/chat/completions")
                ? Protocol.OPENAI_CHAT : Protocol.OPENAI_RESPONSES;
        return Mono.defer(() -> {
            ApiKeyView key = auth.authenticate(request);
            String id = "req_" + UUID.randomUUID().toString().replace("-", "");
            response.getHeaders().set("x-gateway-request-id", id);
            return DataBufferUtils.join(request.getBody(), 4 * 1024 * 1024)
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return bytes;
                        } finally { DataBufferUtils.release(buffer); }
                    }).defaultIfEmpty(new byte[0]).flatMap(body -> handle(request, response, key, id, body, ingress));
        }).onErrorResume(error -> writeError(response, error));
    }

    private Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response, ApiKeyView key, String id, byte[] raw, Protocol ingress) {
        ObjectNode body;
        try {
            if (!(mapper.readTree(raw) instanceof ObjectNode object)) throw new IllegalArgumentException();
            body = object;
        } catch (Exception e) { return Mono.error(GatewayException.invalidRequest("请求应为 JSON 对象")); }
        String model = body.path("model").asText("");
        if (model.isBlank()) return Mono.error(GatewayException.invalidRequest("缺少 model"));
        if (body.has("stream") && !body.path("stream").isBoolean()) return Mono.error(GatewayException.invalidRequest("stream 必须为布尔值"));
        boolean chat = ingress == Protocol.OPENAI_CHAT;
        if (chat && !body.path("messages").isArray()) return Mono.error(GatewayException.invalidRequest("messages 必须为数组"));
        if (chat && body.hasNonNull("stream_options") && !body.path("stream_options").isObject()) {
            return Mono.error(GatewayException.invalidRequest("stream_options 必须为对象"));
        }
        if (!chat && body.path("background").asBoolean()) return Mono.error(GatewayException.invalidRequest("暂不支持后台任务"));
        if (!key.allowsModel(model)) return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND));
        boolean stream = body.path("stream").asBoolean();
        var table = tables.table();
        var candidates = table.candidates(model, key).stream().filter(c ->
                supportsIngress(c.channel().protocol(), chat, c.route().accountId())).toList();
        if (!chat && body.hasNonNull("previous_response_id")) {
            if (!body.path("previous_response_id").isTextual()) return Mono.error(GatewayException.invalidRequest("previous_response_id 无效"));
            Target target = continuations.getIfPresent(new Continuation(key.tenantId(), key.id(), body.path("previous_response_id").asText()));
            if (target == null) return Mono.error(GatewayException.invalidRequest("续轮不可用，请重发完整 input"));
            candidates = candidates.stream().filter(c -> target.equals(Target.from(c))).toList();
        }
        if (candidates.isEmpty()) return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND));
        List<RouteTable.Candidate> available = candidates;
        boolean onlySubscription = candidates.stream().allMatch(c -> c.channel().protocol() == Protocol.CODEX_SUBSCRIPTION);
        int maxTokens = chat ? body.path("max_completion_tokens").asInt(body.path("max_tokens").asInt(4096))
                : body.path("max_output_tokens").asInt(4096);
        long reserve = onlySubscription ? 0 : costs.estimateReserveMicro(pricing.ruleFor(model), Math.max(1, raw.length / 3),
                Math.max(1, maxTokens));
        Mono<Void> checkSpend = key.spendLimits().enabled() ? auth.checkSpend(key) : Mono.empty();
        return checkSpend.then(Mono.defer(() -> quota.reserve(key.tenantId(), key.id(), id, reserve))).flatMap(result -> {
            if (result == QuotaService.ReserveResult.INSUFFICIENT) return Mono.error(new GatewayException(ErrorCode.INSUFFICIENT_QUOTA));
            var plan = accountRouting.plan(table, key, model, available, request, raw);
            var call = new Call(request, response, key, id, model, stream, body, ingress, System.currentTimeMillis(),
                    plan.session(), new RouteTrace(mapper.createArrayNode()));
            return attempt(call, plan.candidates(), 0, null)
                    .onErrorResume(error -> publishRouteFailure(call, error).then(Mono.error(error)))
                    .doFinally(signal -> {
                        if (signal != SignalType.ON_COMPLETE) quota.release(key.tenantId(), id).onErrorResume(e -> Mono.empty()).subscribe();
                    });
        });
    }

    private static boolean supportsIngress(Protocol protocol, boolean chat, Long accountId) {
        return switch (protocol) {
            case OPENAI_CHAT, WORKBUDDY -> true;
            case OPENAI_RESPONSES -> !chat || accountId != null;
            case CODEX_SUBSCRIPTION, ANTHROPIC -> !chat;
            default -> false;
        };
    }

    private Mono<Void> attempt(Call call, List<RouteTable.Candidate> candidates, int retry,
                               UpstreamRelay.UpstreamError previousError) {
        return Mono.defer(() -> {
            boolean grouped = call.key().accountGroup() != null;
            RouteResolver.Decision decision;
            try {
                decision = grouped ? resolver.resolveOrdered(call.model(), candidates)
                        : resolver.resolve(call.model(), candidates, call.ingress());
            } catch (GatewayException e) {
                if (grouped && e.errorCode() == ErrorCode.UPSTREAM_UNAVAILABLE) {
                    candidates.forEach(candidate -> call.trace().skip(candidate, "INFLIGHT_FULL"));
                }
                if (previousError != null && e.errorCode() == ErrorCode.UPSTREAM_UNAVAILABLE) return Mono.error(previousError);
                return Mono.error(e);
            }
            var candidate = decision.selected();
            var channel = candidate.channel();
            var route = candidate.route();
            call.trace().candidate(decision);
            TailUsageSniffer meter = new TailUsageSniffer(mapper);
            var first = new UpstreamRelay.FirstByteFlag();
            boolean subscription = channel.protocol() == Protocol.WORKBUDDY || channel.protocol() == Protocol.CODEX_SUBSCRIPTION;
            return Mono.defer(() -> {
                if (subscription) {
                    return accountAttempt(call, candidate, meter, first, java.util.Set.of());
                }
                if (channel.protocol() == Protocol.ANTHROPIC) {
                    return claudeAttempt(call, candidate, meter, first);
                }
                if (channel.protocol() == Protocol.OPENAI_RESPONSES && route.accountId() != null) {
                    return openAiKeyAttempt(call, candidate, meter, first);
                }
                if (call.ingress() == Protocol.OPENAI_CHAT) {
                    ObjectNode body = call.body().deepCopy().put("model", route.upstreamModel());
                    return relay.relay(call.request(), call.response(), channel, "/v1/chat/completions", bytes(body), call.stream(), first, meter);
                }
                if (channel.protocol() == Protocol.OPENAI_RESPONSES) {
                    ObjectNode nativeBody = call.body().deepCopy().put("model", route.upstreamModel());
                    return relay.relay(call.request(), call.response(), channel, "/v1/responses", bytes(nativeBody), call.stream(), first, meter);
                }
                byte[] chat = adapter.request(call.body(), route.upstreamModel(), call.stream());
                if (call.stream()) return relay.relayTranslated(call.request(), call.response(), channel, "/v1/chat/completions", chat,
                        true, first, meter, raw -> adapter.stream(raw, call.model(), meter));
                return relay.relayTranslatedNonStream(call.request(), call.response(), channel, "/v1/chat/completions", chat,
                        first, meter, raw -> adapter.single(raw, call.model()));
            }).doOnError(error -> {
                if (!subscription) call.trace().attempt(candidate, route.accountId(), responseErrorStatus(error));
            }).then(Mono.defer(() -> {
                if (meter.responseId() != null && "completed".equals(meter.responseStatus())
                        && channel.protocol() == Protocol.OPENAI_RESPONSES && call.body().path("store").asBoolean(true)) {
                    continuations.put(new Continuation(call.key().tenantId(), call.key().id(), meter.responseId()), Target.from(candidate));
                }
                int status = "failed".equals(meter.responseStatus()) || "in_progress".equals(meter.responseStatus()) ? 502 : 200;
                if (!subscription) call.trace().attempt(candidate, route.accountId(), status);
                if (status == 200) {
                    breaker.recordSuccess(channel.id(), route.upstreamModel());
                    accountRouting.remember(call.session(), route.accountId());
                }
                else if (channel.protocol() != Protocol.CODEX_SUBSCRIPTION && channel.protocol() != Protocol.WORKBUDDY) {
                    breaker.recordFailure(channel.id(), route.upstreamModel());
                }
                var cost = channel.protocol() == Protocol.CODEX_SUBSCRIPTION ? null
                        : costs.calculate(pricing.ruleFor(call.model()), meter.inputTokens(), meter.outputTokens(), meter.cachedTokens());
                long price = cost == null ? 0 : cost.microUsd();
                return quota.settle(call.key().tenantId(), call.id(), price, meter.usageSource())
                        .onErrorResume(error -> Mono.empty()).then(Mono.fromRunnable(() -> usage.publish(new UsageEvent(
                                call.id(), call.key().tenantId(), call.key().id(), call.model(), route.upstreamModel(), channel.id(), channel.name(),
                                call.ingress().name(), channel.protocol().name(), call.stream(), status, status == 200 ? null : "upstream_error",
                                meter.inputTokens(), meter.outputTokens(), meter.cachedTokens(), meter.reasoningTokens(), meter.usageSource(), price,
                                call.stream() ? meter.ttftMs(call.start()) : 0,
                                (int) (System.currentTimeMillis() - call.start()), call.trace().retries(), decision.stickyHit(),
                                call.trace().json(), System.currentTimeMillis(), call.trace().lastAccountId, groupId(candidate, call.key()),
                                call.request().getPath().value(), meter.upstreamEndpoint(), UsageEvent.reasoningEffort(call.body()),
                                channel.protocol() == Protocol.CODEX_SUBSCRIPTION ? "SUBSCRIPTION" : "METERED",
                                meter.cacheCreationTokens(), meter.uncachedInputTokens(), cost == null ? null : cost.accountMicroUsd()),
                                call.key().managed()))
                                .subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> Mono.empty())).then();
            })).onErrorResume(error -> {
                call.trace().lastMeter = meter;
                if (!subscription && meter.upstreamEndpoint() != null) {
                    call.trace().attempt(candidate, route.accountId(), responseErrorStatus(error));
                }
                UpstreamRelay.UpstreamError upstream = error instanceof UpstreamRelay.UpstreamError e ? e : null;
                boolean unavailable = error instanceof GatewayException e && e.errorCode() == ErrorCode.UPSTREAM_UNAVAILABLE;
                boolean canRetry;
                if (upstream != null) {
                    if (subscription && !grouped) return Mono.error(error);
                    if (!subscription && upstream.retryable()) breaker.recordFailure(channel.id(), route.upstreamModel());
                    boolean accountRejected = grouped && AccountRouting.accountRejected(upstream.status());
                    canRetry = accountRejected || (upstream.retryable() && retry < 2);
                } else {
                    canRetry = grouped && unavailable;
                }
                if (!canRetry || first.isSet() || call.response().isCommitted()) return Mono.error(error);
                var lastUpstream = upstream != null ? upstream : previousError;
                var next = candidates.stream().filter(c -> c.channel().id() != channel.id()).toList();
                if (next.isEmpty()) return Mono.error(unavailable && lastUpstream != null ? lastUpstream : error);
                return attempt(call, next, retry + (upstream == null ? 0 : 1), lastUpstream);
            }).doFinally(signal -> resolver.release(decision));
        });
    }

    private Mono<Void> claudeAttempt(Call call, RouteTable.Candidate candidate, TailUsageSniffer meter,
                                     UpstreamRelay.FirstByteFlag first) {
        var configured = candidate.channel();
        String model = candidate.route().upstreamModel();
        byte[] body = adapter.anthropicRequest(call.body(), model);
        if (candidate.route().accountId() == null) return relayClaude(call, configured, meter, first, body, Map.of());
        return Mono.fromCallable(() -> accounts.acquireForAccount(AccountPool.ANTHROPIC, candidate.route().accountId(), model, java.util.Set.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(lease -> Mono.defer(() -> {
                    var channel = new Channel(configured.id(), configured.name(), configured.protocol(), configured.baseUrl(),
                            lease.credential().path("api_key").asText(), configured.priority(), configured.weight(), configured.maxInflight(),
                            configured.timeout(), configured.passthrough(), configured.enabled(), false, lease.proxy());
                    Map<String, String> headers = "oauth".equals(lease.realm()) ? AccountAuthorization.claudeOAuthHeaders(lease.credential()) : Map.of();
                    return relayClaude(call, channel, meter, first, body, headers)
                            .then(Mono.defer(() -> record(lease, model, "failed".equals(meter.responseStatus()) ? 502 : 200, meter, false)))
                            .onErrorResume(UpstreamRelay.UpstreamError.class, error ->
                                    record(lease, model, error.status(), meter, false, error.retryAt(Instant.now().getEpochSecond())).then(Mono.error(error)));
                }).doFinally(signal -> lease.close()))
                .doOnDiscard(AccountPool.Lease.class, AccountPool.Lease::close);
    }

    private Mono<Void> relayClaude(Call call, Channel channel, TailUsageSniffer meter, UpstreamRelay.FirstByteFlag first,
                                  byte[] body, Map<String, String> headers) {
        return relay.relayTranslated(call.request(), call.response(), channel, "/v1/messages", body, true, first, meter,
                raw -> adapter.anthropicStream(raw, call.model(), meter), headers, call.stream());
    }

    private Mono<Void> openAiKeyAttempt(Call call, RouteTable.Candidate candidate, TailUsageSniffer meter,
                                         UpstreamRelay.FirstByteFlag first) {
        var configured = candidate.channel();
        var route = candidate.route();
        String upstream = route.upstreamModel();
        return Mono.fromCallable(() -> accounts.acquireForAccount(AccountPool.CODEX, route.accountId(), upstream, java.util.Set.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(lease -> Mono.defer(() -> {
                    var effective = new Channel(configured.id(), configured.name(), configured.protocol(), configured.baseUrl(),
                            lease.credential().path("api_key").asText(), configured.priority(), configured.weight(), configured.maxInflight(),
                            configured.timeout(), configured.passthrough(), configured.enabled(), false, lease.proxy());
                    String path = call.ingress() == Protocol.OPENAI_CHAT ? "/v1/chat/completions" : "/v1/responses";
                    byte[] body = bytes(call.body().deepCopy().put("model", upstream));
                    return relay.relay(call.request(), call.response(), effective, path, body, call.stream(), first, meter)
                            .then(Mono.defer(() -> record(lease, upstream,
                                    "failed".equals(meter.responseStatus()) || "in_progress".equals(meter.responseStatus()) ? 502 : 200, meter, false)))
                            .onErrorResume(UpstreamRelay.UpstreamError.class, error ->
                                    record(lease, upstream, error.status(), meter, false, error.retryAt(Instant.now().getEpochSecond()))
                                            .then(Mono.error(error)));
                }).doFinally(signal -> lease.close()))
                .doOnDiscard(AccountPool.Lease.class, AccountPool.Lease::close);
    }

    private Mono<Void> accountAttempt(Call call, RouteTable.Candidate candidate, TailUsageSniffer meter,
                                      UpstreamRelay.FirstByteFlag first, java.util.Set<Long> attempted) {
        Channel configured = candidate.channel();
        boolean work = configured.protocol() == Protocol.WORKBUDDY;
        String upstream = candidate.route().upstreamModel();
        if (!work && (call.body().hasNonNull("conversation") || call.body().hasNonNull("previous_response_id"))) {
            call.trace().skip(candidate, "INVALID_CONTEXT");
            return Mono.error(GatewayException.invalidRequest("订阅渠道需要在 input 中发送完整上下文"));
        }
        String session = call.request().getHeaders().getFirst("X-Session-Id");
        if (session == null) session = call.body().path("prompt_cache_key").asText("");
        WorkSession affinity = session.isBlank() || session.length() > 128 || !work ? null
                : new WorkSession(call.key().tenantId(), call.key().id(), configured.id(), upstream, session);
        Long preferred = affinity == null ? null : workSessions.getIfPresent(affinity);
        return Mono.fromCallable(() -> {
                    String provider = work ? AccountPool.WORKBUDDY : AccountPool.CODEX;
                    if (candidate.route().accountId() != null) {
                        return accounts.acquireForAccount(provider, candidate.route().accountId(), upstream, attempted);
                    }
                    return accounts.acquire(provider, call.key().id(), upstream, attempted, preferred);
                }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> call.trace().skip(candidate, error instanceof GatewayException gateway
                        ? gateway.getMessage() : "ACCOUNT_UNAVAILABLE"))
                .flatMap(lease -> Mono.defer(() -> {
                    var connection = accountConnection(configured, lease);
                    Channel effective = connection.channel();
                    Map<String, String> headers = connection.headers();
                    if (!work) {
                        ObjectNode body = codexRequest(call.body(), upstream);
                        if (call.stream()) return relay.relay(call.request(), call.response(), effective, connection.path(), bytes(body), true, first, meter, headers);
                        var collector = new CodexCollector();
                        return relay.relaySseToJson(call.request(), call.response(), effective, connection.path(), bytes(body), first, meter, headers,
                                collector::accept, () -> bytes(collector.finish()));
                    }
                    String model = workModel(upstream);
                    if (call.ingress() == Protocol.OPENAI_CHAT) {
                        ObjectNode body = workChatBody(call.body(), model);
                        if (call.stream()) {
                            return relay.relay(call.request(), call.response(), effective, connection.path(), bytes(body), true, first, meter, headers);
                        }
                        var collector = new ChatCompletionCollector(mapper, call.model());
                        return relay.relaySseToJson(call.request(), call.response(), effective, connection.path(),
                                bytes(body), first, meter, headers, collector::accept, collector::finish);
                    }
                    byte[] chat = adapter.request(call.body(), model, true);
                    return relay.relayTranslated(call.request(), call.response(), effective, connection.path(), chat, true, first,
                            meter, raw -> adapter.stream(raw, call.model(), meter), headers, call.stream());
                }).doOnError(error -> call.trace().attempt(candidate, lease.id(), responseErrorStatus(error)))
                .then(Mono.defer(() -> {
                    int status = "failed".equals(meter.responseStatus()) || "in_progress".equals(meter.responseStatus()) ? 502 : 200;
                    call.trace().attempt(candidate, lease.id(), status);
                    if (status == 200 && affinity != null) workSessions.put(affinity, lease.id());
                    return record(lease, upstream, status, meter, false);
                })).onErrorResume(UpstreamRelay.UpstreamError.class, error -> {
                    boolean modelLimit = work && error.status() == 429 && error.body().contains("6004");
                    return record(lease, upstream, error.status(), meter, modelLimit, cooldownUntil(lease, error)).then(Mono.defer(() -> {
                        if (!work || candidate.route().accountId() != null || first.isSet() || call.response().isCommitted() || attempted.size() >= 2
                                || !(error.retryable() || error.status() == 401 || error.status() == 402)) return Mono.error(error);
                        var excluded = new java.util.HashSet<>(attempted);
                        excluded.add(lease.id());
                        return accountAttempt(call, candidate, meter, first, excluded)
                                .onErrorResume(GatewayException.class, unavailable -> Mono.error(error));
                    }));
                }).doFinally(signal -> lease.close()))
                .doOnDiscard(AccountPool.Lease.class, AccountPool.Lease::close);
    }

    private record AccountConnection(Channel channel, String path, Map<String, String> headers) {}

    private static AccountConnection accountConnection(Channel configured, AccountPool.Lease lease) {
        boolean work = configured.protocol() == Protocol.WORKBUDDY;
        var channel = new Channel(configured.id(), configured.name(), configured.protocol(),
                work ? AccountAuthorization.workBase(lease.realm()) : "https://chatgpt.com",
                lease.credential().path("access_token").asText(), configured.priority(), configured.weight(),
                configured.maxInflight(), configured.timeout(), configured.passthrough(), true, false, lease.proxy());
        Map<String, String> headers = new java.util.HashMap<>();
        if (work) {
            headers.put("Origin", AccountAuthorization.workOrigin(lease.realm()));
            headers.put("Referer", AccountAuthorization.workOrigin(lease.realm()) + "/");
            headers.put("X-Requested-With", "XMLHttpRequest");
            headers.put("X-CodeBuddy-Request", "1");
            AccountAuthorization.workIdentity(lease.credential(), headers::put);
            String deviceToken = lease.credential().path("device_token").asText();
            if (!deviceToken.isBlank() && !deviceToken.contains("\r") && !deviceToken.contains("\n")) {
                headers.put("X-Device-Token", deviceToken);
            }
        } else {
            AccountAuthorization.codexIdentity(lease.credential(), headers::put);
        }
        return new AccountConnection(channel, work ? "/v2/chat/completions" : "/backend-api/codex/responses", headers);
    }

    private static String workModel(String upstream) {
        return upstream.startsWith("cn:") || upstream.startsWith("global:") ? upstream.substring(upstream.indexOf(':') + 1) : upstream;
    }

    private static ObjectNode workChatBody(ObjectNode original, String model) {
        ObjectNode body = original.deepCopy().put("model", model).put("stream", true);
        if (body.path("stream_options").isNull()) body.remove("stream_options");
        body.withObjectProperty("stream_options").put("include_usage", true);
        return body;
    }

    private final class CodexCollector {
        private JsonNode result;

        void accept(String event) {
            if ("[DONE]".equals(event)) return;
            try {
                JsonNode message = mapper.readTree(event);
                String type = message.path("type").asText();
                if ("response.completed".equals(type) || "response.incomplete".equals(type) || "response.failed".equals(type)) {
                    result = message.path("response");
                }
            } catch (java.io.IOException e) { throw new UpstreamRelay.UpstreamError(502, "Invalid Codex stream"); }
        }

        ObjectNode finish() {
            if (!(result instanceof ObjectNode object)) throw new UpstreamRelay.UpstreamError(502, "Incomplete Codex stream");
            return object;
        }
    }

    static ObjectNode codexRequest(ObjectNode original, String model) {
        ObjectNode body = original.deepCopy().put("model", model).put("store", false).put("stream", true);
        body.remove(java.util.List.of("max_output_tokens", "max_completion_tokens", "temperature", "top_p",
                "frequency_penalty", "presence_penalty", "chat_template_kwargs", "user", "metadata",
                "prompt_cache_retention", "safety_identifier", "stream_options", "truncation", "stop_sequences", "background"));
        if (body.path("instructions").asText().isBlank()) body.put("instructions", "You are a helpful assistant.");
        if (body.path("input").isTextual()) {
            String input = body.path("input").asText();
            var messages = body.putArray("input");
            if (!input.isBlank()) messages.addObject().put("type", "message").put("role", "user")
                    .putArray("content").addObject().put("type", "input_text").put("text", input);
        }
        return body;
    }

    private long cooldownUntil(AccountPool.Lease lease, UpstreamRelay.UpstreamError error) {
        if (error.status() != 429 && error.status() != 402) return 0;
        long now = java.time.Instant.now().getEpochSecond();
        long until = error.retryAt(now);
        if (lease.provider().equals(AccountPool.CODEX) && error.status() == 429) {
            try {
                JsonNode body = mapper.readTree(error.body());
                JsonNode detail = body == null ? null : body.path("error");
                if (detail != null && "usage_limit_reached".equals(detail.path("type").asText())) {
                    long absolute = detail.path("resets_at").asLong();
                    long relative = detail.path("resets_in_seconds").asLong();
                    if (absolute > now) until = Math.max(until, absolute);
                    else if (relative > 0 && relative <= Long.MAX_VALUE - now) until = Math.max(until, now + relative);
                }
            } catch (java.io.IOException ignored) { }
        }
        return until;
    }

    private Mono<Void> record(AccountPool.Lease lease, String model, int status, TailUsageSniffer meter, boolean modelLimit) {
        return record(lease, model, status, meter, modelLimit, 0);
    }

    private Mono<Void> record(AccountPool.Lease lease, String model, int status, TailUsageSniffer meter, boolean modelLimit, long cooldownUntil) {
        return Mono.fromRunnable(() -> accounts.result(lease, model, status, meter.inputTokens(), meter.outputTokens(), modelLimit, cooldownUntil))
                .subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> Mono.empty()).then();
    }

    private static Long groupId(RouteTable.Candidate candidate, ApiKeyView key) {
        if (candidate == null) return null;
        return candidate.groupId() == null ? key.groupId() : candidate.groupId();
    }

    private Mono<Void> publishRouteFailure(Call call, Throwable error) {
        var selected = call.trace().lastCandidate;
        var channel = selected == null ? null : selected.channel();
        TailUsageSniffer meter = call.trace().lastMeter;
        var event = new UsageEvent(call.id(), call.key().tenantId(), call.key().id(), call.model(),
                selected == null ? null : selected.route().upstreamModel(), channel == null ? 0 : channel.id(),
                channel == null ? null : channel.name(), call.ingress().name(),
                channel == null ? call.ingress().name() : channel.protocol().name(), call.stream(),
                responseErrorStatus(error), responseErrorCode(error).code(), meter == null ? 0 : meter.inputTokens(),
                meter == null ? 0 : meter.outputTokens(), meter == null ? 0 : meter.cachedTokens(),
                meter == null ? 0 : meter.reasoningTokens(), meter == null ? "ESTIMATED" : meter.usageSource(), 0,
                meter == null || !call.stream() ? 0 : meter.ttftMs(call.start()),
                (int) (System.currentTimeMillis() - call.start()), call.trace().retries(), false, call.trace().json(), System.currentTimeMillis(),
                call.trace().lastAccountId, groupId(selected, call.key()), call.request().getPath().value(), meter == null ? null : meter.upstreamEndpoint(),
                UsageEvent.reasoningEffort(call.body()), null, meter == null ? null : meter.cacheCreationTokens(),
                meter == null ? null : meter.uncachedInputTokens(), null);
        return Mono.fromRunnable(() -> usage.publish(event, call.key().managed()))
                .subscribeOn(Schedulers.boundedElastic()).onErrorResume(ignored -> Mono.empty()).then();
    }

    private static ErrorCode responseErrorCode(Throwable error) {
        if (error instanceof GatewayException gateway) return gateway.errorCode();
        if (error instanceof DataBufferLimitException) return ErrorCode.INVALID_REQUEST;
        if (error instanceof UpstreamRelay.UpstreamError upstream) {
            return upstream.status() == 429 ? ErrorCode.UPSTREAM_RATE_LIMITED : ErrorCode.UPSTREAM_UNAVAILABLE;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private static int responseErrorStatus(Throwable error) {
        if (error instanceof UpstreamRelay.UpstreamError upstream) return upstream.status();
        if (error instanceof DataBufferLimitException) return 413;
        return responseErrorCode(error).httpStatus();
    }

    private Mono<Void> writeError(ServerHttpResponse response, Throwable error) {
        if (response.isCommitted()) return Mono.error(error);
        ErrorCode code = responseErrorCode(error);
        String message = error instanceof GatewayException ? error.getMessage() : code.defaultMessage();
        if (error instanceof DataBufferLimitException) message = "请求超过大小限制";
        ObjectNode body = mapper.createObjectNode();
        body.putObject("error").put("message", message).put("type", code.type()).put("code", code.code());
        return json(response, responseErrorStatus(error), body);
    }

    private Mono<Void> json(ServerHttpResponse response, int status, ObjectNode body) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes(body))));
    }

    private static byte[] bytes(ObjectNode body) { return body.toString().getBytes(StandardCharsets.UTF_8); }

    private record Call(ServerHttpRequest request, ServerHttpResponse response, ApiKeyView key, String id, String model,
                        boolean stream, ObjectNode body, Protocol ingress, long start, AccountRouting.Session session,
                        RouteTrace trace) {}

    private static final class RouteTrace {
        private final ArrayNode steps;
        private RouteTable.Candidate lastCandidate;
        private int attempts;
        private Long lastAccountId;
        private TailUsageSniffer lastMeter;

        private RouteTrace(ArrayNode steps) { this.steps = steps; }

        private void candidate(RouteResolver.Decision decision) {
            lastCandidate = decision.selected();
            lastAccountId = lastCandidate.route().accountId();
            lastMeter = null;
            decision.filtered().forEach((name, reason) -> steps.addObject().put("channel", name).put("reason", reason));
        }

        private void skip(RouteTable.Candidate candidate, String reason) {
            step(candidate, candidate.route().accountId()).put("reason", reason);
        }

        private void attempt(RouteTable.Candidate candidate, Long accountId, int status) {
            lastAccountId = accountId;
            step(candidate, accountId).put("status", status);
            attempts++;
        }

        private ObjectNode step(RouteTable.Candidate candidate, Long accountId) {
            var step = steps.addObject().put("channel", candidate.channel().name());
            if (accountId != null) step.put("accountId", accountId);
            if (candidate.groupId() != null) step.put("groupId", candidate.groupId());
            return step;
        }

        private int retries() { return Math.min(127, Math.max(0, attempts - 1)); }
        private String json() { return steps.toString(); }
    }
    private record WorkSession(long tenantId, long keyId, long channelId, String model, String session) {}
    private record Continuation(long tenantId, long keyId, String upstreamId) {}
    private record Target(long channelId, String baseUrl, String keyHash, String model) {
        static Target from(RouteTable.Candidate c) {
            return new Target(c.channel().id(), c.channel().baseUrl(), RouteTable.sha256(c.channel().apiKey()), c.route().upstreamModel());
        }
    }
}
