package com.example.interview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserBanRegistry} 单元测试（P2-03：Redis 持久化 + 内存降级）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserBanRegistry 单元测试")
class UserBanRegistryTest {

    private static final String REDIS_KEY = "interview-guide:banned-users";

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private SetOperations<String, Object> setOps;
    @Mock private com.example.interview.repository.UserRepository userRepository;

    private UserBanRegistry newRegistry(boolean withRedis) {
        UserBanRegistry registry = new UserBanRegistry();
        if (withRedis) {
            ReflectionTestUtils.setField(registry, "redisTemplate", redisTemplate);
            lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        }
        return registry;
    }

    @Test
    @DisplayName("启动时从 Redis 恢复封禁名单")
    void loadFromRedis_restoresBannedIds() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(REDIS_KEY)).thenReturn(Set.of(1L, 2L));
        UserBanRegistry registry = newRegistry(true);
        registry.loadFromRedis();

        assertThat(registry.isBanned(1L)).isTrue();
        assertThat(registry.isBanned(2L)).isTrue();
        assertThat(registry.isBanned(3L)).isFalse();
    }

    @Test
    @DisplayName("Redis 恢复失败时降级为空名单，不抛异常")
    void loadFromRedis_redisDown_fallsBackToEmpty() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(REDIS_KEY)).thenThrow(new IllegalStateException("redis down"));
        UserBanRegistry registry = newRegistry(true);
        registry.loadFromRedis();

        assertThat(registry.isBanned(1L)).isFalse();
    }

    @Test
    @DisplayName("ban/unban 写穿透 Redis")
    void banAndUnban_writeThrough() {
        UserBanRegistry registry = newRegistry(true);
        registry.loadFromRedis();

        registry.ban(7L);
        verify(setOps).add(eq(REDIS_KEY), eq(7L));
        assertThat(registry.isBanned(7L)).isTrue();

        registry.unban(7L);
        verify(setOps).remove(eq(REDIS_KEY), eq(7L));
        assertThat(registry.isBanned(7L)).isFalse();
    }

    @Test
    @DisplayName("Redis 写失败时进程内状态仍生效")
    void ban_redisWriteFails_inMemoryStillWorks() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        doThrow(new IllegalStateException("redis down")).when(setOps).add(any(), any());
        UserBanRegistry registry = newRegistry(true);
        registry.loadFromRedis();

        registry.ban(9L);
        assertThat(registry.isBanned(9L)).isTrue();
    }

    @Test
    @DisplayName("无 Redis（bean 缺失）时为纯进程内实现")
    void withoutRedis_pureInMemory() {
        UserBanRegistry registry = newRegistry(false);
        registry.loadFromRedis();
        registry.ban(5L);
        assertThat(registry.isBanned(5L)).isTrue();
        assertThat(registry.bannedSnapshot()).containsExactly(5L);
    }

    // ── P2-14：DB 是事实，Redis/内存是缓存 ──

    private UserBanRegistry newRegistryWithDb() {
        UserBanRegistry registry = new UserBanRegistry();
        ReflectionTestUtils.setField(registry, "userRepository", userRepository);
        return registry;
    }

    @Test
    @DisplayName("P2-14：启动时从数据库恢复封禁名单（Redis 缺失也能恢复）")
    void loadFromDb_restoresBannedIds() {
        when(userRepository.findBannedIds()).thenReturn(java.util.List.of(11L, 12L));
        UserBanRegistry registry = newRegistryWithDb();

        registry.loadFromRedis();

        assertThat(registry.isBanned(11L)).isTrue();
        assertThat(registry.isBanned(12L)).isTrue();
        assertThat(registry.isBanned(13L)).isFalse();
    }

    @Test
    @DisplayName("P2-14：ban/unban 写穿透数据库（重启后仍生效）")
    void banAndUnban_persistToDb() {
        UserBanRegistry registry = newRegistryWithDb();
        registry.loadFromRedis();

        registry.ban(21L);
        verify(userRepository).updateBanned(21L, true);

        registry.unban(21L);
        verify(userRepository).updateBanned(21L, false);
    }

    @Test
    @DisplayName("P2-14：数据库不可用 / 缺 banned 列时降级为内存，不阻断启动")
    void dbFailure_degradesToMemory() {
        when(userRepository.findBannedIds()).thenThrow(new IllegalStateException("column banned does not exist"));
        UserBanRegistry registry = newRegistryWithDb();

        registry.loadFromRedis();
        registry.ban(31L);

        assertThat(registry.isBanned(31L)).isTrue();
    }
}
