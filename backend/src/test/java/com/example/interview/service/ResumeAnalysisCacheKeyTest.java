package com.example.interview.service;

import com.example.interview.util.HashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResumeAnalysisService#buildCacheKey} 单元测试（v1.71.3，2026-10-18）。
 *
 * <p>背景：缓存键原本只含「简历文本 + 目标岗位」的哈希。v1.71.2 改了 prompt 之后，
 * 老简历在 TTL 内仍会命中**旧 prompt 产出的旧结果**，表现为「代码改了、线上好像没生效」。
 * 修复方式是把 prompt 版本并入键，本类把这条约定钉死。
 *
 * <p>这里刻意**不 mock 任何东西**：`buildCacheKey` 是纯函数，
 * 直接 new 出来测，比走 Spring 上下文更快也更不容易假绿。
 */
@DisplayName("简历分析缓存键（含 prompt 版本）")
class ResumeAnalysisCacheKeyTest {

    private final ResumeAnalysisService service = new ResumeAnalysisService();

    private static final String USER = "u1";
    private static final String TEXT = "Java 后端三年经验";
    private static final String JOB = "Java 后端";

    @Test
    @DisplayName("键里带分析链路版本段（取自 ANALYSIS_VERSION，避免测试里再抄一份版本号）")
    void key_containsAnalysisVersion() {
        String key = service.buildCacheKey(USER, TEXT, JOB);
        assertThat(key).startsWith("resume:analysis:" + ResumeAnalysisService.ANALYSIS_VERSION + ":");
    }

    @Test
    @DisplayName("关键回归：键**不等于**旧格式（无版本段）——否则改了 prompt 仍会命中旧结果")
    void key_differsFromLegacyFormat() {
        String legacy = "resume:analysis:" + USER + ":" + HashUtil.sha256Short(TEXT + "\u0001" + JOB);
        assertThat(service.buildCacheKey(USER, TEXT, JOB)).isNotEqualTo(legacy);
    }

    @Test
    @DisplayName("同用户同文本同岗位 → 键稳定（否则缓存永远不命中，白烧额度）")
    void key_isStableForSameInput() {
        assertThat(service.buildCacheKey(USER, TEXT, JOB))
                .isEqualTo(service.buildCacheKey(USER, TEXT, JOB));
    }

    @Test
    @DisplayName("userId / 简历文本 / 目标岗位 任一变化都应换键，避免跨用户串扰与答非所问")
    void key_differsWhenAnyInputChanges() {
        String base = service.buildCacheKey(USER, TEXT, JOB);
        assertThat(service.buildCacheKey("u2", TEXT, JOB)).isNotEqualTo(base);
        assertThat(service.buildCacheKey(USER, TEXT + "，熟悉 Redis", JOB)).isNotEqualTo(base);
        assertThat(service.buildCacheKey(USER, TEXT, "前端开发")).isNotEqualTo(base);
    }
}
