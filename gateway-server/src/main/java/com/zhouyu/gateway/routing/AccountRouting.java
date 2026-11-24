package com.zhouyu.gateway.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouyu.gateway.accounts.AccountPool;
import com.zhouyu.gateway.accounts.CodexAccountStatus;
import com.zhouyu.gateway.domain.AccountRoutingMode;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AccountRouting {
    private final AccountPool accounts;
    private final SessionKeyExtractor sessions;
    private final Cache<GroupModel, AtomicLong> turns = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(2)).build();
    private final Cache<Session, Long> affinity = Caffeine.newBuilder().maximumSize(20_000)
            .expireAfterAccess(Duration.ofMinutes(30)).build();

    public AccountRouting(AccountPool accounts, SessionKeyExtractor sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    private record GroupModel(long groupId, String model) {}
    public record Session(long tenantId, long keyId, long groupId, String model, String id) {}
    public record Plan(List<Candidate> candidates, Session session) {}
    private record Ranked(Candidate candidate, int tier, long resetAt) {}

    public Plan plan(RouteTable table, ApiKeyView key, String model, List<Candidate> candidates,
                     ServerHttpRequest request, byte[] body) {
        if (key.accountGroup() == null || candidates.isEmpty()) return new Plan(candidates, null);
        var byGroup = new java.util.LinkedHashMap<Long, List<Candidate>>();
        for (var candidate : candidates) {
            long groupId = candidate.groupId() != null ? candidate.groupId() : key.groupId();
            byGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(candidate);
        }
        boolean sticky = byGroup.keySet().stream().map(table::accountRoutingMode)
                .anyMatch(mode -> mode != AccountRoutingMode.ORDER && mode != AccountRoutingMode.ROTATE);
        String id = sticky ? sessions.extract(request, body) : null;
        Session session = id == null || id.length() > 256 ? null : new Session(key.tenantId(), key.id(), key.groupId(), model, id);
        var ordered = new ArrayList<Candidate>();
        byGroup.forEach((groupId, groupCandidates) ->
                ordered.addAll(order(table.accountRoutingMode(groupId), groupId, model, groupCandidates, session)));
        return new Plan(List.copyOf(ordered), session);
    }

    private List<Candidate> order(AccountRoutingMode mode, long groupId, String model, List<Candidate> candidates, Session session) {
        if (mode == AccountRoutingMode.ORDER) return candidates;
        if (mode == AccountRoutingMode.ROTATE) {
            if (candidates.size() == 1) return candidates;
            long turn = turns.get(new GroupModel(groupId, model), ignored -> new AtomicLong()).getAndIncrement();
            var rotated = new ArrayList<>(candidates);
            Collections.rotate(rotated, -Math.floorMod(turn, rotated.size()));
            return rotated;
        }
        Long previous = session == null ? null : affinity.getIfPresent(session);
        long now = Instant.now().getEpochSecond();
        var ranked = new ArrayList<Ranked>(candidates.size());
        for (Candidate candidate : candidates) {
            var windows = AccountPool.CODEX.equals(candidate.route().accountGroup())
                    ? accounts.cachedCodexWindows(candidate.route().accountId()) : List.<CodexAccountStatus.Window>of();
            var active = windows.stream().filter(w -> w.resetsAt() != null && w.resetsAt() > now && w.usedPercent() != null).toList();
            boolean exhausted = active.stream().anyMatch(w -> w.usedPercent() >= 100);
            long resetAt = active.stream().max(Comparator.comparingInt(CodexAccountStatus.Window::durationSeconds))
                    .map(CodexAccountStatus.Window::resetsAt).orElse(Long.MAX_VALUE);
            int tier = exhausted ? 2 : Objects.equals(previous, candidate.route().accountId()) ? 0 : 1;
            ranked.add(new Ranked(candidate, tier, resetAt));
        }
        ranked.sort(Comparator.comparingInt(Ranked::tier).thenComparingLong(Ranked::resetAt));
        return ranked.stream().map(Ranked::candidate).toList();
    }

    public void remember(Session session, Long accountId) {
        if (session != null && accountId != null) affinity.put(session, accountId);
    }

    public static boolean accountRejected(int status) {
        return status == 401 || status == 402 || status == 403 || status == 429;
    }
}
