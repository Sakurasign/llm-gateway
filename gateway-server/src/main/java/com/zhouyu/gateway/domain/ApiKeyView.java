package com.zhouyu.gateway.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.zhouyu.gateway.quota.KeySpend.Amounts;

/**
 * 客户端 API Key 的鉴权视图（只含校验所需的非敏感字段）。
 *
 * @param id             Key ID
 * @param keyHash        sha256(secret) 的十六进制
 * @param tenantId       租户 ID
 * @param groupId        分组 ID
 * @param allowedModels  允许的虚拟模型，null 或空表示全部
 * @param rpmLimit       每分钟请求上限，null 表示不限
 * @param dailyQuotaMicro 日额度（micro-USD），null 表示不限
 */
public record ApiKeyView(
        long id,
        String keyHash,
        long tenantId,
        long groupId,
        Set<String> allowedModels,
        Integer rpmLimit,
        Long dailyQuotaMicro,
        Long accountId,
        String accountGroup,
        IpRules ipRules,
        Long expiresAt,
        Amounts spendLimits,
        List<Long> extraGroupIds
) {

    public ApiKeyView(long id, String keyHash, long tenantId, long groupId, Set<String> allowedModels, Integer rpmLimit, Long dailyQuotaMicro) {
        this(id, keyHash, tenantId, groupId, allowedModels, rpmLimit, dailyQuotaMicro, null, null, IpRules.NONE, null, Amounts.ZERO);
    }

    public ApiKeyView(long id, String keyHash, long tenantId, long groupId, Set<String> allowedModels, Integer rpmLimit,
                      Long dailyQuotaMicro, Long accountId, String accountGroup, IpRules ipRules) {
        this(id, keyHash, tenantId, groupId, allowedModels, rpmLimit, dailyQuotaMicro, accountId, accountGroup, ipRules, null, Amounts.ZERO);
    }

    public ApiKeyView {
        extraGroupIds = List.copyOf(extraGroupIds);
    }

    public ApiKeyView(long id, String keyHash, long tenantId, long groupId, Set<String> allowedModels, Integer rpmLimit,
                      Long dailyQuotaMicro, Long accountId, String accountGroup, IpRules ipRules, Long expiresAt, Amounts spendLimits) {
        this(id, keyHash, tenantId, groupId, allowedModels, rpmLimit, dailyQuotaMicro, accountId, accountGroup, ipRules,
                expiresAt, spendLimits, List.of());
    }

    public List<Long> accountGroupIds() {
        if (accountGroup == null) return List.of();
        if (extraGroupIds.isEmpty()) return List.of(groupId);
        var ids = new ArrayList<Long>();
        ids.add(groupId);
        ids.addAll(extraGroupIds);
        return List.copyOf(ids);
    }

    public boolean expired() {
        return expiresAt != null && expiresAt <= java.time.Instant.now().getEpochSecond();
    }

    public boolean managed() { return accountId != null || accountGroup != null; }

    public boolean allowsModel(String virtualModel) {
        return allowedModels == null || allowedModels.isEmpty() || allowedModels.contains(virtualModel);
    }
}
