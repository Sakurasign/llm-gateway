package com.zhouyu.gateway.admin;

import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1")
public class AdminQueryController {

    private final RouteTableHolder tables;
    private final CircuitBreaker breaker;

    public AdminQueryController(RouteTableHolder tables, CircuitBreaker breaker) {
        this.tables = tables;
        this.breaker = breaker;
    }

    /** 系统概览。 */
    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        RouteTable table = tables.table();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", table.version());
        out.put("models", table.knownModels().size());
        out.put("channels", table.channels().size());
        return ResponseEntity.ok(out);
    }

    /** 渠道列表（含熔断状态）。 */
    @GetMapping("/channels")
    public ResponseEntity<?> channels() {
        RouteTable table = tables.table();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Channel c : table.channels().values().stream().sorted(java.util.Comparator.comparingLong(Channel::id)).toList()) {
            if (table.isAccountChannel(c.id())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("protocol", c.protocol().name());
            m.put("baseUrl", c.baseUrl());
            m.put("priority", c.priority());
            m.put("weight", c.weight());
            m.put("maxInflight", c.maxInflight());
            m.put("passthrough", c.passthrough());
            m.put("enabled", c.enabled());
            m.put("timeoutMs", c.timeout().toMillis());
            m.put("apiKeyInQuery", c.apiKeyInQuery());
            m.put("hasCredential", !c.apiKey().isBlank());
            list.add(m);
        }
        return ResponseEntity.ok(Map.of("data", list, "version", table.version()));
    }

    /** 虚拟模型与其路由。 */
    @GetMapping("/models")
    public ResponseEntity<?> models() {
        RouteTable table = tables.table();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : table.knownModels()) {
            List<Map<String, Object>> routes = new ArrayList<>();
            for (ModelRoute r : table.routes(name)) {
                if (r.accountId() != null) continue;
                Channel channel = r.channel();
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.id());
                rm.put("channelId", channel.id());
                rm.put("channelName", channel.name());
                rm.put("upstreamModel", r.upstreamModel());
                rm.put("priority", channel.priority());
                rm.put("weight", r.weight());
                rm.put("priceRatio", r.priceRatio());
                rm.put("protocol", channel.protocol().name());
                rm.put("enabled", channel.enabled());
                routes.add(rm);
            }
            if (routes.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("routes", routes);
            list.add(m);
        }
        return ResponseEntity.ok(Map.of("data", list, "version", table.version()));
    }

    /** 单个模型的熔断状态。 */
    @GetMapping("/breaker/{channelId}/{model}")
    public ResponseEntity<?> breakerState(@PathVariable long channelId, @PathVariable String model) {
        return ResponseEntity.ok(Map.of(
                "channelId", channelId,
                "model", model,
                "state", breaker.state(channelId, model).name()));
    }
}
