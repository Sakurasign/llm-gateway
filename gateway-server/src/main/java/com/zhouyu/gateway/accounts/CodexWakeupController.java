package com.zhouyu.gateway.accounts;

import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1/accounts/wakeups")
class CodexWakeupController {
    private final CodexWakeups wakeups;

    CodexWakeupController(CodexWakeups wakeups) { this.wakeups = wakeups; }

    @GetMapping
    Mono<CodexWakeups.Overview> overview() {
        return Mono.fromCallable(wakeups::overview).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    Mono<Map<String, Boolean>> create(@RequestBody CodexWakeups.Input body) {
        return run(() -> wakeups.save(null, body));
    }

    @PutMapping("/{id}")
    Mono<Map<String, Boolean>> update(@PathVariable String id, @RequestBody CodexWakeups.Input body) {
        return run(() -> wakeups.save(id, body));
    }

    @DeleteMapping("/{id}")
    Mono<Map<String, Boolean>> delete(@PathVariable String id) {
        return run(() -> wakeups.delete(id));
    }

    @PostMapping("/{id}/run")
    Mono<Map<String, Boolean>> runNow(@PathVariable String id) {
        return run(() -> wakeups.trigger(id));
    }

    private static Mono<Map<String, Boolean>> run(Runnable action) {
        return Mono.fromCallable(() -> {
            action.run();
            return Map.of("ok", true);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
