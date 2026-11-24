package com.zhouyu.gateway.protocol.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.accounts.AccountAuthorization;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.auth.ApiKeyAuthenticator;
import com.zhouyu.gateway.usage.UsageEvent;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zhouyu.gateway.billing.CostCalculator;
import com.zhouyu.gateway.billing.MultimodalUsageExtractor;
import com.zhouyu.gateway.billing.PriceRule;
import com.zhouyu.gateway.billing.PricingService;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.protocol.canonical.CanonicalRequest;
import com.zhouyu.gateway.protocol.gemini.GeminiRequestBuilder;
import com.zhouyu.gateway.protocol.gemini.GeminiToAnthropicNonStream;
import com.zhouyu.gateway.protocol.openai.OpenAiChatRequestBuilder;
import com.zhouyu.gateway.protocol.sse.SseBridge;
import com.zhouyu.gateway.observability.GatewayMetrics;
import com.zhouyu.gateway.usage.UsagePublisher;
import com.zhouyu.gateway.quota.QuotaService;
import com.zhouyu.gateway.relay.RequestRewriter;
import com.zhouyu.gateway.relay.TailUsageSniffer;
import com.zhouyu.gateway.relay.UpstreamRelay;
import com.zhouyu.gateway.routing.AccountRouting;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.DynamicWeightManager;
import com.zhouyu.gateway.routing.RouteResolver;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import com.zhouyu.gateway.routing.SessionKeyExtractor;
import com.zhouyu.gateway.routing.StickySessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Anthropic Messages 入口。
 *
 * <p>两条路径：
 * <ul>
 *   <li>渠道协议也是 ANTHROPIC → 字节透传（快车道）</li>
 *   <li>渠道协议是 OPENAI_CHAT → IR 翻译 + SSE 事件转换（慢车道）</li>
 * </ul>
 */
