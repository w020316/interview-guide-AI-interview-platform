package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 知识库接口
 * - RAG 检索/问答/导入
 * - 关联模拟面试：错题总结、题目汇总
 */
@Tag(name = "知识库", description = "RAG 检索、问答、批量导入、错题总结、题目汇总")
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired private RagSearchService ragSearchService;
    @Autowired private InterviewSessionService sessionService;
    /**
     * RAG 健康跟踪（v1.34.1）：用于向用户暴露「知识库是否可用」。
     * {@code required = false} + 空值保护，避免切片单测未声明该 mock 时 NPE。
     */
    @Autowired(required = false) private com.example.interview.service.RagHealthTracker ragHealthTracker;

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    // ─────────────────────────────── RAG 知识库 ───────────────────────────────

    @Operation(summary = "知识库语义检索")
    @GetMapping("/search")
    public Result<String> search(@RequestParam String query,
                                 @RequestParam(defaultValue = "5") int topK) {
        return Result.success(ragSearchService.search(query, topK, currentUserId()));
    }

    @Operation(summary = "RAG 增强问答")
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) return Result.error(400, "问题不能为空");
        return Result.success(ragSearchService.answerWithRag(question, currentUserId()));
    }

    @Operation(summary = "导入知识文档（简单模式，按用户隔离）")
    @PostMapping("/import")
    public Result<String> importKnowledge(@RequestBody Map<String, List<String>> request) {        List<String> documents = request.get("documents");
        if (documents == null || documents.isEmpty()) return Result.error(400, "文档列表不能为空");
        // 限制单次导入数量，防止滥用
        if (documents.size() > 100) return Result.error(400, "单次最多导入 100 条文档");
        int imported = ragSearchService.importKnowledge(documents, currentUserId());
        return Result.success("成功导入 " + imported + " 条文档（请求 " + documents.size() + " 条）");
    }

    /**
     * 批量导入 Markdown 知识分块（P1，按用户隔离）
     * POST /api/knowledge/import/batch
     * Body: {"category":"Spring","chunks":["内容1","内容2"]}
     */
    @Operation(summary = "批量导入 Markdown 知识分块并向量化（按用户隔离）")
    @PostMapping("/import/batch")
    public Result<Map<String, Object>> batchImport(@RequestBody Map<String, Object> request) {
        String category = (String) request.getOrDefault("category", "通用");
        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) request.get("chunks");
        if (chunks == null || chunks.isEmpty()) return Result.error(400, "chunks 不能为空");
        // 限制单次导入数量
        if (chunks.size() > 100) return Result.error(400, "单次最多导入 100 个分块");

        String userId = currentUserId();
        List<Document> docs = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            // 限制单个分块大小（8KB），防止超大文本拖慢向量化
            String safeChunk = chunk.length() > 8192 ? chunk.substring(0, 8192) : chunk;
            docs.add(Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(safeChunk)
                    .metadata(Map.of("category", category, "source", "batch-import", "userId", userId))
                    .build());
        }
        // P1-04：经统一入库入口，受 maxDocuments 容量计数保护（此前直接 add 绕过保护，
        // 生产内存向量库会被批量导入撑爆）
        // U1：向量化依赖 embedding 服务，不可用时以 503 业务语义返回明确文案（替代 500）
        int stored;
        try {
            stored = ragSearchService.addToVectorStore(docs);
        } catch (Exception e) {
            throw new com.example.interview.common.BusinessException(
                    "AI 服务暂时不可用，知识导入失败，请稍后重试");
        }
        return Result.success(Map.of("imported", stored, "category", category));
    }

    /**
     * 知识库可用状态（v1.34.1，UX P3-5）
     *
     * <p><b>为什么需要</b>：知识库是一条依赖外部 Embedding 的服务链。当 Embedding 的 Key
     * 未配置/失效/欠费时，检索恒返回空、问答只能靠通用知识作答、导入直接失败——但从用户视角看
     * 「知识库」页面看起来一切正常，只是**永远搜不到东西**，无从判断是自己没导入还是服务有问题
     * （2026-09-21 生产实测即为此状态，且应用健康检查仍报 UP）。
     *
     * <p>本接口只返回**面向用户的粗粒度状态**，刻意不暴露堆内存/Redis/JVM 等基础设施细节
     * （那些保留在需认证的 {@code /api/health/detail}）：
     * <ul>
     *   <li>{@code available}：知识库当前是否可用；</li>
     *   <li>{@code documents}：已收录的知识条数（用户可据此判断「库是不是空的」）；</li>
     *   <li>{@code notice}：不可用时给用户看的一句人话解释。</li>
     * </ul>
     */
    @Operation(summary = "知识库可用状态（面向用户，不暴露基础设施细节）")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        int documents = ragSearchService.storedCount();
        Map<String, Object> rag = ragHealthTracker == null ? null
                : ragHealthTracker.snapshot(documents, ragSearchService.maxDocuments());

        String health = rag == null ? "UNKNOWN" : String.valueOf(rag.getOrDefault("status", "UNKNOWN"));
        // 对用户而言「播种失败」与「运行期向量化失败」都是同一件事：库不可用
        boolean available = "UP".equals(health) || "UNKNOWN".equals(health);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", available);
        data.put("documents", documents);
        if (!available) {
            data.put("notice", "知识库暂时不可用，AI 问答将基于通用知识作答；"
                    + "你仍可正常提问，也可稍后在「知识导入」中重试。");
        }
        return Result.success(data);
    }

    // ─────────────────────────── 关联模拟面试 ───────────────────────────

    /**
     * 错题总结：查询当前用户所有评分低于阈值的题目
     * GET /api/knowledge/wrong-questions?threshold=60
     *
     * 返回题目详情 + 关联会话的岗位描述，便于用户回顾薄弱点
     */
    @Operation(summary = "错题总结：查询评分低于阈值的题目（关联模拟面试）")
    @GetMapping("/wrong-questions")
    public Result<Map<String, Object>> wrongQuestions(@RequestParam(defaultValue = "60") int threshold) {
        if (threshold < 0 || threshold > 100) {
            return Result.error(400, "threshold 必须在 0-100 之间");
        }
        String userId = currentUserId();
        List<InterviewQuestionEntity> wrong = sessionService.listWrongQuestionsByUser(userId, threshold);

        // 查询关联会话，构建 sessionId -> jobDescription 映射
        List<InterviewSessionEntity> sessions = sessionService.listByUser(userId);
        Map<String, String> sessionJobMap = sessions.stream()
                .collect(Collectors.toMap(
                        InterviewSessionEntity::getSessionId,
                        s -> s.getJobDescription() != null ? s.getJobDescription() : "未指定岗位",
                        (a, b) -> a));

        // 组装返回：题目 + 岗位
        List<Map<String, Object>> items = wrong.stream().map(q -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.getId());
            item.put("question", q.getQuestion());
            item.put("category", q.getCategory());
            item.put("difficulty", q.getDifficulty());
            item.put("userAnswer", q.getUserAnswer());
            item.put("referenceAnswer", q.getReferenceAnswer());
            item.put("evaluationScore", q.getEvaluationScore());
            item.put("sessionId", q.getSessionId());
            item.put("jobDescription", sessionJobMap.getOrDefault(q.getSessionId(), "未指定岗位"));
            item.put("createdAt", q.getCreatedAt());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("threshold", threshold);
        result.put("questions", items);
        return Result.success(result);
    }

    /**
     * 题目汇总：按分类、难度聚合统计
     * GET /api/knowledge/question-summary
     *
     * 返回：
     * - totalQuestions / answeredQuestions / wrongQuestions / averageScore
     * - byCategory: [{category, total, answered, wrong, avgScore}]
     * - byDifficulty: [{difficulty, total, answered, wrong, avgScore}]
     */
    @Operation(summary = "题目汇总：按分类/难度统计答题情况（关联模拟面试）")
    @GetMapping("/question-summary")
    public Result<Map<String, Object>> questionSummary() {
        String userId = currentUserId();
        return Result.success(sessionService.questionSummary(userId));
    }

    /**
     * 最近题目：查询当前用户最近的题目（用于知识库快速回顾）
     * GET /api/knowledge/recent-questions?limit=10
     */
    @Operation(summary = "最近题目：查询当前用户最近的面试题")
    @GetMapping("/recent-questions")
    public Result<Map<String, Object>> recentQuestions(@RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 100) limit = 10;
        String userId = currentUserId();
        // P1/S-02（2026-09-20）：分页与计数下推数据库。此前全量加载后内存 limit，
        // 且 total 返回被截断后的条数（=min(limit,总数)），分页语义失真；
        // 现在 total 为该用户题目真实总数，questions 为当前页。
        Page<InterviewQuestionEntity> page = sessionService.listRecentQuestionsByUser(userId, limit);

        List<Map<String, Object>> items = page.getContent().stream()
                .map(q -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", q.getId());
                    item.put("question", q.getQuestion());
                    item.put("category", q.getCategory());
                    item.put("difficulty", q.getDifficulty());
                    item.put("evaluationScore", q.getEvaluationScore());
                    item.put("sessionId", q.getSessionId());
                    item.put("createdAt", q.getCreatedAt());
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotalElements());
        result.put("questions", items);
        return Result.success(result);
    }
}
