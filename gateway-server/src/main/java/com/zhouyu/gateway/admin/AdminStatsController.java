package com.zhouyu.gateway.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志与统计查询。
 *
 * <p>所有列表查询强制时间范围（默认近 24h），避免全表扫描。
 */
@RestController
@RequestMapping("/admin/api/v1")
public class AdminStatsController {

    private final JdbcTemplate jdbc;

    public AdminStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 请求日志分页。 */
    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String model,
                                  @RequestParam(required = false) String channel,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(required = false) String requestId,
                                  @RequestParam(required = false) Long accountId,
                                  @RequestParam(required = false) Long groupId,
                                  @RequestParam(required = false) java.time.LocalDate from,
                                  @RequestParam(required = false) java.time.LocalDate to,
                                  @RequestParam(defaultValue = "false") boolean modelOrder,
                                  @RequestParam(defaultValue = "false") boolean ascending) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * limit;

        if (from != null && to != null && from.isAfter(to)) return ResponseEntity.badRequest().build();
        StringBuilder where = new StringBuilder(from == null && to == null
                ? " WHERE l.created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)" : " WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        filter(where, args, "virtual_model", "=", model);
        filter(where, args, "channel_name", "=", channel);
        filter(where, args, "status_code", "=", status);
        filter(where, args, "request_id", "=", requestId);
        filter(where, args, "account_id", "=", accountId);
        filter(where, args, "group_id", "=", groupId);
        if (from != null) filter(where, args, "created_at", ">=", from.atStartOfDay());
        if (to != null) filter(where, args, "created_at", "<", to.plusDays(1).atStartOfDay());
        Integer total = jdbc.queryForObject("SELECT COUNT(1) FROM relay_log l" + where, Integer.class, args.toArray());
        args.add(limit);
        args.add(offset);
        String order = modelOrder ? "l.virtual_model" : "l.created_at";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.request_id,l.virtual_model,l.upstream_model,l.channel_name,l.protocol_in,l.protocol_out,
                       l.gateway_stream,l.status_code,l.error_code,l.input_tokens,l.output_tokens,l.cached_tokens,
                       l.cache_creation_tokens,l.uncached_input_tokens,l.reasoning_tokens,l.cost_micro,l.usage_source,
                       l.ttft_ms,l.total_ms,l.created_at,l.account_id,l.group_id,l.inbound_endpoint,l.upstream_endpoint,
                       l.reasoning_effort,l.billing_mode,l.account_cost_micro,l.retry_count,a.name AS account_name,g.name AS group_name
                FROM relay_log l LEFT JOIN gateway_upstream_account a ON a.id=l.account_id
                LEFT JOIN api_group g ON g.id=l.group_id
                """ + where + " ORDER BY " + order + (ascending ? " ASC" : " DESC") + ", l.id DESC LIMIT ? OFFSET ?", args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", page);
        out.put("size", limit);
        out.put("data", rows);
        return ResponseEntity.ok(out);
    }

    private static void filter(StringBuilder where, List<Object> args, String column, String operator, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) return;
        where.append(" AND l.").append(column).append(' ').append(operator).append(" ?");
        args.add(value);
    }

    /** 单条日志详情。 */
    @GetMapping("/logs/{requestId}")
    public ResponseEntity<?> logDetail(@PathVariable String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM relay_log WHERE request_id = ?", requestId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rows.get(0));
    }

    /** 概览统计：近 24 小时。 */
    @GetMapping("/stats/overview")
    public ResponseEntity<?> overview() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(1) AS requests,
                       COALESCE(SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), 0) AS errors,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro,
                       COALESCE(AVG(total_ms), 0) AS avg_ms
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                """);
        long reqs = ((Number) row.getOrDefault("requests", 0)).longValue();
        long errs = ((Number) row.getOrDefault("errors", 0)).longValue();
        row.put("successRate", reqs == 0 ? 1.0 : 1.0 - (double) errs / reqs);
        return ResponseEntity.ok(row);
    }

    /** 按模型分组的统计。 */
    @GetMapping("/stats/by-model")
    public ResponseEntity<?> byModel() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT virtual_model,
                       COUNT(1) AS requests,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY virtual_model
                ORDER BY requests DESC
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }

    /** 按渠道分组的统计。 */
    @GetMapping("/stats/by-channel")
    public ResponseEntity<?> byChannel() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT channel_name,
                       COUNT(1) AS requests,
                       COALESCE(SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), 0) AS errors,
                       COALESCE(AVG(total_ms), 0) AS avg_ms,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY channel_name
                ORDER BY requests DESC
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }

    /** 时间序列（按分钟聚合，最近 2 小时）。 */
    @GetMapping("/stats/timeseries")
    public ResponseEntity<?> timeseries() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS ts,
                       COUNT(1) AS requests,
                       COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micro), 0) AS cost_micro
                FROM relay_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 2 HOUR)
                GROUP BY ts
                ORDER BY ts
                """);
        return ResponseEntity.ok(Map.of("data", rows));
    }
}
