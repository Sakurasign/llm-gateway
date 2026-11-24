package com.zhouyu.gateway.quota;

import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KeySpend {
    public record Amounts(long total, long fiveHours, long oneDay, long sevenDays) {
        public static final Amounts ZERO = new Amounts(0, 0, 0, 0);

        public boolean enabled() {
            return total > 0 || fiveHours > 0 || oneDay > 0 || sevenDays > 0;
        }

        public boolean valid() {
            return validAmount(total) && validAmount(fiveHours) && validAmount(oneDay) && validAmount(sevenDays);
        }

        private static boolean validAmount(long amount) { return amount >= 0 && amount <= 1_000_000_000_000L; }

        public boolean reached(Amounts used) {
            return (total > 0 && used.total >= total) || (fiveHours > 0 && used.fiveHours >= fiveHours)
                    || (oneDay > 0 && used.oneDay >= oneDay) || (sevenDays > 0 && used.sevenDays >= sevenDays);
        }
    }

    private final JdbcTemplate jdbc;

    public KeySpend(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Amounts used(long id) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.cost_micro),0),
                       COALESCE(SUM(CASE WHEN l.created_at >= NOW() - INTERVAL 5 HOUR AND l.created_at >= m.window_reset_at THEN l.cost_micro END),0),
                       COALESCE(SUM(CASE WHEN l.created_at >= NOW() - INTERVAL 1 DAY AND l.created_at >= m.window_reset_at THEN l.cost_micro END),0),
                       COALESCE(SUM(CASE WHEN l.created_at >= NOW() - INTERVAL 7 DAY AND l.created_at >= m.window_reset_at THEN l.cost_micro END),0)
                FROM relay_log l JOIN gateway_client_key m ON m.key_id=l.api_key_id
                WHERE l.api_key_id=? AND l.created_at>=m.quota_reset_at
                """, (row, n) -> new Amounts(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)), id);
    }

    public Mono<Void> check(ApiKeyView key) {
        return Mono.fromRunnable(() -> {
            if (key.spendLimits().reached(used(key.id()))) throw new GatewayException(ErrorCode.INSUFFICIENT_QUOTA, "API Key 用量已达额度");
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
