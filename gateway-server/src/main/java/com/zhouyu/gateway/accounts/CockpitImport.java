package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

/** Reads Cockpit Tools' Codex export, including older records with nested tokens. */
final class CockpitImport {
    static final String SOURCE = "cockpit";

    static boolean isFormat(JsonNode input) {
        return input.isArray() || isAccount(input);
    }

    static JsonNode entries(ObjectMapper mapper, JsonNode input) {
        JsonNode entries = input.isArray() ? input : mapper.createArrayNode().add(input);
        if (entries.isEmpty() || entries.size() > 500) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择包含 1–500 个账号的 Cockpit Tools 导出 JSON");
        }
        return entries;
    }

    static boolean supports(JsonNode account) {
        String mode = account.path("auth_mode").asText();
        return isAccount(account) && (mode.isBlank() || "oauth".equalsIgnoreCase(mode))
                && !account.hasNonNull("agent_identity") && account.path("OPENAI_API_KEY").asText("").isBlank()
                && account.path("openai_api_key").asText("").isBlank();
    }

    private static boolean isAccount(JsonNode account) {
        String mode = account.path("auth_mode").asText();
        return "codex".equalsIgnoreCase(account.path("type").asText()) || account.path("tokens").isObject()
                || "apikey".equalsIgnoreCase(mode) || "agentIdentity".equalsIgnoreCase(mode);
    }

    static AccountPool.Registration decode(ObjectMapper mapper, JsonNode account) {
        var credential = mapper.createObjectNode();
        var tokens = account.path("tokens");
        for (String field : new String[]{"access_token", "refresh_token", "id_token", "client_id"}) {
            credential.set(field, prefer(account.path(field), tokens.path(field)));
        }
        credential.set("chatgpt_account_id", prefer(account.path("account_id"), tokens.path("account_id")));
        credential.set("chatgpt_user_id", account.path("user_id"));
        credential.set("email", account.path("email"));
        credential.set("expires_at", prefer(account.path("expired"), account.path("expires_at")));
        return Sub2ApiImport.decode(mapper, credential, account.path("account_name").asText("").strip(), SOURCE);
    }

    private static JsonNode prefer(JsonNode value, JsonNode fallback) {
        return value.asText("").isBlank() ? fallback : value;
    }
}
