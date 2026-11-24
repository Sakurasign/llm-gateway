package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhouyu.gateway.config.GatewayConfigLoader;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.Callable;

@RestController
public class AccountController {
    private final AccountPool pool;
    private final AccountAuthorization auth;
    private final GatewayConfigLoader config;
    private final AccountProxies proxies;

    public AccountController(AccountPool pool, AccountAuthorization auth, GatewayConfigLoader config, AccountProxies proxies) {
        this.pool = pool;
        this.auth = auth;
        this.config = config;
        this.proxies = proxies;
    }

    @GetMapping("/admin/api/v1/proxies")
    public Mono<Map<String, Object>> proxies() { return run(() -> Map.of("data", proxies.list())); }

    @PostMapping("/admin/api/v1/proxies")
    public Mono<Map<String, Long>> createProxy(@RequestBody AccountProxies.Input input) {
        return run(() -> Map.of("id", proxies.save(null, input)));
    }

    @PutMapping("/admin/api/v1/proxies/{id}")
    public Mono<Map<String, Long>> editProxy(@PathVariable long id, @RequestBody AccountProxies.Input input) {
        return run(() -> Map.of("id", proxies.save(id, input)));
    }

    @DeleteMapping("/admin/api/v1/proxies/{id}")
    public Mono<Map<String, Boolean>> deleteProxy(@PathVariable long id) {
        return run(() -> { proxies.delete(id); return Map.of("ok", true); });
    }

    @PostMapping("/admin/api/v1/proxies/{id}/test")
    public Mono<Map<String, Integer>> testProxy(@PathVariable long id) {
        return run(() -> Map.of("upstreamStatus", proxies.test(id)));
    }

    @GetMapping("/admin/api/v1/accounts")
    public Mono<Map<String, Object>> accounts() {
        return run(() -> Map.of("data", pool.summaries(), "bindings", pool.bindings(), "keyIds", pool.keyIds()));
    }

    public record ApiKeyInput(String name, String apiKey, Long proxyId) {}

    @PostMapping("/admin/api/v1/accounts/anthropic")
    public Mono<Map<String, Long>> createAnthropic(@RequestBody ApiKeyInput body) {
        return run(() -> Map.of("id", config.saveAuthorizedAccount(() -> {
            long id = pool.saveApiKey(AccountPool.ANTHROPIC, null, body.name(), body.apiKey());
            if (body.proxyId() != null) pool.bindProxy(id, body.proxyId());
            return id;
        })));
    }

    public record ApiKeyValue(String apiKey) {}

    @PutMapping("/admin/api/v1/accounts/{id}/anthropic-key")
    public Mono<Map<String, Long>> replaceAnthropicKey(@PathVariable long id, @RequestBody ApiKeyValue body) {
        return run(() -> Map.of("id", config.saveAuthorizedAccount(() -> pool.saveApiKey(AccountPool.ANTHROPIC, id, null, body.apiKey()))));
    }

    @PostMapping("/admin/api/v1/accounts/openai-key")
    public Mono<Map<String, Long>> createOpenAiKey(@RequestBody ApiKeyInput body) {
        return run(() -> Map.of("id", config.saveAuthorizedAccount(() -> {
            long id = pool.saveApiKey(AccountPool.CODEX, null, body.name(), body.apiKey());
            if (body.proxyId() != null) pool.bindProxy(id, body.proxyId());
            return id;
        })));
    }

    @PutMapping("/admin/api/v1/accounts/{id}/openai-key")
    public Mono<Map<String, Long>> replaceOpenAiKey(@PathVariable long id, @RequestBody ApiKeyValue body) {
        return run(() -> Map.of("id", config.saveAuthorizedAccount(() -> pool.saveApiKey(AccountPool.CODEX, id, null, body.apiKey()))));
    }

