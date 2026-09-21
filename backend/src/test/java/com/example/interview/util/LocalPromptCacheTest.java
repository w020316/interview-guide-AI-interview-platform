package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalPromptCache} 单元测试（v1.34.1，P2-5 缓存降级兜底）
 *
 * <p>覆盖：命中/未命中、TTL 过期、容量上限 LRU 淘汰、空值安全、计数。
 */
@DisplayName("进程内兜底缓存测试")
class LocalPromptCacheTest {

    @Test
    @DisplayName("put 后 get 命中并返回原值；未知 key 返回 null")
    void putThenGet_hits() {
        LocalPromptCache cache = new LocalPromptCache(10);

        cache.put("k1", "v1", 60_000L);

        assertThat(cache.get("k1")).isEqualTo("v1");
        assertThat(cache.get("nope")).isNull();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 过期后 get 返回 null，且过期项被移除（不占容量）")
    void expiredEntry_returnsNullAndIsRemoved() {
        LocalPromptCache cache = new LocalPromptCache(10);
        // 用极小 TTL 触发过期；sleep 1ms 后必然超过
        cache.put("k1", "v1", 1L);
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(cache.get("k1")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("超过容量上限时淘汰最久未访问的条目（LRU），容量恒定")
    void lruEviction_keepsSizeBounded() {
        LocalPromptCache cache = new LocalPromptCache(3);
        cache.put("a", "1", 60_000L);
        cache.put("b", "2", 60_000L);
        cache.put("c", "3", 60_000L);
        // 访问 a，使其成为最近使用；此时最久未用是 b
        assertThat(cache.get("a")).isEqualTo("1");

        cache.put("d", "4", 60_000L);   // 触发淘汰

        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get("b")).as("最久未访问的 b 应被淘汰").isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("d")).isEqualTo("4");
    }

    @Test
    @DisplayName("非法入参被忽略：null key/value、非正 TTL 都不写入")
    void invalidArgs_ignored() {
        LocalPromptCache cache = new LocalPromptCache(10);

        cache.put(null, "v", 1000L);
        cache.put("k", null, 1000L);
        cache.put("k", "v", 0L);
        cache.put("k", "v", -5L);

        assertThat(cache.size()).isZero();
        assertThat(cache.get(null)).isNull();
    }

    @Test
    @DisplayName("clear 清空全部条目；容量参数非法时回退默认值")
    void clearAndDefaultCapacity() {
        LocalPromptCache cache = new LocalPromptCache(0);   // 非法容量 → 回退默认 500
        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, "v" + i, 60_000L);
        }
        assertThat(cache.size()).isEqualTo(10);

        cache.clear();
        assertThat(cache.size()).isZero();
    }
}
