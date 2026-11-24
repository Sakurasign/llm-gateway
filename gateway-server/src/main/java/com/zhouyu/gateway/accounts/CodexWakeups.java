package com.zhouyu.gateway.accounts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.protocol.responses.ResponsesController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CodexWakeups {
    private static final Logger log = LoggerFactory.getLogger(CodexWakeups.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AccountPool accounts;
    private final ResponsesController responses;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile long startedAt;

    public CodexWakeups(JdbcTemplate jdbc, ObjectMapper mapper, AccountPool accounts, ResponsesController responses) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.accounts = accounts;
        this.responses = responses;
    }

    public record Schedule(String kind, String time, List<Integer> days, Integer hours, String window, Integer delay) {}
    public record Input(String name, boolean enabled, List<Long> accountIds, String model, String prompt, String effort, Schedule schedule) {}
    private record Task(String id, Input input, long updatedAt, long lastScheduledAt, long quotaDue) {}
    public record TaskView(String id, Input input, Long nextRunAt, boolean running) {}
    public record Entry(String taskId, String name, long accountId, String trigger, long runAt, boolean success, String message) {}
    public record Overview(List<TaskView> tasks, List<Entry> history) {}

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway_codex_wakeup (
                  id CHAR(36) PRIMARY KEY, settings JSON NOT NULL, updated_at BIGINT NOT NULL,
                  last_scheduled_at BIGINT NOT NULL DEFAULT 0, quota_due BIGINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway_codex_wakeup_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id CHAR(36) NOT NULL,
                  task_name VARCHAR(80) NOT NULL, account_id BIGINT NOT NULL,
                  trigger_type VARCHAR(16) NOT NULL, run_at BIGINT NOT NULL,
                  success BOOLEAN NOT NULL, message VARCHAR(500) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        startedAt = Instant.now().getEpochSecond();
    }

    public Overview overview() {
        long now = Instant.now().getEpochSecond();
        var views = tasks().stream().map(task -> new TaskView(task.id(), task.input(), next(task, now), running.contains(task.id()))).toList();
        var history = jdbc.query("SELECT * FROM gateway_codex_wakeup_log ORDER BY id DESC LIMIT 100", (row, index) ->
                new Entry(row.getString("task_id"), row.getString("task_name"), row.getLong("account_id"),
                        row.getString("trigger_type"), row.getLong("run_at"), row.getBoolean("success"), row.getString("message")));
        return new Overview(views, history);
    }

    public void save(String id, Input input) {
        Input settings = validate(input);
        long now = Instant.now().getEpochSecond();
        String json;
        try { json = mapper.writeValueAsString(settings); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        if (id == null) {
            jdbc.update("INSERT INTO gateway_codex_wakeup(id,settings,updated_at) VALUES(?,?,?)", UUID.randomUUID().toString(), json, now);
        } else {
            int changed = jdbc.update("UPDATE gateway_codex_wakeup SET settings=?,updated_at=?,last_scheduled_at=0,quota_due=0 WHERE id=?", json, now, id);
            if (changed == 0) throw missing();
        }
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM gateway_codex_wakeup WHERE id=?", id) == 0) throw missing();
    }

    public void trigger(String id) {
        var task = tasks().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(CodexWakeups::missing);
        if (!running.add(id)) throw AccountPool.problem(HttpStatus.CONFLICT, "任务正在运行");
        Mono.fromRunnable(() -> execute(task, "manual"))
                .subscribeOn(Schedulers.boundedElastic()).doFinally(signal -> running.remove(id))
                .subscribe(ignored -> {}, error -> log.warn("Manual Codex wakeup failed for task {}", id, error));
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    void tick() {
        if (startedAt == 0 || !checking.compareAndSet(false, true)) return;
        Mono.fromRunnable(this::runDue).subscribeOn(Schedulers.boundedElastic()).doFinally(signal -> checking.set(false))
                .subscribe(ignored -> {}, error -> log.warn("Codex wakeup scheduler failed", error));
    }

    void runDue() {
        var windows = new HashMap<Long, List<CodexAccountStatus.Window>>();
        for (var task : tasks()) {
            if (!task.input().enabled() || running.contains(task.id())) continue;
            long now = Instant.now().getEpochSecond();
            Long due = next(task, now);
            if (schedule(task).kind().equals("quota_reset") && (due == null || due > now)) {
                due = planQuota(task, now, windows);
            }
            if (due == null || due > now || !running.add(task.id())) continue;
            try {
                int claimed = jdbc.update("""
                        UPDATE gateway_codex_wakeup SET last_scheduled_at=?, quota_due=0
                        WHERE id=? AND updated_at=? AND last_scheduled_at=?
                        """, now, task.id(), task.updatedAt(), task.lastScheduledAt());
                if (claimed == 1) execute(task, schedule(task).kind());
            } finally {
                running.remove(task.id());
            }
        }
    }

    private Long planQuota(Task task, long now, HashMap<Long, List<CodexAccountStatus.Window>> windows) {
        Long next = next(task, now);
        long anchor = Math.max(task.updatedAt(), task.lastScheduledAt());
        for (long accountId : task.input().accountIds()) {
            var accountWindows = windows.computeIfAbsent(accountId, accounts::wakeupQuotaWindows);
            for (var window : accountWindows) {
                if (window.resetsAt() == null || window.resetsAt() <= anchor || !matchesWindow(schedule(task).window(), window)) continue;
                long due = window.resetsAt() + schedule(task).delay() * 60L;
                if (next == null || due < next) next = due;
            }
        }
        if (next != null && next != task.quotaDue()) {
            jdbc.update("UPDATE gateway_codex_wakeup SET quota_due=? WHERE id=? AND updated_at=? AND last_scheduled_at=?",
                    next, task.id(), task.updatedAt(), task.lastScheduledAt());
        }
        return next;
    }

    private static boolean matchesWindow(String filter, CodexAccountStatus.Window window) {
        return switch (filter) {
            case "5h" -> window.durationSeconds() == 18_000;
            case "week" -> window.durationSeconds() == 604_800;
            default -> window.durationSeconds() == 18_000 || window.durationSeconds() == 604_800;
        };
    }

    private Long next(Task task, long now) {
        if (!task.input().enabled()) return null;
        return nextAt(schedule(task), task.updatedAt(), task.lastScheduledAt(), task.quotaDue(), startedAt, now);
    }

    static Long nextAt(Schedule schedule, long updated, long last, long quotaDue, long started, long now) {
        long anchor = Math.max(updated, last);
        return switch (schedule.kind()) {
            case "interval" -> anchor + schedule.hours() * 3600L;
            case "startup" -> updated < started && last < started ? started + schedule.delay() * 60L : null;
            case "quota_reset" -> quotaDue > anchor ? quotaDue : null;
            default -> {
                var today = Instant.ofEpochSecond(now).atZone(ZONE).toLocalDate();
                var time = LocalTime.parse(schedule.time());
                for (int offset = 0; offset <= 7; offset++) {
                    var day = today.plusDays(offset);
                    if (schedule.kind().equals("weekly") && !schedule.days().contains(day.getDayOfWeek().getValue())) continue;
                    long slot = day.atTime(time).atZone(ZONE).toEpochSecond();
                    if (slot > anchor) yield slot;
                }
                yield null;
            }
        };
    }

    private void execute(Task task, String trigger) {
        var input = task.input();
        for (long accountId : input.accountIds()) {
            long now = Instant.now().getEpochSecond();
            boolean success = false;
            String message;
            try {
                var result = responses.wakeCodex(accountId, input.model(), input.prompt(), input.effort()).block();
                success = result.ok();
                message = success ? result.output() : result.message();
            } catch (GatewayException e) {
                message = e.getMessage();
            } catch (RuntimeException e) {
                log.warn("Codex wakeup request failed for account {}", accountId, e);
                message = "唤醒请求失败";
            }
            jdbc.update("""
                    INSERT INTO gateway_codex_wakeup_log(task_id,task_name,account_id,trigger_type,run_at,success,message)
                    VALUES(?,?,?,?,?,?,?)
                    """, task.id(), input.name(), accountId, trigger, now, success,
                    message == null ? "" : message.substring(0, Math.min(500, message.length())));
        }
    }

    private List<Task> tasks() {
        return jdbc.query("SELECT * FROM gateway_codex_wakeup ORDER BY updated_at DESC,id", (row, index) -> {
            try {
                return new Task(row.getString("id"), mapper.readValue(row.getString("settings"), Input.class),
                        row.getLong("updated_at"), row.getLong("last_scheduled_at"), row.getLong("quota_due"));
            } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        });
    }

    private Input validate(Input input) {
        String name = clean(input.name());
        String model = clean(input.model());
        String prompt = clean(input.prompt());
        String effort = clean(input.effort());
        if (name.isEmpty() || name.length() > 80 || model.isEmpty() || model.length() > 128 || model.contains("*")
                || model.chars().anyMatch(Character::isISOControl) || prompt.length() > 1000
                || (!effort.isEmpty() && !EFFORTS.contains(effort))) throw invalid("请检查任务名称、模型、提示词和推理强度");
        var ids = input.accountIds() == null ? List.<Long>of() : input.accountIds().stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0 || !accounts.isCodexSubscription(id))) {
            throw invalid("请选择 1–20 个 ChatGPT / Codex 订阅账号");
        }
        var s = input.schedule();
        if (s == null || s.kind() == null) throw invalid("请选择触发方式");
        switch (s.kind()) {
            case "daily", "weekly" -> {
                if (s.time() == null || !s.time().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid("请输入有效的触发时间");
                if (s.kind().equals("weekly") && (s.days() == null || s.days().isEmpty()
                        || s.days().stream().anyMatch(day -> day == null || day < 1 || day > 7))) throw invalid("请选择有效的星期");
            }
            case "interval" -> {
                if (s.hours() == null || s.hours() < 1 || s.hours() > 168) throw invalid("间隔须为 1–168 小时");
            }
            case "startup", "quota_reset" -> {
                if (s.delay() == null || s.delay() < 0 || s.delay() > 1440) throw invalid("延迟须为 0–1440 分钟");
                if (s.kind().equals("quota_reset") && (s.window() == null || !Set.of("either", "5h", "week").contains(s.window()))) {
                    throw invalid("请选择额度窗口");
                }
            }
            default -> throw invalid("请选择有效的触发方式");
        }
        return new Input(name, input.enabled(), ids, model, prompt.isEmpty() ? "hi" : prompt, effort, s);
    }

    private static Schedule schedule(Task task) { return task.input().schedule(); }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static org.springframework.web.server.ResponseStatusException invalid(String message) { return AccountPool.problem(HttpStatus.BAD_REQUEST, message); }
    private static org.springframework.web.server.ResponseStatusException missing() { return AccountPool.problem(HttpStatus.NOT_FOUND, "定时任务不存在"); }
}
