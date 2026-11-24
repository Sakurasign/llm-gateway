package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CodexAccountStatus(String planType, Long subscriptionStartedAt, Long subscriptionExpiresAt,
                                 List<Window> windows, ResetCredits resetCredits,
                                 String subscriptionError, String usageError, long checkedAt) {
    public record Window(int durationSeconds, Double usedPercent, Long resetsAt) {}

    public record ResetCredit(String id, String label, String description, long expiresAt) {}
    public record ResetCredits(Integer availableCount, List<ResetCredit> options, String error) {}

    static CodexAccountStatus from(JsonNode subscription, JsonNode usage, ResetCredits resetCredits,
                                   String subscriptionError, String usageError, long now) {
        String plan = subscription.path("plan_type").asText("");
        return new CodexAccountStatus(plan.isBlank() ? usage.path("plan_type").asText(null) : plan,
                firstDate(subscription, "active_since", "active_from", "subscription_started_at", "started_at", "start_date", "created_at"),
                firstDate(subscription, "active_until"), windows(usage.path("rate_limit"), now),
                resetCredits, subscriptionError, usageError, now);
    }

    static ResetCredits resetCredits(JsonNode details, int summaryCount, String error, long now) {
        int count = details.path("available_count").asInt(summaryCount);
        var credits = new ArrayList<ResetCredit>();
        JsonNode items = details.isArray() ? details : details.path("credits");
        if (count != 0 && details.path("is_supported_by_plan").asBoolean(true) && items.isArray()) {
            for (JsonNode item : items) {
                String id = item.path("id").asText(item.path("credit_id").asText(""));
                Long expiresAt = firstDate(item, "expires_at");
                if (id.isBlank() || expiresAt == null || expiresAt <= now
                        || !"available".equals(item.path("status").asText("available"))) continue;
                String label = item.path("title").asText("");
                credits.add(new ResetCredit(id, label.isBlank() ? "额度重置券" : label,
                        item.path("description").asText(null), expiresAt));
            }
        }
        if (count < 0 && items.isArray()) count = credits.size();
        return new ResetCredits(count < 0 ? null : count, List.copyOf(credits), error);
    }

    static List<Window> windows(JsonNode limits, long now) {
        Map<Integer, Window> windows = new TreeMap<>();
        for (String key : new String[]{"primary_window", "secondary_window"}) {
            JsonNode window = limits.path(key);
            int seconds = window.path("limit_window_seconds").asInt();
            if (seconds <= 0) continue;
            double used = window.path("used_percent").asDouble(Double.NaN);
            Long resetsAt = firstDate(window, "reset_at");
            long remainingSeconds = window.path("reset_after_seconds").asLong();
            if (resetsAt == null && remainingSeconds > 0 && remainingSeconds <= seconds) resetsAt = now + remainingSeconds;
            windows.putIfAbsent(seconds, new Window(seconds, Double.isFinite(used) ? Math.max(0, Math.min(100, used)) : null, resetsAt));
        }
        return List.copyOf(windows.values());
    }

    private static Long firstDate(JsonNode data, String... fields) {
        for (String field : fields) {
            long seconds = Sub2ApiImport.epoch(data.path(field));
            if (seconds > 0 && seconds < 253_402_300_800L) return seconds;
        }
        return null;
    }
}