@RestController
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final ApiKeyAuthenticator auth;
    private final AccountPool accounts;
    private final AccountRouting accountRouting;
    private final RouteTableHolder tables;
    private final RouteResolver resolver;
    private final QuotaService quota;
    private final RequestRewriter rewriter;
    private final UpstreamRelay relay;
    private final CircuitBreaker breaker;
    private final ObjectMapper mapper;
    private final AnthropicParser parser;
    private final OpenAiChatRequestBuilder openAiBuilder;
    private final SseBridge sseBridge;
    private final GatewayMetrics metrics;
    private final UsagePublisher usagePublisher;
    private final SessionKeyExtractor sessionKeyExtractor;
    private final StickySessionStore stickyStore;
    private final PricingService pricing;
    private final CostCalculator costCalculator;
    private final GeminiRequestBuilder geminiBuilder;
    private final DynamicWeightManager dynamicWeight;
    private final MultimodalUsageExtractor multimodalExtractor;

    public AnthropicController(ApiKeyAuthenticator auth,
                               AccountPool accounts,
                               AccountRouting accountRouting,
                               RouteTableHolder tables,
                               RouteResolver resolver,
                               QuotaService quota,
                               RequestRewriter rewriter,
                               UpstreamRelay relay,
                               CircuitBreaker breaker,
                               ObjectMapper mapper,
                               AnthropicParser parser,
                               OpenAiChatRequestBuilder openAiBuilder,
                               SseBridge sseBridge,
                               GatewayMetrics metrics,
                               UsagePublisher usagePublisher,
                               SessionKeyExtractor sessionKeyExtractor,
                               StickySessionStore stickyStore,
                               PricingService pricing,
                               CostCalculator costCalculator,
                               GeminiRequestBuilder geminiBuilder,
                               DynamicWeightManager dynamicWeight,
                               MultimodalUsageExtractor multimodalExtractor) {
        this.auth = auth;
        this.accounts = accounts;
        this.accountRouting = accountRouting;
        this.tables = tables;
        this.resolver = resolver;
        this.quota = quota;
        this.rewriter = rewriter;
        this.relay = relay;
        this.breaker = breaker;
        this.mapper = mapper;
        this.parser = parser;
        this.openAiBuilder = openAiBuilder;
        this.sseBridge = sseBridge;
        this.metrics = metrics;
        this.usagePublisher = usagePublisher;
        this.sessionKeyExtractor = sessionKeyExtractor;
        this.stickyStore = stickyStore;
        this.pricing = pricing;
        this.costCalculator = costCalculator;
        this.geminiBuilder = geminiBuilder;
        this.dynamicWeight = dynamicWeight;
        this.multimodalExtractor = multimodalExtractor;
    }

    /**
     * 估算输入 token 数。
     *
     * <p>不引入完整 tokenizer（体积大、模型覆盖不全），用字符数粗估：
     * 中文约 1 字/token，英文约 4 字符/token，混合内容取 3 字符/token 的中位数。
     * 预扣只需量级正确（后续会按实际用量结算修正），这个精度够用。
     */
    private int estimateInputTokens(byte[] body) {
        try {
            var root = mapper.readTree(body);
            StringBuilder sb = new StringBuilder();
            var system = root.get("system");
            if (system != null) {
                sb.append(system.isTextual() ? system.asText() : system.toString());
            }
            var messages = root.get("messages");
            if (messages != null) {
                sb.append(messages.toString());
            }
            return Math.max(1, sb.length() / 3);
        } catch (Exception e) {
            return Math.max(1, body.length / 3);
        }
    }

    /** 从请求体读 max_tokens，缺省 4096（与 Anthropic 默认一致）。 */
    private int estimateMaxTokens(byte[] body) {
        try {
            var root = mapper.readTree(body);
            var mt = root.get("max_tokens");
            return (mt == null || mt.isNull()) ? 4096 : Math.max(1, mt.asInt());
        } catch (Exception e) {
            return 4096;
        }
    }

    @PostMapping("/v1/messages")
    public Mono<Void> messages(ServerHttpRequest request, ServerHttpResponse response) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        response.getHeaders().set("x-gateway-request-id", requestId);

        ApiKeyView key;
        try {
            key = auth.authenticate(request);
        } catch (GatewayException e) {
            return ErrorResponses.write(response, e, mapper);
        }

        return rewriter.join(response.bufferFactory(), request)
                .flatMap(body -> handle(requestId, key, request, response, body));
    }

    private Mono<Void> handle(String requestId, ApiKeyView key,
                              ServerHttpRequest request, ServerHttpResponse response,
                              byte[] body) {
        String virtualModel;
        boolean stream;
        try {
            virtualModel = rewriter.peekModel(body);
            stream = rewriter.isStream(body);
        } catch (GatewayException e) {
            return ErrorResponses.write(response, e, mapper);
        }

        if (!key.allowsModel(virtualModel)) {
            return ErrorResponses.write(response,
                    new GatewayException(ErrorCode.MODEL_NOT_FOUND, "当前 Key 无权访问模型 " + virtualModel), mapper);
        }

        var table = tables.table();
        List<RouteTable.Candidate> candidates = table.candidates(virtualModel, key).stream()
                .filter(candidate -> switch (candidate.channel().protocol()) {
                    case ANTHROPIC, OPENAI_CHAT, GEMINI -> true;
                    default -> false;
                }).toList();
        if (candidates.isEmpty()) {
            return ErrorResponses.write(response,
                    new GatewayException(ErrorCode.MODEL_NOT_FOUND, "模型 " + virtualModel + " 未配置渠道"), mapper);
        }

        // 按真实定价估算预扣额：输入按实际 token 估、输出按 max_tokens 上限，含 1.2 安全系数
        PriceRule priceRule = pricing.ruleFor(virtualModel);
        int estInput = estimateInputTokens(body);
        int maxTokens = estimateMaxTokens(body);
        long estimateMicro = costCalculator.estimateReserveMicro(priceRule, estInput, maxTokens);

        // 会话标识在入口处解析一次，贯穿整条链路（重试时不再变化）
        long start = System.currentTimeMillis();
        String sessionKey = key.accountGroup() == null ? sessionKeyExtractor.extract(request, body) : null;

        Mono<Void> checkSpend = key.spendLimits().enabled() ? auth.checkSpend(key) : Mono.empty();
        return checkSpend.then(Mono.defer(() -> quota.reserve(key.tenantId(), key.id(), requestId, estimateMicro)))
                .flatMap(result -> {
                    if (result == QuotaService.ReserveResult.INSUFFICIENT) {
                        metrics.quotaRejected();
                        return ErrorResponses.write(response,
                                new GatewayException(ErrorCode.INSUFFICIENT_QUOTA, "余额不足"), mapper);
                    }
                    // OK / DUPLICATE / DEGRADED 均放行：
                    // DEGRADED 表示配额服务不可用，按 FAIL_OPEN 策略放行并照常记账
                    var plan = accountRouting.plan(table, key, virtualModel, candidates, request, body);
                    return attempt(requestId, key, request, response, body, virtualModel, stream,
                            plan.candidates(), new UpstreamRelay.FirstByteFlag(), sessionKey, plan.session(), true, mapper.createArrayNode(), start);
                })
                .onErrorResume(e -> {
                    quota.release(key.tenantId(), requestId).onErrorResume(x -> Mono.empty()).subscribe();
                    if (response.isCommitted()) {
                        log.warn("响应已提交，无法再写错误体: {}", e.toString());
                        return Mono.empty();
                    }
                    return ErrorResponses.write(response, mapError(e), mapper);
                });
    }

    /**
     * 单次尝试。失败且可重试、且未写出首字节时，递归尝试下一个候选。
     *
     * @param sessionKey 会话键，null 表示无法识别（退化为加权轮询）
     * @param firstAttempt true 表示首次尝试（此时才查亲和；重试已换渠道，不应回粘性映射）
     */
    private Mono<Void> attempt(String requestId, ApiKeyView key,
                               ServerHttpRequest request, ServerHttpResponse response,
                               byte[] body, String virtualModel, boolean stream,
                               List<RouteTable.Candidate> remaining,
                               UpstreamRelay.FirstByteFlag firstByte,
                               String sessionKey, AccountRouting.Session accountSession, boolean firstAttempt, ArrayNode trace, long start) {

        boolean ordered = key.accountGroup() != null;
        RouteResolver.Decision decision;
        List<RouteTable.Candidate> rest;
        try {
            java.util.Optional<Long> stickyChannel = java.util.Optional.empty();
            if (!ordered && firstAttempt && sessionKey != null) {
                // 同步读本地缓存（命中时零网络往返），未命中退化为轮询
                Long bound = stickyStore.peekLocal(key.tenantId(), virtualModel, sessionKey);
                if (bound != null) {
                    stickyChannel = java.util.Optional.of(bound);
                }
            }
            decision = ordered ? resolver.resolveOrdered(virtualModel, remaining)
                    : resolver.resolve(virtualModel, remaining, Protocol.ANTHROPIC, stickyChannel);
            rest = new ArrayList<>(remaining);
            rest.remove(decision.selected());
        } catch (GatewayException e) {
            return Mono.error(e);
        }

        var channel = decision.selected().channel();
        String upstreamModel = decision.selected().route().upstreamModel();
        Long accountId = decision.selected().route().accountId();
        boolean managed = channel.protocol() == Protocol.ANTHROPIC && accountId != null;
        TailUsageSniffer meter = new TailUsageSniffer(mapper);

        Mono<Void> pipeline;
        if (channel.protocol() == Protocol.GEMINI) {
            // 慢车道：Anthropic 入口 → Gemini 出口
            CanonicalRequest ir;
            try {
                ir = parser.parse(body);
            } catch (GatewayException e) {
                return Mono.error(e);
            }
            byte[] upstreamBody = geminiBuilder.build(ir, upstreamModel);
            String path = GeminiRequestBuilder.path(upstreamModel, stream, channel.apiKeyQueryStyle());

            if (stream) {
                pipeline = relay.relayTranslated(request, response, channel, path, upstreamBody,
                        true, firstByte, meter, raw -> sseBridge.geminiToAnthropic(raw, meter));
            } else {
                pipeline = relay.relayTranslatedNonStream(request, response, channel, path, upstreamBody,
                        firstByte, meter,
                        raw -> GeminiToAnthropicNonStream.convert(mapper, raw, ir.requestedModel()));
            }
        } else if (channel.protocol() == Protocol.OPENAI_CHAT) {
            // 慢车道：Anthropic 入口 → OpenAI 出口
            CanonicalRequest ir;
            try {
                ir = parser.parse(body);
            } catch (GatewayException e) {
                return Mono.error(e);
            }
            byte[] upstreamBody = openAiBuilder.build(ir, upstreamModel);
            String path = "/v1/chat/completions";

            if (stream) {
                pipeline = relay.relayTranslated(request, response, channel, path, upstreamBody,
                        true, firstByte, meter, raw -> sseBridge.openAiToAnthropic(raw, meter));
            } else {
                pipeline = relay.relayTranslatedNonStream(request, response, channel, path, upstreamBody,
                        firstByte, meter,
                        raw -> OpenAiToAnthropicNonStream.convert(mapper, raw, ir.requestedModel()));
            }
        } else {
            // 快车道：同协议透传
            byte[] rewritten = rewriter.rewriteModel(body, upstreamModel);
            pipeline = managed ? relayAccount(request, response, channel, accountId, upstreamModel, rewritten, stream, firstByte, meter)
                    : relay.relay(request, response, channel, "/v1/messages", rewritten, stream, firstByte, meter);
        }

        var step = trace.addObject().put("channel", channel.name());
        if (accountId != null) step.put("accountId", accountId);
        if (decision.selected().groupId() != null) step.put("groupId", decision.selected().groupId());
        return pipeline.then(Mono.<Void>defer(() -> "failed".equals(meter.responseStatus())
                        ? Mono.error(new UpstreamRelay.UpstreamError(502, "上游流未正常结束")) : Mono.empty()))
                .doOnSuccess(v -> {
                    breaker.recordSuccess(channel.id(), upstreamModel);
                    dynamicWeight.recordSuccess(channel.id(), upstreamModel);
                    accountRouting.remember(accountSession, accountId);
                    if (decision.stickyHit()) {
                        metrics.stickyHit();
                    }
                    // 成功后才绑定亲和：失败/熔断的渠道不应被钉住
                    if (!ordered && sessionKey != null) {
                        stickyStore.bind(key.tenantId(), virtualModel, sessionKey, channel.id())
                                .subscribe(
                                        null,
                                        e -> log.debug("sticky bind 失败: {}", e.toString()));
                    }
                    step.put("status", 200);
                    settle(requestId, key, meter, start, channel.name(), virtualModel, decision, body,
                            request.getPath().value(), stream, trace);
                })
                .onErrorResume(e -> {
                    int failStatus = e instanceof UpstreamRelay.UpstreamError upstream ? upstream.status() : mapError(e).errorCode().httpStatus();
                    step.put("status", failStatus);
                    if (firstByte.isSet() || response.isCommitted()) {
                        log.warn("request={} 已写出首字节后失败，终止不重试: {}", requestId, e.toString());
                        breaker.recordFailure(channel.id(), upstreamModel);
                        dynamicWeight.recordFailure(channel.id(), upstreamModel, true);
                        publishUsage(requestId, key, meter, start, virtualModel, decision, body,
                                request.getPath().value(), stream, trace, failStatus, 0, null);
                        quota.release(key.tenantId(), requestId).onErrorResume(error -> Mono.empty()).subscribe();
                        return Mono.empty();
                    }
                    boolean retryable = e instanceof UpstreamRelay.UpstreamError upstream && upstream.retryable();
                    if (ordered && managed) {
                        retryable |= AccountRouting.accountRejected(failStatus)
                                || e instanceof GatewayException unavailable && unavailable.errorCode() == ErrorCode.UPSTREAM_UNAVAILABLE;
                    }
                    // 429 属于限流而非渠道故障，只轻微惩罚；5xx / 连接失败为严重失败
                    dynamicWeight.recordFailure(channel.id(), upstreamModel, failStatus == 0 || failStatus >= 500);
                    breaker.recordFailure(channel.id(), upstreamModel);
                    if (!retryable || rest.isEmpty()) {
                        publishUsage(requestId, key, meter, start, virtualModel, decision, body,
                                request.getPath().value(), stream, trace, failStatus, 0, null);
                        return Mono.error(e);
                    }
                    log.warn("request={} channel={} 失败，切换下一候选（剩余 {}）",
                            requestId, channel.name(), rest.size());
                    return attempt(requestId, key, request, response, body, virtualModel, stream,
                            rest, firstByte, sessionKey, accountSession, false, trace, start);
                })
                .doFinally(sig -> resolver.release(decision));
    }

    private Mono<Void> relayAccount(ServerHttpRequest request, ServerHttpResponse response, Channel channel,
                                    long accountId, String model, byte[] body, boolean stream,
                                    UpstreamRelay.FirstByteFlag firstByte, TailUsageSniffer meter) {
        return Mono.fromCallable(() -> accounts.acquireForAccount(AccountPool.ANTHROPIC, accountId, model, Set.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(lease -> {
                    var authenticated = new Channel(channel.id(), channel.name(), channel.protocol(), channel.baseUrl(),
                            lease.credential().path("api_key").asText(), channel.priority(), channel.weight(), channel.maxInflight(),
                            channel.timeout(), channel.passthrough(), channel.enabled(), false, lease.proxy());
                    return Mono.defer(() -> "oauth".equals(lease.realm())
                                    ? relay.relay(request, response, authenticated, "/v1/messages", body, stream, firstByte, meter,
                                            AccountAuthorization.claudeOAuthHeaders(lease.credential()))
                                    : relay.relay(request, response, authenticated, "/v1/messages", body, stream, firstByte, meter))
                            .then(Mono.defer(() -> recordAccount(lease, model, "failed".equals(meter.responseStatus()) ? 502 : 200, 0, meter)))
                            .onErrorResume(UpstreamRelay.UpstreamError.class, error ->
                                    recordAccount(lease, model, error.status(), error.retryAt(Instant.now().getEpochSecond()), meter)
                                            .then(Mono.error(error)))
                            .doFinally(signal -> lease.close());
                })
                .doOnDiscard(AccountPool.Lease.class, AccountPool.Lease::close);
    }

    private Mono<Void> recordAccount(AccountPool.Lease lease, String model, int status, long retryAt, TailUsageSniffer meter) {
        return Mono.<Void>fromRunnable(() -> accounts.result(lease, model, status, meter.inputTokens(), meter.outputTokens(), false, retryAt))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Failed to record Claude account result for account {}", lease.id(), error);
                    return Mono.empty();
                });
    }

    private void settle(String requestId, ApiKeyView key, TailUsageSniffer meter,
                        long start, String channelName, String virtualModel,
                        RouteResolver.Decision decision, byte[] requestBody, String inboundEndpoint, boolean stream, ArrayNode trace) {
        int in = meter.inputTokens();
        int out = meter.outputTokens();
        int cached = meter.cachedTokens();
        long elapsed = System.currentTimeMillis() - start;

        // 按真实定价算成本（含渠道倍率、分组折扣；缓存 token 按 cacheRead 价）
        PriceRule rule = pricing.ruleFor(virtualModel);
        // 多模态用量从请求体提取（上游不返回图片张数/音频时长）
        CostCalculator.MultimodalUsage mm = multimodalExtractor.extract(requestBody);
        CostCalculator.Cost cost = costCalculator.calculate(rule, in, out, cached, mm);
        long actualMicro = cost.microUsd();

        quota.settle(key.tenantId(), requestId, actualMicro, meter.usageSource())
                .doOnSuccess(r -> log.info(
                        "settled requestId={} channel={} model={} in={} out={} costMicro={} src={} elapsed={}ms trace={}",
                        requestId, channelName, virtualModel, in, out, actualMicro, meter.usageSource(),
                        elapsed, decision.toTrace()))
                .doOnError(e -> log.error("settle failed requestId={}", requestId, e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();

        metrics.recordTokens(in, out);
        metrics.recordAddedLatency(java.time.Duration.ofMillis(elapsed));

        publishUsage(requestId, key, meter, start, virtualModel, decision, requestBody, inboundEndpoint,
                stream, trace, 200, actualMicro, cost.accountMicroUsd());
    }

    private void publishUsage(String requestId, ApiKeyView key, TailUsageSniffer meter, long start, String model,
                              RouteResolver.Decision decision, byte[] body, String inboundEndpoint, boolean stream,
                              ArrayNode trace, int status, long cost, Long accountCost) {
        var candidate = decision.selected();
        var channel = candidate.channel();
        String effort = null;
        try { effort = UsageEvent.reasoningEffort(mapper.readTree(body)); }
        catch (java.io.IOException ignored) { }
        var event = new UsageEvent(requestId, key.tenantId(), key.id(), model,
                candidate.route().upstreamModel(), channel.id(), channel.name(),
                "ANTHROPIC", channel.protocol().name(), stream, status, status >= 400 ? "upstream_error" : null,
                meter.inputTokens(), meter.outputTokens(), meter.cachedTokens(), meter.reasoningTokens(), meter.usageSource(), cost,
                stream ? meter.ttftMs(start) : 0, (int) (System.currentTimeMillis() - start), Math.min(127, trace.size() - 1),
                decision.stickyHit(), trace.toString(), System.currentTimeMillis(), candidate.route().accountId(),
                candidate.groupId() == null ? key.groupId() : candidate.groupId(), inboundEndpoint, meter.upstreamEndpoint(),
                effort, "METERED", meter.cacheCreationTokens(), meter.uncachedInputTokens(), accountCost);
        Mono.fromRunnable(() -> usagePublisher.publish(event, key.managed()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
    }

    private static GatewayException mapError(Throwable e) {
        if (e instanceof GatewayException ge) {
            return ge;
        }
        if (e instanceof UpstreamRelay.UpstreamError ue) {
            return GatewayException.upstream(ue.status(), ue.body());
        }
        return new GatewayException(ErrorCode.INTERNAL_ERROR, e.getMessage());
    }
}






