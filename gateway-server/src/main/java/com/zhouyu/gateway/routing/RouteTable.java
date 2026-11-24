package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.domain.AccountRoutingMode;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 不可变路由表快照。整表通过 AtomicReference 整体替换实现热更新，
 * 读路径无锁、无迭代器并发问题。
 */
public final class RouteTable {

    private final long version;
    private final Map<Long, Channel> channels;
    private final Set<Long> accountChannels;
    private final Map<Long, Set<Long>> groupAccounts;
    private final Map<Long, Map<Long, Integer>> groupAccountPositions;
    private final Map<Long, AccountRoutingMode> groupRoutingModes;
    private final Map<String, List<ModelRoute>> routesByModel;
    private final Map<String, ApiKeyView> keysByHash;

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys) {
        this(version, routes, keys, routes.stream().collect(Collectors.toMap(
                r -> r.channel().id(), ModelRoute::channel, (first, second) -> first)));
    }

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys, Map<Long, Channel> channels) {
        this(version, routes, keys, channels, Set.of());
    }

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys, Map<Long, Channel> channels, Set<Long> accountChannels) {
        this(version, routes, keys, channels, accountChannels, Map.of());
    }

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys, Map<Long, Channel> channels,
                      Set<Long> accountChannels, Map<Long, Set<Long>> groupAccounts) {
        this(version, routes, keys, channels, accountChannels, groupAccounts, Map.of());
    }

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys, Map<Long, Channel> channels,
                      Set<Long> accountChannels, Map<Long, Set<Long>> groupAccounts, Map<Long, List<Long>> groupAccountOrders) {
        this(version, routes, keys, channels, accountChannels, groupAccounts, groupAccountOrders, Map.of());
    }

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys, Map<Long, Channel> channels,
                      Set<Long> accountChannels, Map<Long, Set<Long>> groupAccounts, Map<Long, List<Long>> groupAccountOrders,
                      Map<Long, AccountRoutingMode> groupRoutingModes) {
        this.version = version;
        this.groupRoutingModes = Map.copyOf(groupRoutingModes);
        Map<Long, Map<Long, Integer>> positions = new HashMap<>();
        groupAccountOrders.forEach((groupId, order) -> {
            Map<Long, Integer> accounts = new HashMap<>();
            for (int i = 0; i < order.size(); i++) accounts.put(order.get(i), i);
            positions.put(groupId, Map.copyOf(accounts));
        });
        this.groupAccountPositions = Map.copyOf(positions);
        this.groupAccounts = Map.copyOf(groupAccounts);
        this.channels = Map.copyOf(channels);
        this.accountChannels = Set.copyOf(accountChannels);
        Map<String, List<ModelRoute>> byModel = new HashMap<>();
        for (ModelRoute r : routes) {
            byModel.computeIfAbsent(r.virtualModel(), k -> new ArrayList<>()).add(r);
        }
        // 按优先级升序，同优先级内保持声明顺序，便于稳定断言
        byModel.replaceAll((k, v) -> v.stream()
                .sorted(Comparator.comparingInt(r -> r.channel().priority()))
                .toList());
        this.routesByModel = Map.copyOf(byModel);

        Map<String, ApiKeyView> byHash = keys.stream()
                .collect(Collectors.toMap(ApiKeyView::keyHash, k -> k, (a, b) -> a));
        this.keysByHash = Map.copyOf(byHash);
    }

    public Map<Long, Channel> channels() { return channels; }

    public boolean isAccountChannel(long id) { return accountChannels.contains(id); }

    public List<ModelRoute> routes(String name) { return routesByModel.getOrDefault(name, List.of()); }

    public long version() {
        return version;
    }

    public Optional<ApiKeyView> findKey(String rawSecret) {
        return findKeyHash(sha256(rawSecret));
    }

    public Optional<ApiKeyView> findKeyHash(String hash) {
        return Optional.ofNullable(keysByHash.get(hash));
    }

    /**
     * 构建候选集：模型存在 + 渠道启用 + 协议可承接。
     */
    public List<Candidate> candidates(String virtualModel) {
        List<ModelRoute> routes = routesByModel.get(virtualModel);
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        return routes.stream()
                .filter(r -> r.channel().enabled())
                .map(r -> new Candidate(r, r.channel()))
                .toList();
    }

    public AccountRoutingMode accountRoutingMode(long groupId) {
        return groupRoutingModes.getOrDefault(groupId, AccountRoutingMode.ORDER);
    }

    public List<Candidate> candidates(String virtualModel, ApiKeyView key) {
        var available = candidates(virtualModel);
        if (key.accountGroup() == null) {
            return available.stream().filter(c -> java.util.Objects.equals(c.route().accountId(), key.accountId())).toList();
        }
        var eligible = new ArrayList<Candidate>();
        var included = new java.util.HashSet<Long>();
        for (long groupId : key.accountGroupIds()) {
            var members = groupAccounts.getOrDefault(groupId, Set.of());
            if (members.isEmpty()) continue;
            var positions = groupAccountPositions.getOrDefault(groupId, Map.of());
            available.stream()
                    .filter(c -> c.route().accountId() != null && members.contains(c.route().accountId()))
                    .filter(c -> groupId != key.groupId() || key.accountGroup().equals(c.route().accountGroup()))
                    .filter(c -> !included.contains(c.route().id()))
                    .sorted(Comparator.comparingInt((Candidate c) -> positions.getOrDefault(c.route().accountId(), Integer.MAX_VALUE))
                            .thenComparingInt(c -> c.channel().priority())
                            .thenComparingLong(c -> c.route().accountId()))
                    .forEach(c -> {
                        if (included.add(c.route().id())) eligible.add(new Candidate(c.route(), c.channel(), groupId));
                    });
        }
        return List.copyOf(eligible);
    }

    public Set<String> knownModels() {
        return routesByModel.keySet();
    }

    /**
     * 只保留与入口协议一致的候选，用于"快车道"透传判定。
     */
    public static List<Candidate> sameProtocol(List<Candidate> candidates, Protocol ingress) {
        return candidates.stream()
                .filter(c -> c.channel().protocol() == ingress)
                .toList();
    }

    /**
     * 候选：路由 + 渠道的组合。
     */
    public record Candidate(ModelRoute route, Channel channel, Long groupId) {
        public Candidate(ModelRoute route, Channel channel) { this(route, channel, null); }
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
