package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import com.zhouyu.gateway.domain.AccountProxy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.zhouyu.gateway.accounts.AccountPool.problem;

@Component
public class AccountAuthorization {
    private static final String OPENAI_AUTH = "https://auth.openai.com";
    private static final String CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String CLAUDE_CALLBACK = "https://platform.claude.com/oauth/code/callback";
    public static final String OPENAI_BASE = "https://api.openai.com";
    public static final String ANTHROPIC_BASE = "https://api.anthropic.com";
    public static final String CODEX_BASE = "https://chatgpt.com/backend-api/codex";
    private static final String CODEX_RESET_CREDITS = "/wham/rate-limit-reset-credits";
    private static final URI CODEX_CALLBACK = URI.create("http://localhost:1455/auth/callback");
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http;
    private final Cache<String, OAuthPending> codex = Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, OAuthPending> claude = Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, WorkPending> work = Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(10)).build();

    @org.springframework.beans.factory.annotation.Autowired
    public AccountAuthorization(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    AccountAuthorization(ObjectMapper mapper, HttpClient http) {
        this.mapper = mapper;
        this.http = http;
    }

    public Map<String, String> startCodex(Long id) { return startCodex(id, null, null); }

    public Map<String, String> startCodex(Long id, Long proxyId, AccountProxy proxy) {
        String state = randomToken(), verifier = randomToken();
        String challenge = pkceChallenge(verifier);
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", Sub2ApiImport.CLIENT_ID);
        params.put("response_type", "code");
        params.put("scope", "openid profile email offline_access");
        params.put("redirect_uri", CODEX_CALLBACK.toString());
        params.put("state", state);
        params.put("code_challenge_method", "S256");
        params.put("code_challenge", challenge);
        params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true");
        codex.put(state, new OAuthPending(verifier, id, proxyId, proxy));
        return Map.of("url", OPENAI_AUTH + "/oauth/authorize?" + form(params), "state", state);
    }

    private static String pkceChallenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    record OAuthResult(Long id, AccountPool.Registration registration, Long proxyId) {}

    OAuthResult finishCodex(String state, String callback) {
        Map<String, String> params = callbackParams(callback);
        if (state == null || !state.equals(params.get("state"))) {
            throw problem(HttpStatus.BAD_REQUEST, "回调与当前授权链接不匹配");
        }
        OAuthPending pending = codex.asMap().remove(state);
        String code = params.get("code");
        if (pending == null || params.containsKey("error") || code == null || code.isBlank()) {
            throw problem(HttpStatus.BAD_REQUEST, "授权取消、状态无效或已超时，请重新生成链接");
        }
        ObjectNode token = exchangeCodex(Map.of("grant_type", "authorization_code", "client_id", Sub2ApiImport.CLIENT_ID,
                "code", code, "code_verifier", pending.verifier(), "redirect_uri", CODEX_CALLBACK.toString()), pending.proxy());
        token.put("client_id", Sub2ApiImport.CLIENT_ID);
        return new OAuthResult(pending.accountId(), Sub2ApiImport.fromOAuth(mapper, token), pending.proxyId());
    }

    public Map<String, String> startClaude(Long id, Long proxyId, AccountProxy proxy) {
        String state = randomToken(), verifier = randomToken();
        var params = Map.of("code", "true", "client_id", CLAUDE_CLIENT_ID, "response_type", "code",
                "scope", "user:profile user:inference user:sessions:claude_code", "redirect_uri", CLAUDE_CALLBACK,
                "state", state, "code_challenge", pkceChallenge(verifier), "code_challenge_method", "S256");
        claude.put(state, new OAuthPending(verifier, id, proxyId, proxy));
        return Map.of("url", "https://claude.com/cai/oauth/authorize?" + form(params), "state", state);
    }

    OAuthResult finishClaude(String state, String callback) {
        String[] parts = (callback == null ? "" : callback.strip()).split("#", -1);
        if (state == null || parts.length > 2 || parts[0].isBlank() || parts[0].length() > 8192
                || (parts.length == 2 && !state.equals(parts[1]))) {
            throw problem(HttpStatus.BAD_REQUEST, "请输入当前 Claude 授权页显示的验证码");
        }
        var pending = claude.asMap().remove(state);
        if (pending == null) throw problem(HttpStatus.BAD_REQUEST, "授权链接已失效，请重新生成");
        var response = exchangeClaude(mapper.createObjectNode().put("grant_type", "authorization_code")
                .put("code", parts[0]).put("code_verifier", pending.verifier()).put("state", state)
                .put("redirect_uri", CLAUDE_CALLBACK), pending.proxy());
        var identity = response.path("account");
        String email = identity.path("email_address").asText();
        String user = identity.path("uuid").asText(email);
        if (user.isBlank()) throw problem(HttpStatus.BAD_GATEWAY, "Claude 授权未返回账号标识");
        String id = "claude:" + com.zhouyu.gateway.routing.RouteTable.sha256(user + "\n" + response.path("organization").path("uuid").asText());
        var token = claudeTokens(mapper.createObjectNode(), response);
        return new OAuthResult(pending.accountId(), new AccountPool.Registration(AccountPool.ANTHROPIC, id,
                email.isBlank() ? "Claude 账号" : email, "oauth", token.path("expires_at").asLong(), token), pending.proxyId());
    }

    private JsonNode exchangeClaude(ObjectNode body, AccountProxy proxy) {
        body.put("client_id", CLAUDE_CLIENT_ID);
        var response = call(request("https://platform.claude.com/v1/oauth/token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), proxy);
        if (response.path("access_token").asText().isBlank() || response.path("expires_in").asLong() <= 0) {
            throw problem(HttpStatus.BAD_GATEWAY, "Claude 授权返回了无效的凭证");
        }
        return response;
    }

    private static ObjectNode claudeTokens(ObjectNode previous, JsonNode response) {
        var token = previous.deepCopy().put("access_token", response.path("access_token").asText())
                .put("expires_at", Instant.now().getEpochSecond() + response.path("expires_in").asLong());
        if (!response.path("refresh_token").asText().isBlank()) token.set("refresh_token", response.path("refresh_token"));
        return token;
    }

    public static Map<String, String> claudeOAuthHeaders(ObjectNode token) {
        return Map.of("Authorization", "Bearer " + token.path("access_token").asText(), "anthropic-beta", "oauth-2025-04-20");
    }

    private static Map<String, String> callbackParams(String callback) {
        try {
            if (callback == null || callback.length() > 8192) throw new IllegalArgumentException();
            URI uri = URI.create(callback.trim());
            if (!CODEX_CALLBACK.getScheme().equals(uri.getScheme()) || !CODEX_CALLBACK.getAuthority().equals(uri.getRawAuthority())
                    || !CODEX_CALLBACK.getPath().equals(uri.getRawPath()) || uri.getFragment() != null || uri.getRawQuery() == null) {
                throw new IllegalArgumentException();
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (String param : uri.getRawQuery().split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length != 2 || params.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
            }
            return params;
        } catch (IllegalArgumentException e) {
            throw problem(HttpStatus.BAD_REQUEST, "请粘贴浏览器地址栏中的完整 localhost:1455 授权回调地址");
        }
    }

    public static void codexIdentity(JsonNode token, java.util.function.BiConsumer<String, String> header) {
        String accountId = token.path("chatgpt_account_id").asText();
        if (!accountId.isBlank()) header.accept("ChatGPT-Account-Id", accountId);
        header.accept("originator", "codex_cli_rs");
    }

    static void requireCodex(JsonNode token) {
        if (!token.path("codex_oauth").asBoolean()) {
            throw problem(HttpStatus.UNAUTHORIZED, "旧版 ChatGPT 授权需要在账号池重新授权");
        }
    }

    public Map<String, String> startWork(String realm, Long proxyId, AccountProxy proxy) {
        if (!Set.of("cn", "global").contains(realm)) throw problem(HttpStatus.BAD_REQUEST, "请选择站点");
        JsonNode data = workData(call(workRequest(realm, "/v2/plugin/auth/state?platform=CLI")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), proxy));
        String url = data.path("authUrl").asText();
        String state = data.path("state").asText();
        if (state.isBlank() || !url.startsWith("https://")) throw problem(HttpStatus.BAD_GATEWAY, "WorkBuddy 未返回登录链接");
        String ticket = randomToken();
        work.put(ticket, new WorkPending(state, realm, proxyId, proxy));
        return Map.of("ticket", ticket, "url", url);
    }

    record WorkResult(AccountPool.Registration registration, Long proxyId) {}

    public synchronized WorkResult finishWork(String ticket) {
        var pending = ticket == null ? null : work.getIfPresent(ticket);
        if (pending == null) throw problem(HttpStatus.BAD_REQUEST, "登录请求无效或已超时");
        String query = "?state=" + quote(pending.state());
        JsonNode response = call(workRequest(pending.realm(), "/v2/plugin/auth/token" + query).GET().build(), pending.proxy());
        JsonNode token = response.path("data");
        String access = token.path("accessToken").asText();
        if (response.path("code").asInt(-1) != 0 || access.isBlank()) throw problem(HttpStatus.CONFLICT, "请先在浏览器完成登录");
        JsonNode profile = workData(call(workRequest(pending.realm(), "/v2/plugin/login/account" + query)
                .header("Authorization", "Bearer " + access).GET().build(), pending.proxy()));
        String uid = profile.path("uid").asText();
        if (uid.isBlank()) throw problem(HttpStatus.BAD_GATEWAY, "没有取得 WorkBuddy 账号身份");
        ObjectNode credential = mapper.createObjectNode().put("access_token", access).put("uid", uid)
                .put("refresh_token", token.path("refreshToken").asText()).put("domain", token.path("domain").asText())
                .put("enterpriseId", profile.path("enterpriseId").asText());
        work.invalidate(ticket);
        return new WorkResult(new AccountPool.Registration(AccountPool.WORKBUDDY, pending.realm() + ":" + uid,
                profile.path("nickname").asText(uid), pending.realm(),
                Instant.now().getEpochSecond() + token.path("expiresIn").asLong(3600), credential), pending.proxyId());
    }

    ObjectNode refresh(String provider, String realm, ObjectNode old) { return refresh(provider, realm, old, null); }

    ObjectNode refresh(String provider, String realm, ObjectNode old, AccountProxy proxy) {
        String refresh = old.path("refresh_token").asText();
        if (refresh.isBlank()) throw problem(HttpStatus.UNAUTHORIZED, "请重新授权账号");
        if (provider.equals(AccountPool.CODEX)) {
            requireCodex(old);
            ObjectNode latest = exchangeCodex(Map.of("grant_type", "refresh_token", "client_id", old.path("client_id").asText(),
                    "refresh_token", refresh, "scope", "openid profile email"), proxy);
            if (latest.path("refresh_token").asText().isBlank()) latest.remove("refresh_token");
            return old.deepCopy().setAll(latest);
        }
        if (provider.equals(AccountPool.ANTHROPIC)) {
            return claudeTokens(old, exchangeClaude(mapper.createObjectNode().put("grant_type", "refresh_token")
                    .put("refresh_token", refresh), proxy));
        }
        var request = workRequest(realm, "/v2/plugin/auth/token/refresh").header("X-Refresh-Token", refresh)
                .header("X-Auth-Refresh-Source", "plugin");
        workIdentity(old, request::header);
        JsonNode latest = workData(call(request.POST(HttpRequest.BodyPublishers.noBody()).build(), proxy));
        if (latest.path("accessToken").asText().isBlank()) throw problem(HttpStatus.UNAUTHORIZED, "WorkBuddy 授权失效");
        ObjectNode out = old.deepCopy().put("access_token", latest.path("accessToken").asText())
                .put("expires_at", Instant.now().getEpochSecond() + latest.path("expiresIn").asLong(3600));
        if (latest.hasNonNull("refreshToken")) out.set("refresh_token", latest.get("refreshToken"));
        if (latest.hasNonNull("domain")) out.set("domain", latest.get("domain"));
        return out;
    }

    CodexAccountStatus codexStatus(ObjectNode token, AccountProxy proxy) {
        requireCodex(token);
        String accountId = token.path("chatgpt_account_id").asText("");
        JsonNode subscription = mapper.missingNode();
        JsonNode usage = mapper.missingNode();
        String subscriptionError = null;
        String usageError = null;
        if (accountId.isBlank()) {
            subscriptionError = "授权中缺少 ChatGPT 账号标识";
        } else {
            try {
                subscription = codexStatusRequest("/subscriptions?account_id=" + quote(accountId), token, proxy, true);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                subscriptionError = e.getReason();
            }
        }
        try {
            usage = codexUsage(token, proxy);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            usageError = e.getReason();
        }
        int count = usage.path("rate_limit_reset_credits").path("available_count").asInt(-1);
        var resetCredits = readCodexResetCredits(token, proxy, count);
        return CodexAccountStatus.from(subscription, usage, resetCredits, subscriptionError, usageError, Instant.now().getEpochSecond());
    }

    private CodexAccountStatus.ResetCredits readCodexResetCredits(ObjectNode token, AccountProxy proxy, int count) {
        JsonNode details = mapper.missingNode();
        String error = null;
        if (count != 0) {
            try {
                details = codexStatusRequest(CODEX_RESET_CREDITS, token, proxy, true);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                error = e.getReason();
            }
        }
        return CodexAccountStatus.resetCredits(details, count, error, Instant.now().getEpochSecond());
    }

    int resetCodexQuota(ObjectNode token, AccountProxy proxy, String creditId) {
        requireCodex(token);
        if (creditId == null || creditId.isBlank()) throw problem(HttpStatus.BAD_REQUEST, "请选择要使用的重置券");
        if (token.path("chatgpt_account_id").asText("").isBlank()) {
            throw problem(HttpStatus.BAD_REQUEST, "授权中缺少 ChatGPT 账号标识");
        }
        var credits = readCodexResetCredits(token, proxy, -1);
        if (credits.error() != null) throw problem(HttpStatus.BAD_GATEWAY, credits.error());
        if (credits.options().stream().noneMatch(credit -> credit.id().equals(creditId))) {
            throw problem(HttpStatus.CONFLICT, "该重置券不可用，请刷新后重新选择");
        }
        var body = mapper.createObjectNode().put("credit_id", creditId)
                .put("redeem_request_id", java.util.UUID.randomUUID().toString());
        var request = codexAccountRequest(CODEX_RESET_CREDITS + "/consume", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        int windows = call(request, proxy).path("windows_reset").asInt(-1);
        if (windows < 0) throw problem(HttpStatus.BAD_GATEWAY, "上游未确认重置结果，请核对剩余次数和额度");
        return windows;
    }

    JsonNode codexUsage(ObjectNode token, AccountProxy proxy) {
        return codexStatusRequest("/wham/usage", token, proxy, false);
    }

    private JsonNode codexStatusRequest(String path, ObjectNode token, AccountProxy proxy, boolean allowNotFound) {
        return call(codexAccountRequest(path, token).GET().build(), proxy, allowNotFound);
    }

    private HttpRequest.Builder codexAccountRequest(String path, ObjectNode token) {
        var request = request("https://chatgpt.com/backend-api" + path)
                .header("Authorization", "Bearer " + token.path("access_token").asText())
                .header("Origin", "https://chatgpt.com").header("Referer", "https://chatgpt.com/");
        codexIdentity(token, request::header);
        return request;
    }

    JsonNode models(String provider, String realm, ObjectNode token, AccountProxy proxy) {
        if (provider.equals(AccountPool.ANTHROPIC) || (provider.equals(AccountPool.CODEX) && realm.equals("api"))) {
            var request = provider.equals(AccountPool.CODEX)
                    ? request(OPENAI_BASE + "/v1/models").header("Authorization", "Bearer " + token.path("api_key").asText())
                    : request(ANTHROPIC_BASE + "/v1/models?limit=1000").header("anthropic-version", "2023-06-01");
            if (provider.equals(AccountPool.ANTHROPIC)) {
                if (realm.equals("oauth")) claudeOAuthHeaders(token).forEach(request::header);
                else request.header("x-api-key", token.path("api_key").asText());
            }
            JsonNode response = call(request.GET().build(), proxy);
            var output = mapper.createArrayNode();
            for (JsonNode model : response.path("data")) {
                if (model.hasNonNull("id")) output.add(model.path("id").asText());
            }
            return output;
        }
        String access = token.path("access_token").asText();
        if (provider.equals(AccountPool.CODEX)) {
            requireCodex(token);
            var request = request(CODEX_BASE + "/models?client_version=0.157.0").header("Authorization", "Bearer " + access);
            codexIdentity(token, request::header);
            JsonNode response = call(request.GET().build(), proxy);
            var output = mapper.createArrayNode();
            for (JsonNode model : response.path("models")) {
                if ("list".equals(model.path("visibility").asText("list")) && model.hasNonNull("slug")) output.add(model.path("slug").asText());
            }
            return output;
        }
        var result = new java.util.LinkedHashSet<String>();
        org.springframework.web.server.ResponseStatusException failure = null;
        String enterprise = realm.equals("global") ? "/v2/enterprises/personal/models" : "/console/enterprises/personal/models";
        for (String path : new String[]{enterprise, "/v3/config"}) {
            try {
                var request = workRequest(realm, path).header("Authorization", "Bearer " + access);
                workIdentity(token, request::header);
                JsonNode response = workData(call(request.GET().build(), proxy));
                result.addAll(workModelIds(response, path.equals(enterprise)));
            } catch (org.springframework.web.server.ResponseStatusException e) {
                if (failure == null) failure = e;
            }
        }
        if (result.isEmpty() && failure != null) throw failure;
        var output = mapper.createArrayNode();
        result.forEach(id -> output.add(realm + ":" + id));
        return output;
    }

    static Set<String> workModelIds(JsonNode response, boolean requireCli) {
        var cli = new java.util.HashSet<String>();
        if (requireCli) {
            for (JsonNode agent : response.path("agents")) {
                if (agent.path("name").asText().equals("cli")) {
                    for (JsonNode model : agent.path("models")) cli.add(model.asText());
                }
            }
        }
        var result = new java.util.LinkedHashSet<String>();
        JsonNode models = response.isArray() ? response : response.path("models");
        for (JsonNode item : models) {
            String id = item.path("id").asText(item.path("modelId").asText(item.path("name").asText()));
            if (id.isBlank() || item.path("disabled").asBoolean() || (requireCli && !cli.contains(id))) continue;
            if (id.startsWith("nes-") || id.startsWith("completion-") || id.startsWith("codewise-")) continue;
            int maxOutput = item.path("maxOutputTokens").asInt();
            if (maxOutput > 0 && maxOutput <= 256) continue;
            boolean image = false;
            for (JsonNode tag : item.path("tags")) image |= "text-to-image".equals(tag.asText());
            if (!image) result.add(id);
        }
        return result;
    }

    Long credits(String realm, ObjectNode token, AccountProxy proxy) {
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        var date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        var query = mapper.createObjectNode().put("PageNumber", 1).put("PageSize", 100).put("ProductCode", "p_tcaca")
                .put("PackageEndTimeRangeBegin", date.format(now)).put("PackageEndTimeRangeEnd", date.format(now.plusYears(101)));
        query.putArray("Status").add(0).add(3);
        JsonNode result = billing(realm, token, "/billing/meter/get-user-resource", query.toString(), proxy);
        JsonNode accounts = workData(result).path("Response").path("Data").path("Accounts");
        if (!accounts.isArray()) return null;
        long total = 0;
        for (JsonNode pack : accounts) {
            total += Math.max(0, pack.path("CycleCapacitySize").asLong() > 0
                    ? pack.path("CycleCapacityRemain").asLong() : pack.path("CapacityRemain").asLong());
        }
        return total;
    }

    void checkIn(String realm, ObjectNode token, AccountProxy proxy) {
        JsonNode result = billing(realm, token, "/billing/meter/daily-checkin", "{}", proxy);
        if (result.path("Response").hasNonNull("Error")) throw problem(HttpStatus.BAD_GATEWAY, "WorkBuddy 签到未成功");
    }

    private JsonNode billing(String realm, ObjectNode token, String path, String body, AccountProxy proxy) {
        var request = request(workOrigin(realm) + (realm.equals("global") ? path : "/v2" + path))
                .header("Authorization", "Bearer " + token.path("access_token").asText())
                .header("Origin", workOrigin(realm)).header("Referer", workOrigin(realm) + "/").header("Content-Type", "application/json");
        workIdentity(token, request::header);
        return call(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), proxy);
    }

    void revoke(ObjectNode token, AccountProxy proxy) {
        String refresh = token.path("refresh_token").asText();
        if (refresh.isBlank()) return;
        call(request(OPENAI_AUTH + "/api/accounts/oauth/revoke").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", refresh, "token_type_hint", "refresh_token",
                        "client_id", token.path("client_id").asText())))).build(), proxy);
    }

    private ObjectNode exchangeCodex(Map<String, String> data, AccountProxy proxy) {
        var req = request(OPENAI_AUTH + "/oauth/token").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(data))).build();
        JsonNode payload = call(req, proxy);
        if (!(payload instanceof ObjectNode result) || result.path("access_token").asText().isBlank()) {
            throw problem(HttpStatus.BAD_GATEWAY, "授权服务未返回访问凭证");
        }
        return result.put("expires_at", Instant.now().getEpochSecond() + result.path("expires_in").asLong(3600));
    }

    public static String workBase(String realm) {
        return "global".equals(realm) ? "https://www.workbuddy.ai" : "https://copilot.tencent.com";
    }

    public static String workOrigin(String realm) { return "global".equals(realm) ? workBase(realm) : "https://www.codebuddy.cn"; }

    public static void workIdentity(JsonNode token, java.util.function.BiConsumer<String, String> header) {
        for (var field : Map.of("uid", "X-User-Id", "domain", "X-Domain", "enterpriseId", "X-Enterprise-Id").entrySet()) {
            String value = token.path(field.getKey()).asText();
            if (!value.isBlank() && !value.contains("\r") && !value.contains("\n")) header.accept(field.getValue(), value);
        }
    }

    private HttpRequest.Builder workRequest(String realm, String path) {
        return request(workBase(realm) + path).header("Origin", workOrigin(realm))
                .header("X-Requested-With", "XMLHttpRequest").header("X-CodeBuddy-Request", "1")
                .header("Referer", workOrigin(realm) + "/").header("Content-Type", "application/json");
    }

    private HttpRequest.Builder request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
    }

    private record AccountResponse(int status, String body) {}

    private AccountResponse send(HttpRequest req, AccountProxy proxy) throws java.io.IOException, InterruptedException {
        if (proxy == null) {
            var response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new AccountResponse(response.statusCode(), response.body());
        }
        var client = proxy.apply(reactor.netty.http.client.HttpClient.newConnection()).headers(headers ->
                req.headers().map().forEach(headers::set));
        var request = client.request(io.netty.handler.codec.http.HttpMethod.valueOf(req.method())).uri(req.uri().toString());
        reactor.netty.http.client.HttpClient.ResponseReceiver<?> receiver = request;
        if (req.bodyPublisher().isPresent() && req.bodyPublisher().get().contentLength() != 0) {
            var data = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(req.bodyPublisher().get());
            receiver = request.send((ignored, outbound) -> outbound.send(data.map(io.netty.buffer.Unpooled::wrappedBuffer)));
        }
        try {
            return receiver.responseSingle((response, bytes) -> bytes.asString().defaultIfEmpty("")
                    .map(body -> new AccountResponse(response.status().code(), body)))
                    .block(req.timeout().orElse(Duration.ofSeconds(20)));
        } catch (RuntimeException error) {
            throw new java.io.IOException("Proxy request failed", error);
        }
    }

    private JsonNode call(HttpRequest req, AccountProxy proxy) {
        return call(req, proxy, false);
    }

    private JsonNode call(HttpRequest req, AccountProxy proxy, boolean allowNotFound) {
        try {
            var response = send(req, proxy);
            int status = response.status();
            String body = response.body();
            if (status == 404 && allowNotFound) return mapper.missingNode();
            if (status == 401 || status == 403 || (status == 400 && body.contains("invalid_grant"))) {
                throw problem(HttpStatus.UNAUTHORIZED, "上游授权失效，请重新登录");
            }
            if (status < 200 || status >= 300) throw problem(HttpStatus.BAD_GATEWAY, "上游授权或账号服务返回 HTTP " + status);
            return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (java.io.IOException e) { throw problem(HttpStatus.BAD_GATEWAY, "无法连接上游账号服务"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw problem(HttpStatus.SERVICE_UNAVAILABLE, "账号操作中断"); }
    }

    private static JsonNode workData(JsonNode envelope) {
        if (envelope.has("code") && envelope.path("code").asInt(-1) != 0) {
            throw problem(HttpStatus.BAD_GATEWAY, "WorkBuddy 账号接口拒绝请求");
        }
        return envelope.has("data") ? envelope.get("data") : envelope;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String form(Map<String, String> data) {
        return data.entrySet().stream().map(e -> quote(e.getKey()) + "=" + quote(e.getValue())).collect(Collectors.joining("&"));
    }
    private static String quote(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record OAuthPending(String verifier, Long accountId, Long proxyId, AccountProxy proxy) {}
    private record WorkPending(String state, String realm, Long proxyId, AccountProxy proxy) {}
}
