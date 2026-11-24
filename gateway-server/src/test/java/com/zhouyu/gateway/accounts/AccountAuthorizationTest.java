package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAuthorizationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void exchangesOnlyMatchingCallbackOnceAndReturnsTheOriginalRecordId() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        var requests = new ArrayList<HttpRequest>();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return response;
        });
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"access_token":"fake-access","refresh_token":"fake-refresh","expires_in":3600,
                 "chatgpt_account_id":"fake-workspace","chatgpt_user_id":"fake-user"}
                """, """
                {"access_token":"new-access","expires_in":3600}
                """);
        var authorization = new AccountAuthorization(mapper, client);
        var pending = authorization.startCodex(42L);
        String state = pending.get("state");
        assertThat(pending.get("url")).startsWith("https://auth.openai.com/oauth/authorize?")
                .contains("code_challenge_method=S256", "codex_cli_simplified_flow=true");
        String callback = "http://localhost:1455/auth/callback?code=synthetic&state=" + state;
        assertThatThrownBy(() -> authorization.finishCodex("another-state", callback)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> authorization.finishCodex(state, callback.replace("localhost", "invalid.example")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(requests).isEmpty();
        var result = authorization.finishCodex(state, callback);
        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.registration().credential().path("chatgpt_account_id").asText()).isEqualTo("fake-workspace");
        assertThatThrownBy(() -> authorization.finishCodex(state, callback)).isInstanceOf(ResponseStatusException.class);
        assertThat(requests).hasSize(1);
        var refreshed = authorization.refresh(AccountPool.CODEX, "chatgpt", result.registration().credential());
        assertThat(refreshed.path("access_token").asText()).isEqualTo("new-access");
        assertThat(refreshed.path("refresh_token").asText()).isEqualTo("fake-refresh");
        assertThat(requests).allSatisfy(request -> assertThat(request.uri()).isEqualTo(URI.create("https://auth.openai.com/oauth/token")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsWorkBuddyCreditsFromWrappedAndLegacyResponses() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"code":0,"data":{"Response":{"Data":{"Accounts":[
                  {"CycleCapacitySize":100,"CycleCapacityRemain":37,"CapacityRemain":500},
                  {"CapacityRemain":5}
                ]}}}}
                """, """
                {"Response":{"Data":{"Accounts":[]}}}
                """);
        var authorization = new AccountAuthorization(mapper, client);
        var credential = mapper.createObjectNode().put("access_token", "fixture");
        assertThat(authorization.credits("cn", credential, null)).isEqualTo(42L);
        assertThat(authorization.credits("global", credential, null)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestsCodexModelCatalogAndExcludesHiddenModels() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.uri().getQuery()).isEqualTo("client_version=0.157.0");
            return response;
        });
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"models":[{"slug":"gpt-6-luna","visibility":"list"},
                           {"slug":"codex-auto-review","visibility":"hide"}]}
                """);
        var authorization = new AccountAuthorization(mapper, client);
        var credential = mapper.createObjectNode().put("access_token", "fixture").put("codex_oauth", true);
        assertThat(authorization.models(AccountPool.CODEX, "chatgpt", credential, null))
                .isEqualTo(mapper.createArrayNode().add("gpt-6-luna"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesSubscriptionAndIdentifiesQuotaWindowsByDuration() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> subscription = mock(HttpResponse.class);
        HttpResponse<String> usage = mock(HttpResponse.class);
        when(subscription.statusCode()).thenReturn(200);
        when(subscription.body()).thenReturn("""
                {"plan_type":"plus","active_since":"2026-11-01T00:00:00Z","active_until":"2026-12-01T00:00:00Z"}
                """);
        when(usage.statusCode()).thenReturn(200);
        when(usage.body()).thenReturn("""
                {"plan_type":"plus","rate_limit":{
                  "primary_window":{"limit_window_seconds":604800,"used_percent":100,"reset_at":1795650000},
                  "secondary_window":{"limit_window_seconds":18000,"used_percent":17.5,"reset_after_seconds":3600}
                }}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.headers().firstValue("ChatGPT-Account-Id")).contains("fixture-account");
            assertThat(request.method()).isEqualTo("GET");
            return switch (request.uri().toString()) {
                case "https://chatgpt.com/backend-api/subscriptions?account_id=fixture-account" -> subscription;
                case "https://chatgpt.com/backend-api/wham/usage" -> usage;
                case "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits" -> subscription;
                default -> throw new AssertionError(request.uri());
            };
        });
        var token = mapper.createObjectNode().put("codex_oauth", true).put("access_token", "fixture-token")
                .put("chatgpt_account_id", "fixture-account");
        var authorization = new AccountAuthorization(mapper, client);
        var status = authorization.codexStatus(token, null);
        assertThat(status.planType()).isEqualTo("plus");
        assertThat(status.subscriptionStartedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z").getEpochSecond());
        assertThat(status.subscriptionExpiresAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z").getEpochSecond());
        assertThat(status.windows()).containsExactly(
                new CodexAccountStatus.Window(18000, 17.5, status.checkedAt() + 3600),
                new CodexAccountStatus.Window(604800, 100.0, 1795650000L));

        when(subscription.statusCode()).thenReturn(404);
        when(usage.body()).thenReturn("""
                {"plan_type":"free","rate_limit":{
                  "primary_window":null,
                  "secondary_window":{"limit_window_seconds":604800,"used_percent":0,"reset_at":1795650000}
                }}
                """);
        var weekly = authorization.codexStatus(token, null);
        assertThat(weekly.planType()).isEqualTo("free");
        assertThat(weekly.subscriptionError()).isNull();
        assertThat(weekly.windows()).containsExactly(new CodexAccountStatus.Window(604800, 0.0, 1795650000L));

        when(usage.body()).thenReturn("""
                {"plan_type":"free","rate_limit":{"primary_window":null,"secondary_window":null}}
                """);
        assertThat(authorization.codexStatus(token, null).windows()).isEmpty();

        when(subscription.statusCode()).thenReturn(503);
        when(usage.body()).thenReturn("""
                {"plan_type":"free","rate_limit":{"primary_window":{"limit_window_seconds":3600,"used_percent":0}}}
                """);
        var partial = authorization.codexStatus(token, null);
        assertThat(partial.planType()).isEqualTo("free");
        assertThat(partial.subscriptionError()).contains("503");
        assertThat(partial.subscriptionStartedAt()).isNull();
        assertThat(partial.subscriptionExpiresAt()).isNull();
        assertThat(partial.windows()).containsExactly(new CodexAccountStatus.Window(3600, 0.0, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void consumesOnlyTheSelectedAvailableCodexResetCredit() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> details = mock(HttpResponse.class);
        HttpResponse<String> result = mock(HttpResponse.class);
        when(details.statusCode()).thenReturn(200);
        when(details.body()).thenReturn("""
                {"available_count":2,"credits":[
                  {"id":"first","status":"available","expires_at":"2099-01-01T00:00:00Z"},
                  {"id":"selected","status":"available","expires_at":"2099-02-01T00:00:00Z"},
                  {"id":"expired","status":"available","expires_at":"2001-01-01T00:00:00Z"},
                  {"id":"redeemed","status":"redeemed","expires_at":"2099-02-01T00:00:00Z"}
                ]}
                """);
        when(result.statusCode()).thenReturn(200);
        when(result.body()).thenReturn("""
                {"windows_reset":1}
                """);
        var requests = new ArrayList<HttpRequest>();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(request);
            assertThat(request.headers().firstValue("ChatGPT-Account-Id")).contains("fixture-account");
            return request.method().equals("GET") ? details : result;
        });
        var authorization = new AccountAuthorization(mapper, client);
        var token = mapper.createObjectNode().put("codex_oauth", true).put("access_token", "fixture-token")
                .put("chatgpt_account_id", "fixture-account");
        for (String unavailable : new String[]{"expired", "redeemed", "other-account"}) {
            assertThatThrownBy(() -> authorization.resetCodexQuota(token, null, unavailable))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        }
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getPath()).isEqualTo("/backend-api/wham/rate-limit-reset-credits");
        });
        assertThat(authorization.resetCodexQuota(token, null, "selected")).isEqualTo(1);
        var posts = requests.stream().filter(req -> req.method().equals("POST")).toList();
        assertThat(posts).hasSize(1);
        var post = posts.get(0);
        assertThat(post.uri().getPath()).isEqualTo("/backend-api/wham/rate-limit-reset-credits/consume");
        String body = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(post.bodyPublisher().orElseThrow())
                .map(java.nio.charset.StandardCharsets.UTF_8::decode).map(Object::toString).reduce("", String::concat).block();
        var payload = mapper.readTree(body);
        assertThat(payload.path("credit_id").asText()).isEqualTo("selected");
        assertThat(payload.path("redeem_request_id").asText()).isNotBlank();
    }

    @Test
    void decodesSub2ApiEnvelopeAndIsoTimestamp() throws Exception {
        var data = mapper.readTree("""
                {"data":{"type":"sub2api-data","version":1,"accounts":[
                  {"name":"Fixture","platform":"openai","type":"oauth","credentials":{
                    "access_token":"fixture-access","refresh_token":"fixture-refresh","expires_at":"2099-01-02T03:04:05Z",
                    "chatgpt_account_id":"fixture-account","chatgpt_user_id":"fixture-user"
                  }},
                  {"platform":"anthropic","type":"oauth"}
                ]}}
                """);
        var entries = Sub2ApiImport.entries(data);
        var account = Sub2ApiImport.decode(mapper, entries.get(0));
        assertThat(account.expiresAt()).isEqualTo(Instant.parse("2099-01-02T03:04:05Z").getEpochSecond());
        assertThat(account.externalId()).isEqualTo(Sub2ApiImport.decode(mapper, entries.get(0)).externalId());
        assertThat(account.credential().path("codex_oauth").asBoolean()).isTrue();
        assertThat(Sub2ApiImport.supports(entries.get(1))).isFalse();
    }

    @Test
    void decodesCockpitOAuthExportAndSkipsUnsupportedCredentialTypes() throws Exception {
        var data = mapper.readTree("""
                [{"type":"codex","email":"fixture@example.invalid","account_name":"Fixture Cockpit",
                  "account_id":"fixture-account","access_token":"fixture-access","refresh_token":"fixture-refresh",
                  "expired":"2099-01-02T03:04:05Z","account_password":"fixture-password"},
                 {"auth_mode":"apikey","OPENAI_API_KEY":"fixture-key"},
                 {"type":"codex","auth_mode":"agentIdentity","agent_identity":{}}]
                """);
        assertThat(CockpitImport.isFormat(data)).isTrue();
        var entries = CockpitImport.entries(mapper, data);
        assertThat(CockpitImport.supports(entries.get(0))).isTrue();
        assertThat(CockpitImport.supports(entries.get(1))).isFalse();
        assertThat(CockpitImport.supports(entries.get(2))).isFalse();
        var account = CockpitImport.decode(mapper, entries.get(0));
        assertThat(account.name()).isEqualTo("Fixture Cockpit");
        assertThat(account.expiresAt()).isEqualTo(Instant.parse("2099-01-02T03:04:05Z").getEpochSecond());
        assertThat(account.credential().path("refresh_token").asText()).isEqualTo("fixture-refresh");
        assertThat(account.credential().has("account_password")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exchangesClaudeCodeOnceAndRefreshesItsBearerToken() throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.uri()).isEqualTo(URI.create("https://platform.claude.com/v1/oauth/token"));
            assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
            return response;
        });
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"access_token":"claude-fixture","refresh_token":"refresh-fixture","expires_in":3600,
                 "account":{"uuid":"test-account","email_address":"claude@example.invalid"},"organization":{"uuid":"test-org"}}
                """, """
                {"access_token":"claude-refreshed","expires_in":3600}
                """);
        var authorization = new AccountAuthorization(mapper, http);
        var login = authorization.startClaude(null, 3L, null);
        assertThat(login.get("url")).startsWith("https://claude.com/cai/oauth/authorize?").contains("code_challenge_method=S256");
        String state = login.get("state");
        assertThatThrownBy(() -> authorization.finishClaude(state, "fixture-code#wrong")).isInstanceOf(ResponseStatusException.class);
        var result = authorization.finishClaude(state, "fixture-code#" + state);
        assertThat(result.proxyId()).isEqualTo(3L);
        assertThat(result.registration().realm()).isEqualTo("oauth");
        assertThat(result.registration().name()).isEqualTo("claude@example.invalid");
        assertThatThrownBy(() -> authorization.finishClaude(state, "fixture-code#" + state)).isInstanceOf(ResponseStatusException.class);
        var refreshed = authorization.refresh(AccountPool.ANTHROPIC, "oauth", result.registration().credential(), null);
        assertThat(refreshed.path("access_token").asText()).isEqualTo("claude-refreshed");
        assertThat(refreshed.path("refresh_token").asText()).isEqualTo("refresh-fixture");
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void acceptsAnOlderCockpitRecordWithNestedTokens() throws Exception {
        var data = mapper.readTree("""
                {"auth_mode":"oauth","email":"fixture@example.invalid",
                 "tokens":{"account_id":"fixture-account","access_token":"fixture-access","refresh_token":"fixture-refresh"}}
                """);
        assertThat(CockpitImport.isFormat(data)).isTrue();
        var entries = CockpitImport.entries(mapper, data);
        assertThat(entries.size()).isEqualTo(1);
        var account = CockpitImport.decode(mapper, entries.get(0));
        assertThat(account.credential().path("chatgpt_account_id").asText()).isEqualTo("fixture-account");
        assertThat(account.credential().path("access_token").asText()).isEqualTo("fixture-access");
    }
}
