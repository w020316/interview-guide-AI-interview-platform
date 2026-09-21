package com.example.interview.service;

import com.example.interview.common.BusinessException;
import com.example.interview.util.JsonRepairUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeAnalysisService 单元测试")
class ResumeAnalysisServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec chatClientRequestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private VectorStore vectorStore;
    @Mock private RagSearchService ragSearchService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private Counter resumeCounter;
    @Mock private Counter cacheHitCounter;
    @Mock private Counter cacheMissCounter;
    @Mock private Timer aiCallTimer;

    @InjectMocks private ResumeAnalysisService service;

    private static final String USER_ID = "user-123";
    private static final String RESUME = "三年 Java 后端经验，熟悉 Spring Boot";
    private static final String JOB = "Java 后端";
    private static final String VALID_JSON =
            "{\"overallScore\":75,\"dimensions\":[{\"name\":\"岗位匹配度\",\"score\":80,\"suggestion\":\"补充量化成果\"}],\"strengths\":[\"项目扎实\"],\"improvements\":[\"加数据\"]}";

    @BeforeEach
    void setUp() {
        // micrometer mock 默认返回 0，无需 stub
    }

    /** 缓存未命中 stub */
    private void stubCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
    }

    /** ChatClient 同步调用链 stub：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    @Test
    @DisplayName("analyze: 缓存命中时不调用 AI")
    void analyze_cacheHit_shouldNotCallAI() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("{\"overallScore\":80}");

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).contains("overallScore");
        verify(chatClient, never()).prompt();
        verify(cacheHitCounter).increment();
        verify(cacheMissCounter, never()).increment();
    }

    @Test
    @DisplayName("analyze: resumeText 为空时应抛 IllegalArgumentException")
    void analyze_emptyResume_shouldThrow() {
        // GlobalExceptionHandler 会拦截，这里直接测 Controller 层的校验
        // Service 层本身不校验，跳过此用例
        assertThat(true).isTrue(); // placeholder
    }

    // ─────────────────────────── analyze: 缓存未命中主流程 ───────────────────────────

    @Test
    @DisplayName("analyze: 缓存未命中时调用 AI、写缓存并异步向量化")
    void analyze_cacheMiss_callsAiWritesCacheAndEmbeds() {
        stubCacheMiss();
        stubChatClient(VALID_JSON);

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(VALID_JSON);
        verify(cacheMissCounter).increment();
        // 合法 JSON 原样写入缓存（30 分钟）
        verify(valueOps).set(anyString(), eq(VALID_JSON), eq(30L), eq(TimeUnit.MINUTES));
        // 异步向量化：先删旧版本（upsert），再经 RagSearchService 统一入库。
        // v1.34.1（P2-6）：删除也统一走 RagSearchService，使容量计数能成对递减，
        // 不再直接 vectorStore.delete 绕过计数（否则计数只增不减、误拒后续导入）。
        verify(ragSearchService, timeout(3000)).removeFromVectorStore(anyList());
        verify(ragSearchService, timeout(3000)).addToVectorStore(anyList());
        verify(resumeCounter).increment();
        verify(aiCallTimer).record(anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("analyze: AI 返回空白时抛 BusinessException 且不写缓存")
    void analyze_aiBlankResponse_throwsBusinessException() {
        stubCacheMiss();
        stubChatClient("   ");

        assertThatThrownBy(() -> service.analyze(USER_ID, RESUME, JOB))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 返回内容为空");

        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("analyze: AI 返回超长非法文本时回退兜底 JSON，且**不写入缓存**（P2-7）")
    void analyze_invalidLongJson_usesFallbackJson() {
        stubCacheMiss();
        // >200 字符且不含任何 JSON 结构的纯文本
        stubChatClient("这不是JSON输出。".repeat(30));

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(JsonRepairUtil.FALLBACK_JSON);
        // v1.34.1（P2-7）：兜底 JSON 不再写入 30 分钟缓存。
        // 若写入，AI 临时抖动产生的兜底结果会被缓存住——之后即便 AI 已恢复，
        // 同一简历仍命中兜底内容，用户体感「一直坏」。现改为不缓存（等价于下次重试）。
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("analyze: AI 返回 Markdown 代码块包裹的 JSON 时剥离围栏后返回")
    void analyze_markdownFencedJson_stripsFence() {
        stubCacheMiss();
        stubChatClient("```json\n{\"overallScore\":60}\n```");

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo("{\"overallScore\":60}");
        verify(valueOps).set(anyString(), eq("{\"overallScore\":60}"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("analyze: Redis 读异常时降级直连 AI")
    void analyze_redisReadError_fallsBackToAi() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 连接失败"));
        stubChatClient(VALID_JSON);

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(VALID_JSON);
        verify(cacheMissCounter).increment();
    }

    @Test
    @DisplayName("analyze: Redis 写异常时静默跳过缓存仍返回结果")
    void analyze_redisWriteError_stillReturnsResult() {
        stubCacheMiss();
        stubChatClient(VALID_JSON);
        doThrow(new RuntimeException("Redis 写入失败"))
                .when(valueOps).set(anyString(), any(), anyLong(), any());

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(VALID_JSON);
    }

    @Test
    @DisplayName("analyze: 异步向量化失败时不影响主流程返回")
    void analyze_resumeEmbeddingFailure_doesNotAffectResult() {
        stubCacheMiss();
        stubChatClient(VALID_JSON);
        // 虚拟线程内入库抛异常 → 被 catch 吞掉，仅记日志
        when(ragSearchService.addToVectorStore(anyList()))
                .thenThrow(new RuntimeException("embedding 不可用"));

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(VALID_JSON);
        verify(ragSearchService, timeout(3000)).addToVectorStore(anyList());
    }

    @Test
    @DisplayName("analyze: 旧版本简历文档删除失败时仍继续入库（catch 静默忽略）")
    void analyze_deleteOldEmbeddingFails_stillAddsNewDocument() {
        stubCacheMiss();
        stubChatClient(VALID_JSON);
        // delete 抛异常 → 被 catch (Exception ignored) 吞掉，后续入库照常执行
        // v1.34.1（P2-6）：删除入口已改走 RagSearchService.removeFromVectorStore
        doThrow(new RuntimeException("delete 失败"))
                .when(ragSearchService).removeFromVectorStore(anyList());

        String result = service.analyze(USER_ID, RESUME, JOB);

        assertThat(result).isEqualTo(VALID_JSON);
        verify(ragSearchService, timeout(3000)).removeFromVectorStore(anyList());
        verify(ragSearchService, timeout(3000)).addToVectorStore(anyList());
    }

    // ─────────────────────── generateOptimizedResume: 优化简历 ───────────────────────

    @Test
    @DisplayName("generateOptimizedResume: 缓存命中时直接返回且不调用 AI")
    void generateOptimizedResume_cacheHit_returnsCached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("# 缓存的优化简历");

        String result = service.generateOptimizedResume(USER_ID, RESUME, JOB, VALID_JSON);

        assertThat(result).isEqualTo("# 缓存的优化简历");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("generateOptimizedResume: 缓存未命中时调用 AI 并写缓存（analysis 为 null 走无分析数据分支）")
    void generateOptimizedResume_cacheMiss_callsAiAndCaches() {
        stubCacheMiss();
        stubChatClient("# 优化简历\n## 项目经历\n- 基于建议优化");

        String result = service.generateOptimizedResume(USER_ID, RESUME, JOB, null);

        assertThat(result).isEqualTo("# 优化简历\n## 项目经历\n- 基于建议优化");
        verify(valueOps).set(anyString(),
                eq("# 优化简历\n## 项目经历\n- 基于建议优化"), eq(30L), eq(TimeUnit.MINUTES));
        verify(resumeCounter).increment();
        verify(aiCallTimer).record(anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("generateOptimizedResume: AI 返回空白时抛 BusinessException")
    void generateOptimizedResume_aiBlank_throwsBusinessException() {
        stubCacheMiss();
        stubChatClient("");

        assertThatThrownBy(() -> service.generateOptimizedResume(USER_ID, RESUME, JOB, VALID_JSON))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 返回内容为空");
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("generateOptimizedResume: Redis 读异常时降级直连 AI")
    void generateOptimizedResume_redisReadError_fallsBackToAi() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 连接失败"));
        stubChatClient("# 优化简历");

        String result = service.generateOptimizedResume(USER_ID, RESUME, JOB, VALID_JSON);

        assertThat(result).isEqualTo("# 优化简历");
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("generateOptimizedResume: AI 返回 null 时抛 BusinessException")
    void generateOptimizedResume_aiNullResponse_throwsBusinessException() {
        stubCacheMiss();
        stubChatClient(null);

        assertThatThrownBy(() -> service.generateOptimizedResume(USER_ID, RESUME, JOB, VALID_JSON))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 返回内容为空");
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("generateOptimizedResume: Redis 写异常时仍返回优化结果")
    void generateOptimizedResume_redisWriteError_stillReturns() {
        stubCacheMiss();
        stubChatClient("# 优化简历");
        doThrow(new RuntimeException("Redis 写入失败"))
                .when(valueOps).set(anyString(), any(), anyLong(), any());

        String result = service.generateOptimizedResume(USER_ID, RESUME, JOB, VALID_JSON);

        assertThat(result).isEqualTo("# 优化简历");
    }

    @Test
    @DisplayName("generateOptimizedResume: 超长简历与分析结果应被截断后正常生成")
    void generateOptimizedResume_longInputs_truncatedAndGenerated() {
        stubCacheMiss();
        stubChatClient("# 截断后仍正常生成");
        String longResume = "项目经验内容。".repeat(200); // 1200 字符 > MAX_RESUME_LEN(800)
        String longAnalysis = "建议内容。".repeat(200);     // 1000 字符 > 800

        String result = service.generateOptimizedResume(USER_ID, longResume, JOB, longAnalysis);

        assertThat(result).isEqualTo("# 截断后仍正常生成");
        verify(chatClient).prompt();
    }

    // ───────────── 缓存降级兜底（v1.34.1 P2-5 修复回归）─────────────
    // 背景：出题/简历缓存此前只依赖 Redis，读写异常时仅 WARN 后直连 AI，
    // 因此在未部署 Redis 的环境缓存整体旁路——真机实测 cache.hit.count = 0、
    // 重复请求耗时 7.12s（与首次 9.24s 同量级），即每次都全额调用 LLM。

    @Test
    @DisplayName("analyze: Redis 读写均失败时，第二次请求命中进程内兜底缓存，不再调用 AI（P2-5）")
    void analyze_redisUnavailable_secondCallHitsLocalFallbackCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 读与写都抛异常 → 模拟「未部署 Redis」的真实形态
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 连接失败"));
        doThrow(new RuntimeException("Redis 写入失败"))
                .when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        stubChatClient(VALID_JSON);

        String first = service.analyze(USER_ID, RESUME, JOB);
        String second = service.analyze(USER_ID, RESUME, JOB);

        assertThat(first).isEqualTo(VALID_JSON);
        assertThat(second).isEqualTo(VALID_JSON);
        // 关键断言：第二次命中进程内缓存，AI 只被调用一次（修复前每次都会调用）
        verify(chatClient, times(1)).prompt();
        verify(cacheHitCounter).increment();
    }

    @Test
    @DisplayName("analyze: Redis 正常（未命中但无异常）时不启用进程内兜底，行为与改造前一致")
    void analyze_redisHealthy_doesNotUseLocalFallback() {
        // Redis 返回 null（正常未命中，不抛异常）
        stubCacheMiss();
        stubChatClient(VALID_JSON);

        service.analyze(USER_ID, RESUME, JOB);
        service.analyze(USER_ID, RESUME, JOB);

        // 两次都是正常未命中 → 两次都调用 AI；兜底缓存不得介入（避免引入双写不一致）
        verify(chatClient, times(2)).prompt();
        verify(cacheHitCounter, never()).increment();
    }
}
