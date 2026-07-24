package com.example.interview.controller;

import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KnowledgeController} MockMvc 集成测试
 *
 * <p>覆盖 8 个端点：search/ask/import/import-batch/wrong-questions/question-summary/recent-questions。
 * 所有端点均需认证（Controller 内 {@code currentUserId()} 读取 SecurityContext）。
 *
 * <p>关键点：
 * <ul>
 *   <li>@MockBean 隔离 RagSearchService / VectorStore / InterviewSessionService</li>
 *   <li>@MockBean RateLimitInterceptor 绕过 10 次/分钟限流</li>
 *   <li>@MockBean JwtUtil：@WebMvcTest 拾取 JwtAuthFilter 但不拾取 JwtUtil</li>
 * </ul>
 */
@WebMvcTest(controllers = KnowledgeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RagSearchService ragSearchService;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private InterviewSessionService sessionService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("GET /api/knowledge/search 语义检索")
    class Search {

        @Test
        @DisplayName("合法入参返回 200 + 检索结果")
        void search_validInput_returns200() throws Exception {
            when(ragSearchService.search("HashMap 原理", 5, USER_ID))
                    .thenReturn("检索结果文本");

            mockMvc.perform(get("/api/knowledge/search")
                            .param("query", "HashMap 原理")
                            .param("topK", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("topK 缺失时使用默认值 5 仍返回 200")
        void search_missingTopK_defaultsTo5() throws Exception {
            when(ragSearchService.search("Spring", 5, USER_ID))
                    .thenReturn("结果");

            mockMvc.perform(get("/api/knowledge/search")
                            .param("query", "Spring"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("POST /api/knowledge/ask RAG 问答")
    class Ask {

        @Test
        @DisplayName("问题为空返回 400")
        void ask_emptyQuestion_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("question", ""));

            mockMvc.perform(post("/api/knowledge/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("问题不能为空"));
        }

        @Test
        @DisplayName("问题缺失返回 400")
        void ask_missingQuestion_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/knowledge/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("问题不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + RAG 回答")
        void ask_validInput_returns200() throws Exception {
            when(ragSearchService.answerWithRag("HashMap 原理", USER_ID))
                    .thenReturn("HashMap 基于哈希表实现…");

            String body = objectMapper.writeValueAsString(Map.of("question", "HashMap 原理"));

            mockMvc.perform(post("/api/knowledge/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/knowledge/import 简单导入")
    class Import {

        @Test
        @DisplayName("文档列表缺失返回 400")
        void import_missingDocuments_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/knowledge/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文档列表不能为空"));
        }

        @Test
        @DisplayName("文档列表为空返回 400")
        void import_emptyDocuments_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("documents", List.of()));

            mockMvc.perform(post("/api/knowledge/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文档列表不能为空"));
        }

        @Test
        @DisplayName("文档数超过 100 返回 400")
        void import_over100Documents_returns400() throws Exception {
            // 构造 101 个文档
            List<String> docs = java.util.stream.Stream.generate(() -> "doc").limit(101).toList();
            String body = objectMapper.writeValueAsString(Map.of("documents", docs));

            mockMvc.perform(post("/api/knowledge/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("单次最多导入 100 条文档"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 导入数量")
        void import_validInput_returns200() throws Exception {
            when(ragSearchService.importKnowledge(List.of("文档1", "文档2"), USER_ID))
                    .thenReturn(2);

            String body = objectMapper.writeValueAsString(Map.of("documents", List.of("文档1", "文档2")));

            mockMvc.perform(post("/api/knowledge/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("成功导入 2 条文档（请求 2 条）"));
        }
    }

    @Nested
    @DisplayName("POST /api/knowledge/import/batch 批量导入分块")
    class BatchImport {

        @Test
        @DisplayName("chunks 缺失返回 400")
        void batchImport_missingChunks_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/knowledge/import/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("chunks 不能为空"));
        }

        @Test
        @DisplayName("chunks 为空返回 400")
        void batchImport_emptyChunks_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("chunks", List.of()));

            mockMvc.perform(post("/api/knowledge/import/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("chunks 不能为空"));
        }

        @Test
        @DisplayName("chunks 超过 100 返回 400")
        void batchImport_over100Chunks_returns400() throws Exception {
            List<String> chunks = java.util.stream.Stream.generate(() -> "chunk").limit(101).toList();
            String body = objectMapper.writeValueAsString(Map.of("chunks", chunks));

            mockMvc.perform(post("/api/knowledge/import/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("单次最多导入 100 个分块"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 导入数量")
        void batchImport_validInput_returns200() throws Exception {
            // vectorStore.add 返回 void，无需 mock
            String body = objectMapper.writeValueAsString(Map.of(
                    "category", "Spring", "chunks", List.of("chunk1", "chunk2")));

            mockMvc.perform(post("/api/knowledge/import/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.imported").value(2))
                    .andExpect(jsonPath("$.data.category").value("Spring"));
        }
    }

    @Nested
    @DisplayName("GET /api/knowledge/wrong-questions 错题总结")
    class WrongQuestions {

        @Test
        @DisplayName("threshold 超出范围返回 400")
        void wrongQuestions_thresholdOutOfRange_returns400() throws Exception {
            mockMvc.perform(get("/api/knowledge/wrong-questions")
                            .param("threshold", "150"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("threshold 必须在 0-100 之间"));
        }

        @Test
        @DisplayName("threshold 为负数返回 400")
        void wrongQuestions_negativeThreshold_returns400() throws Exception {
            mockMvc.perform(get("/api/knowledge/wrong-questions")
                            .param("threshold", "-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("threshold 必须在 0-100 之间"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 错题列表")
        void wrongQuestions_validInput_returns200() throws Exception {
            InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？")
                    .category("Java 基础").difficulty("EASY")
                    .evaluationScore(50).build();
            when(sessionService.listWrongQuestionsByUser(USER_ID, 60))
                    .thenReturn(List.of(q));
            when(sessionService.listByUser(USER_ID))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/knowledge/wrong-questions")
                            .param("threshold", "60"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.threshold").value(60));
        }
    }

    @Nested
    @DisplayName("GET /api/knowledge/question-summary 题目汇总")
    class QuestionSummary {

        @Test
        @DisplayName("返回 200 + 汇总数据")
        void questionSummary_returns200() throws Exception {
            when(sessionService.questionSummary(USER_ID))
                    .thenReturn(Map.of("totalQuestions", 10, "answeredQuestions", 8));

            mockMvc.perform(get("/api/knowledge/question-summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalQuestions").value(10));
        }
    }

    @Nested
    @DisplayName("GET /api/knowledge/recent-questions 最近题目")
    class RecentQuestions {

        @Test
        @DisplayName("合法入参返回 200 + 最近题目")
        void recentQuestions_validInput_returns200() throws Exception {
            InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？").build();
            when(sessionService.listAllQuestionsByUser(USER_ID))
                    .thenReturn(List.of(q));

            mockMvc.perform(get("/api/knowledge/recent-questions")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(1));
        }

        @Test
        @DisplayName("limit 超出 100 时夹紧为 10 仍返回 200")
        void recentQuestions_limitOver100_clampedTo10() throws Exception {
            when(sessionService.listAllQuestionsByUser(USER_ID))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/knowledge/recent-questions")
                            .param("limit", "200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(0));
        }
    }
}
