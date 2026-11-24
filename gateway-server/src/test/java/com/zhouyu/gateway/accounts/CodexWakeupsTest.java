package com.zhouyu.gateway.accounts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexWakeupsTest {
    @Test
    void usesBeijingTimeAndSkipsPreviousDays() {
        var daily = new CodexWakeups.Schedule("daily", "09:00", null, null, null, null);
        var weekly = new CodexWakeups.Schedule("weekly", "09:00", List.of(2), null, null, null);
        long tuesday = epoch("2026-11-24T01:00:00Z");
        long monday = tuesday - 86400;
        assertThat(CodexWakeups.nextAt(daily, monday - 1, 0, 0, monday, tuesday + 1)).isEqualTo(tuesday);
        assertThat(CodexWakeups.nextAt(daily, monday - 1, tuesday, 0, monday, tuesday + 1)).isEqualTo(tuesday + 86400);
        assertThat(CodexWakeups.nextAt(weekly, monday - 1, tuesday, 0, monday, tuesday + 1)).isEqualTo(tuesday + 7 * 86400);
    }

    @Test
    void respectsStartupOnceIntervalAnchorsAndSavedQuotaPlans() {
        long now = epoch("2026-11-24T01:00:00Z");
        var startup = new CodexWakeups.Schedule("startup", null, null, null, null, 2);
        var interval = new CodexWakeups.Schedule("interval", null, null, 4, null, null);
        var quota = new CodexWakeups.Schedule("quota_reset", null, null, null, "week", 10);
        assertThat(CodexWakeups.nextAt(startup, now - 300, 0, 0, now, now)).isEqualTo(now + 120);
        assertThat(CodexWakeups.nextAt(startup, now - 300, now + 120, 0, now, now + 121)).isNull();
        assertThat(CodexWakeups.nextAt(startup, now + 1, 0, 0, now, now + 2)).isNull();
        assertThat(CodexWakeups.nextAt(interval, now - 3600, now, 0, now - 1, now + 1)).isEqualTo(now + 14400);
        assertThat(CodexWakeups.nextAt(quota, now - 3600, 0, now + 600, now - 1, now + 300)).isEqualTo(now + 600);
        assertThat(CodexWakeups.nextAt(quota, now - 3600, now + 600, now + 600, now - 1, now + 601)).isNull();
    }

    private static long epoch(String value) { return Instant.parse(value).getEpochSecond(); }
}