    @PostMapping("/admin/api/v1/accounts/anthropic/login")
    public Mono<Map<String, String>> claudeLogin(@RequestParam(required = false) Long id, @RequestParam(required = false) Long proxyId) {
        return run(() -> {
            if (id != null && (!AccountPool.ANTHROPIC.equals(pool.provider(id)) || !"oauth".equals(pool.realm(id)))) {
                throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择 Claude OAuth 账号");
            }
            Long selected = id == null ? proxyId : pool.proxyId(id);
            return auth.startClaude(id, selected, proxies.connection(selected));
        });
    }

    @PostMapping("/admin/api/v1/accounts/anthropic/confirm")
    public Mono<Map<String, Long>> claudeConfirm(@RequestBody OAuthCallback body) {
        return run(() -> {
            var result = auth.finishClaude(body.state(), body.callback());
            return Map.of("id", config.saveAuthorizedAccount(() -> {
                long saved = pool.saveClaudeOAuth(result.id(), result.registration());
                if (result.proxyId() != null) pool.bindProxy(saved, result.proxyId());
                return saved;
            }));
        });
    }

    @PostMapping("/admin/api/v1/accounts/workbuddy/import")
    public Mono<Map<String, Long>> importWorkBuddy(@RequestBody JsonNode body) {
        return run(() -> {
            var account = pool.workbuddyImport(body);
            return Map.of("id", config.saveAuthorizedAccount(() -> pool.save(account)));
        });
    }

    @PostMapping("/admin/api/v1/accounts/workbuddy/login")
    public Mono<Map<String, String>> workLogin(@RequestParam String realm, @RequestParam(required = false) Long id, @RequestParam(required = false) Long proxyId) {
        return run(() -> {
            Long selected = id == null ? proxyId : pool.proxyId(id);
            return auth.startWork(realm, selected, proxies.connection(selected));
        });
    }

    @PostMapping("/admin/api/v1/accounts/workbuddy/confirm")
    public Mono<Map<String, Long>> workConfirm(@RequestBody Map<String, String> body) {
        return run(() -> {
            var result = auth.finishWork(body.get("ticket"));
            return Map.of("id", config.saveAuthorizedAccount(() -> {
                long saved = pool.save(result.registration());
                if (result.proxyId() != null) pool.bindProxy(saved, result.proxyId());
                return saved;
            }));
        });
    }

    @PostMapping({"/admin/api/v1/accounts/codex/import", "/admin/api/v1/accounts/sub2api/import"})
    public Mono<AccountPool.ImportResult> importCodexAccounts(@RequestBody JsonNode body) {
        return run(() -> pool.importCodexAccounts(body, config));
    }

    @PostMapping("/admin/api/v1/accounts/codex/login")
    public Mono<Map<String, String>> codexLogin(@RequestParam(required = false) Long id, @RequestParam(required = false) Long proxyId) {
        return run(() -> {
            if (id != null && (!pool.provider(id).equals(AccountPool.CODEX) || "api".equals(pool.realm(id)))) {
                throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择 ChatGPT 订阅账号");
            }
            Long selected = id == null ? proxyId : pool.proxyId(id);
            return auth.startCodex(id, selected, proxies.connection(selected));
        });
    }

    public record OAuthCallback(String state, String callback) {}

    @PostMapping("/admin/api/v1/accounts/codex/confirm")
    public Mono<Map<String, Long>> codexConfirm(@RequestBody OAuthCallback body) {
        return run(() -> {
            var result = auth.finishCodex(body.state(), body.callback());
            return Map.of("id", config.saveAuthorizedAccount(() -> {
                long saved = pool.saveCodex(result.id(), result.registration());
                if (result.proxyId() != null) pool.bindProxy(saved, result.proxyId());
                return saved;
            }));
        });
    }

    public record PublishModel(String model, String upstreamModel, Long keyId) {}

    @PostMapping("/admin/api/v1/accounts/{id}/publish")
    public Mono<Map<String, Long>> publish(@PathVariable long id, @RequestBody PublishModel body, @RequestParam long version) {
        return run(() -> Map.of("version", config.publishAccountModel(pool, id, body.model(), body.upstreamModel(), body.keyId(), version)))
                .onErrorMap(DuplicateKeyException.class, e -> AccountPool.problem(HttpStatus.CONFLICT, "渠道或模型路由已存在"));
    }

