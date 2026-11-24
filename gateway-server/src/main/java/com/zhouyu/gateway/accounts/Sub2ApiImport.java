package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.config.GatewayConfigLoader.ModelMapping;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;

/** Reads Sub2API's administrator account export. Imported JWT claims are identity hints only. */
final class Sub2ApiImport {
    static final String SOURCE = "sub2api";
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    static JsonNode entries(JsonNode input) {
        JsonNode data = input.has("data") ? input.path("data") : input;
        String type = data.path("type").asText("");
        JsonNode entries = data.path("accounts");
        if ((!type.isEmpty() && !Set.of("sub2api-data", "sub2api-bundle").contains(type))
                || (data.hasNonNull("version") && data.path("version").asInt(-1) != 1)
                || !entries.isArray() || entries.isEmpty() || entries.size() > 500) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择包含 1–500 个账号的 Sub2API 账号导出 JSON");
        }
        return entries;
    }

    static boolean supports(JsonNode account) {
        return "openai".equalsIgnoreCase(account.path("platform").asText())
                && "oauth".equalsIgnoreCase(account.path("type").asText());
    }

    static java.util.List<ModelMapping> modelMappings(JsonNode item) {
        var mappings = new java.util.ArrayList<ModelMapping>();
        var models = item.path("credentials").path("model_mapping");
        if (!models.isObject()) return mappings;
        var names = new java.util.HashSet<String>();
        var fields = models.fields();
        while (fields.hasNext() && mappings.size() < 100) {
            var field = fields.next();
            String name = field.getKey().trim();
            String upstream = field.getValue().asText("").trim();
            if (!field.getValue().isTextual() || name.isEmpty() || name.length() > 64 || upstream.isEmpty() || upstream.length() > 128
                    || name.contains("*") || upstream.contains("*") || !names.add(name.toLowerCase(java.util.Locale.ROOT))) continue;
            mappings.add(new ModelMapping(name, upstream));
        }
        return mappings;
    }

    static AccountPool.Registration decode(ObjectMapper mapper, JsonNode account) {
        return decode(mapper, account.path("credentials"), text(account, "name"), SOURCE);
    }

    static AccountPool.Registration fromOAuth(ObjectMapper mapper, ObjectNode token) {
        return decode(mapper, token, "", "gateway");
    }

    static AccountPool.Registration decode(ObjectMapper mapper, JsonNode source, String name, String origin) {
        String access = text(source, "access_token");
        String refresh = text(source, "refresh_token");
        if (access.isEmpty() && refresh.isEmpty()) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "缺少 OAuth 凭证");
        JsonNode accessClaims = claims(mapper, access);
        JsonNode idClaims = claims(mapper, text(source, "id_token"));
        String workspace = first(text(source, "chatgpt_account_id"), text(accessClaims.path(AUTH_CLAIM), "chatgpt_account_id"),
                text(idClaims.path(AUTH_CLAIM), "chatgpt_account_id"));
        String user = first(text(source, "chatgpt_user_id"), text(accessClaims.path(AUTH_CLAIM), "chatgpt_user_id"),
                text(idClaims.path(AUTH_CLAIM), "chatgpt_user_id"), text(idClaims, "sub"), text(accessClaims, "sub"), text(source, "email"));
        if (workspace.isEmpty() && user.isEmpty()) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "缺少 ChatGPT 账号标识");
        long expires = epoch(source.path("expires_at"));
        if (expires <= 0) expires = epoch(accessClaims.path("exp"));
        if (expires <= 0 && refresh.isEmpty()) expires = Instant.now().plusSeconds(300).getEpochSecond();
        if ((access.isEmpty() || expires <= Instant.now().getEpochSecond()) && refresh.isEmpty()) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "访问凭证已过期且缺少刷新凭证");
        }
        String client = first(text(source, "client_id"), text(accessClaims, "client_id"), CLIENT_ID);
        if (!client.matches("[a-zA-Z0-9_-]{3,160}")) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "OAuth client_id 无效");
        ObjectNode token = mapper.createObjectNode().put("source", origin).put("codex_oauth", true).put("access_token", access)
                .put("refresh_token", refresh).put("client_id", client).put("chatgpt_account_id", workspace).put("chatgpt_user_id", user).put("expires_at", expires);
        String stableId = (SOURCE.equals(origin) ? "sub2:" : "codex:") + RouteTable.sha256(client + "\n" + workspace + "\n" + user);
        return new AccountPool.Registration(AccountPool.CODEX, stableId,
                first(name, text(source, "email"), text(idClaims, "email"), "ChatGPT 账号"), "chatgpt", access.isEmpty() ? 0 : expires, token);
    }

    static long epoch(JsonNode node) {
        long numeric = node.asLong(0);
        if (numeric > 0) return numeric >= 1_000_000_000_000L ? numeric / 1000 : numeric;
        if (!node.isTextual()) return 0;
        try { return Instant.parse(node.asText()).getEpochSecond(); }
        catch (DateTimeParseException ignored) { return 0; }
    }

    private static JsonNode claims(ObjectMapper mapper, String jwt) {
        String[] parts = jwt.split("\\.", 3);
        if (parts.length == 3 && parts[1].length() < 32768) {
            try {
                JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
                if (payload != null && payload.isObject()) return payload;
            }
            catch (Exception ignored) { }
        }
        return mapper.missingNode();
    }

    private static String text(JsonNode node, String key) {
        String value = node.path(key).asText("").trim();
        if (value.contains("\r") || value.contains("\n")) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "账号字段包含无效字符");
        return value;
    }

    private static String first(String... values) {
        for (String value : values) if (!value.isEmpty()) return value;
        return "";
    }
}
