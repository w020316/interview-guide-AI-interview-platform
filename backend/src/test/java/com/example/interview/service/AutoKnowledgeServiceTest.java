package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 知识自动补充服务的守卫逻辑测试（v1.34.0）
 *
 * 重点验证「不该补充时不补充」——自动补充会额外调用一次 LLM，
 * 异常流量（爬虫/压测/误粘贴长文）若每次都触发，会变成成本黑洞。
 * 因此限流与入参过滤是必须固化的行为。
 */
class AutoKnowledgeServiceTest {

    private static final long SETTLE_MS = 400;

    private AutoKnowledgeService newService(RagSearchService rag, boolean enabled, int perHour) {
        AutoKnowledgeService svc = new AutoKnowledgeService(rag, mock(ChatClient.class), new ObjectMapper());
        ReflectionTestUtils.setField(svc, "enabled", enabled);
        ReflectionTestUtils.setField(svc, "perHourLimit", perHour);
        return svc;
    }

    /** 给异步线程留出足够时间暴露问题（若守卫失效，会观察到向量库被写入） */
    private void settle() throws InterruptedException {
        Thread.sleep(SETTLE_MS);
    }

    @Test
    @DisplayName("开关关闭时完全不触发（不投递任务、不调用向量库）")
    void disabledDoesNothing() throws Exception {
        RagSearchService rag = mock(RagSearchService.class);
        AutoKnowledgeService svc = newService(rag, false, 30);

        svc.supplementAsync("化工 HAZOP 分析方法论");
        settle();

        verifyNoInteractions(rag);
    }

    @Test
    @DisplayName("过短提问不补充（如「你好」无沉淀价值）")
    void tooShortQuestionSkipped() throws Exception {
        RagSearchService rag = mock(RagSearchService.class);
        AutoKnowledgeService svc = newService(rag, true, 30);

        svc.supplementAsync("你好");
        settle();

        verifyNoInteractions(rag);
    }

    @Test
    @DisplayName("超长提问不补充（多为误粘贴简历正文，且会污染知识库）")
    void tooLongQuestionSkipped() throws Exception {
        RagSearchService rag = mock(RagSearchService.class);
        AutoKnowledgeService svc = newService(rag, true, 30);

        svc.supplementAsync("x".repeat(500));
        settle();

        verifyNoInteractions(rag);
    }

    @Test
    @DisplayName("null 提问安全跳过，不抛异常")
    void nullQuestionSafe() {
        RagSearchService rag = mock(RagSearchService.class);
        AutoKnowledgeService svc = newService(rag, true, 30);

        assertDoesNotThrow(() -> svc.supplementAsync(null));
    }

    @Test
    @DisplayName("超出每小时配额后静默跳过（成本保护）")
    void quotaExceededSkips() throws Exception {
        RagSearchService rag = mock(RagSearchService.class);
        // 配额 0：任何提问都应被挡下
        AutoKnowledgeService svc = newService(rag, true, 0);

        svc.supplementAsync("医疗器械注册流程有哪些关键环节");
        settle();

        verifyNoInteractions(rag);
    }

    @Test
    @DisplayName("守卫通过时应投递任务而非抛异常（不阻塞调用方）")
    void validQuestionEnqueuesWithoutThrowing() {
        RagSearchService rag = mock(RagSearchService.class);
        AutoKnowledgeService svc = newService(rag, true, 30);

        // 关键契约：调用方在回答用户提问的同步链路上触发本方法，
        // 因此必须立即返回 —— 真正的 LLM 生成与入库发生在后台线程。
        long start = System.currentTimeMillis();
        assertDoesNotThrow(() -> svc.supplementAsync("医疗器械注册流程有哪些关键环节"));
        long cost = System.currentTimeMillis() - start;

        org.junit.jupiter.api.Assertions.assertTrue(cost < 200,
                "supplementAsync 必须立即返回（实测 " + cost + "ms），否则会拖慢用户问答时延");
    }
}
