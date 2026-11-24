package com.zhouyu.gateway.admin;

import com.zhouyu.gateway.config.GatewayConfigLoader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/admin/api/v1")
public class AdminConfigController {
    private final GatewayConfigLoader config;

    public AdminConfigController(GatewayConfigLoader config) { this.config = config; }

    @PostMapping("/channels")
    public Mono<Map<String, Long>> createChannel(@RequestBody GatewayConfigLoader.ChannelInput body, @RequestParam long version) {
        return write(() -> config.saveChannel(null, body, version));
    }

    @PutMapping("/channels/{id}")
    public Mono<Map<String, Long>> editChannel(@PathVariable long id, @RequestBody GatewayConfigLoader.ChannelInput body,
                                                @RequestParam long version) {
        return write(() -> config.saveChannel(id, body, version));
    }

    @PatchMapping("/channels/{id}")
    public Mono<Map<String, Long>> status(@PathVariable long id, @RequestParam boolean enabled, @RequestParam long version) {
        return write(() -> config.setEnabled(id, enabled, version));
    }

    @DeleteMapping("/channels/{id}")
    public Mono<Map<String, Long>> deleteChannel(@PathVariable long id, @RequestParam long version) {
        return write(() -> config.deleteChannel(id, version));
    }

    @PostMapping("/routes")
    public Mono<Map<String, Long>> createRoute(@RequestBody GatewayConfigLoader.RouteInput body, @RequestParam long version) {
        return write(() -> config.saveRoute(null, body, version));
    }

    @PutMapping("/routes/{id}")
    public Mono<Map<String, Long>> editRoute(@PathVariable long id, @RequestBody GatewayConfigLoader.RouteInput body,
                                              @RequestParam long version) {
        return write(() -> config.saveRoute(id, body, version));
    }

    @DeleteMapping("/routes/{id}")
    public Mono<Map<String, Long>> deleteRoute(@PathVariable long id, @RequestParam long version) {
        return write(() -> config.deleteRoute(id, version));
    }

    public record ImportBody(String yaml) {}

    @PostMapping("/config/import")
    public Mono<Map<String, Long>> importConfig(@RequestBody ImportBody body, @RequestParam long version) {
        return write(() -> config.importYaml(body.yaml(), version));
    }

    private Mono<Map<String, Long>> write(Callable<Long> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic())
                .map(version -> Map.of("version", version))
                .onErrorMap(DuplicateKeyException.class,
                        error -> new ResponseStatusException(HttpStatus.CONFLICT, "渠道名称、模型绑定或版本已存在"));
    }
}
