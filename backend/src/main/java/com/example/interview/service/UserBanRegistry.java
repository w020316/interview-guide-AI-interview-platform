package com.example.interview.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户禁用注册表（进程内，v1.31.4 管理后台）
 *
 * <p>生产库由外部管理 schema（ddl-auto:none），为避免加列迁移风险，用户禁用采用进程内集合实现：
 * 被禁用的用户 ID 立即拒绝登录与已有 token 访问；应用重启后需重新设置（用于"临时封禁"）。
 *
 * <p>并发安全：ConcurrentHashMap 保证可见性；禁用/解禁即时生效。
 */
@Component
public class UserBanRegistry {

    private final ConcurrentHashMap.KeySetView<Long, Boolean> bannedIds = ConcurrentHashMap.newKeySet();

    /** 禁用用户（幂等） */
    public void ban(Long userId) {
        if (userId != null) {
            bannedIds.add(userId);
        }
    }

    /** 解禁用户（幂等） */
    public void unban(Long userId) {
        if (userId != null) {
            bannedIds.remove(userId);
        }
    }

    /** 是否已禁用 */
    public boolean isBanned(Long userId) {
        return userId != null && bannedIds.contains(userId);
    }

    /** 当前禁用用户 ID 集合（只读快照） */
    public Set<Long> bannedSnapshot() {
        return Set.copyOf(bannedIds);
    }
}