    @PutMapping("/admin/api/v1/accounts/binding/{keyId}")
    public Mono<Map<String, Long>> bind(@PathVariable long keyId, @RequestParam long accountId) {
        return run(() -> { pool.bind(keyId, accountId); return Map.of("accountId", accountId); });
    }

    @PatchMapping("/admin/api/v1/accounts/{id}")
    public Mono<Map<String, Boolean>> status(@PathVariable long id, @RequestParam boolean enabled) {
        return run(() -> { config.changeAccountStatus(pool, id, enabled); return Map.of("enabled", enabled); });
    }

    @DeleteMapping("/admin/api/v1/accounts/{id}")
    public Mono<Map<String, Long>> remove(@PathVariable long id) {
        return run(() -> { config.deleteAccount(pool, id); return Map.of("deleted", id); });
    }

    @GetMapping("/admin/api/v1/accounts/{id}/settings")
    public Mono<Map<String, Object>> settings(@PathVariable long id) {
        return run(() -> {
            pool.provider(id);
            return config.accountSettings(id);
        });
    }

    public record AccountInput(String name, Boolean enabled, java.util.List<GatewayConfigLoader.ModelMapping> mappings,
                               GatewayConfigLoader.AccountOptions options) {}

    @PutMapping("/admin/api/v1/accounts/{id}")
    public Mono<Map<String, Long>> edit(@PathVariable long id, @RequestParam long version, @RequestBody AccountInput body) {
        return run(() -> {
            if (body.enabled() == null) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择账号状态");
            return Map.of("version", config.editAccount(pool, id, body.name(), body.enabled(), body.mappings(), body.options(), version));
        }).onErrorMap(DuplicateKeyException.class, e -> AccountPool.problem(HttpStatus.CONFLICT, "模型映射重复"));
    }

    public record AccountBulk(java.util.List<Long> ids, Boolean enabled, Boolean schedulable) {}
    public record KeyBulk(java.util.List<Long> ids, Boolean enabled, Long groupId) {}

    @PostMapping("/admin/api/v1/accounts/bulk")
    public Mono<Map<String, Boolean>> bulkAccounts(@RequestBody AccountBulk body) {
        return run(() -> { config.bulkAccountState(pool, body.ids(), body.enabled(), body.schedulable()); return Map.of("saved", true); });
    }

    @GetMapping("/admin/api/v1/groups")
    public Mono<Map<String, java.util.List<AccountGroups.Group>>> groups() {
        return run(() -> Map.of("data", config.accountGroups()));
    }

    @PostMapping("/admin/api/v1/groups")
    public Mono<Map<String, Long>> createGroup(@RequestBody AccountGroups.Input body) {
        return run(() -> Map.of("id", config.saveAccountGroup(null, body)));
    }

    @PutMapping("/admin/api/v1/groups/{id}")
    public Mono<Map<String, Long>> editGroup(@PathVariable long id, @RequestBody AccountGroups.Input body) {
        return run(() -> Map.of("id", config.saveAccountGroup(id, body)));
    }

    public record PreferredAccount(Long accountId) {}

    @PutMapping("/admin/api/v1/groups/{id}/preferred-account")
    public Mono<Map<String, Boolean>> preferAccount(@PathVariable long id, @RequestBody PreferredAccount body) {
        return run(() -> { config.preferGroupAccount(id, body.accountId()); return Map.of("saved", true); });
    }

    public record AccountOrder(java.util.List<Long> accountIds) {}

    @PutMapping("/admin/api/v1/groups/{id}/account-order")
    public Mono<Map<String, Boolean>> reorderAccounts(@PathVariable long id, @RequestBody AccountOrder body) {
        return run(() -> { config.reorderGroupAccounts(id, body.accountIds()); return Map.of("saved", true); });
    }

    @DeleteMapping("/admin/api/v1/groups/{id}")
    public Mono<Map<String, Long>> deleteGroup(@PathVariable long id) {
        return run(() -> { config.deleteAccountGroup(id); return Map.of("deleted", id); });
    }

    @PostMapping("/admin/api/v1/keys/bulk")
    public Mono<Map<String, Boolean>> bulkKeys(@RequestBody KeyBulk body) {
        return run(() -> { config.bulkClientKeys(body.ids(), body.enabled(), body.groupId()); return Map.of("saved", true); });
    }

    @PutMapping("/admin/api/v1/keys/{id}/ips")
    public Mono<Map<String, Boolean>> editIps(@PathVariable long id, @RequestBody GatewayConfigLoader.ClientKeyIpInput body) {
        return run(() -> { config.saveClientKeyIps(id, body); return Map.of("saved", true); });
    }

    @GetMapping("/admin/api/v1/keys")
    public Mono<Map<String, java.util.List<GatewayConfigLoader.ClientKey>>> keys() {
        return run(() -> Map.of("data", config.clientKeys()));
    }

    @PostMapping("/admin/api/v1/keys")
    public Mono<Map<String, Long>> createKey(@RequestBody GatewayConfigLoader.ClientKeyInput body) {
        return run(() -> Map.of("id", config.saveClientKey(pool, null, body)));
    }

    @PutMapping("/admin/api/v1/keys/{id}")
    public Mono<Map<String, Long>> editKey(@PathVariable long id, @RequestBody GatewayConfigLoader.ClientKeyInput body) {
        return run(() -> Map.of("id", config.saveClientKey(pool, id, body)));
    }

    @DeleteMapping("/admin/api/v1/keys/{id}")
    public Mono<Map<String, Long>> deleteKey(@PathVariable long id) {
        return run(() -> { config.deleteClientKey(id); return Map.of("deleted", id); });
    }

    @PostMapping("/admin/api/v1/keys/{id}/reset-usage")
    public Mono<Map<String, Boolean>> resetKeyUsage(@PathVariable long id, @RequestParam(defaultValue = "false") boolean total) {
        return run(() -> { config.resetClientKeySpend(id, total); return Map.of("ok", true); });
    }

    @PostMapping("/admin/api/v1/keys/{id}/use")
    public Mono<org.springframework.http.ResponseEntity<GatewayConfigLoader.ClientKeyUse>> useKey(@PathVariable long id) {
        return run(() -> org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(config.useClientKey(id)));
    }

    @GetMapping("/admin/api/v1/accounts/{id}/models")
    public Mono<Map<String, JsonNode>> models(@PathVariable long id) { return run(() -> Map.of("data", pool.models(id))); }

    @GetMapping("/admin/api/v1/accounts/{id}/codex-status")
    public Mono<org.springframework.http.ResponseEntity<CodexAccountStatus>> codexStatus(@PathVariable long id) {
        return run(() -> org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(pool.codexStatus(id)));
    }

    @GetMapping("/admin/api/v1/accounts/{id}/usage-stats")
    public Mono<AccountPool.UsageStats> usageStats(@PathVariable long id) {
        return run(() -> pool.usageStats(id));
    }

    @PostMapping("/admin/api/v1/accounts/{id}/codex-status/reset")
    public Mono<Map<String, Integer>> resetCodexQuota(@PathVariable long id, @RequestBody Map<String, String> body) {
        return run(() -> Map.of("windowsReset", pool.resetCodexQuota(id, body.get("creditId"))));
    }

    @PostMapping("/admin/api/v1/accounts/{id}/credits")
    public Mono<Map<String, Object>> credits(@PathVariable long id) {
        return run(() -> {
            Long value = pool.updateCredits(id);
            return value == null ? Map.of("available", false) : Map.of("available", true, "credits", value);
        });
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> error(org.springframework.web.server.ResponseStatusException error) {
        var status = error.getStatusCode().value() == 401 ? HttpStatus.UNPROCESSABLE_ENTITY : error.getStatusCode();
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("message", error.getReason()));
    }

    private static <T> Mono<T> run(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }
}
