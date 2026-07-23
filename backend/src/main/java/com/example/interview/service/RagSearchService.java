package com.example.interview.service;

import com.example.interview.util.PromptSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库服务
 * - 向量检索面试八股文（按用户隔离）
 * - 构建带上下文的回答
 * - 通过 ObjectMapper 输出 JSON，避免手工拼接导致的安全/转义问题
 *
 * v1.8 起：所有 search/ask/import 均携带 userId，写入 metadata 并在检索时过滤
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    /** metadata 中用户隔离字段名 */
    private static final String META_USER_ID = "userId";
    /** metadata 中共享知识标记（系统预置数据无 userId，标记为 shared） */
    private static final String META_SHARED = "shared";
    /**
     * 去重相似度阈值：相似度 >= 此值视为重复文档，跳过导入
     * 0.90 对应 cosine distance <= 0.10，覆盖文本微调后重复的场景
     */
    private static final double DEDUP_SIMILARITY_THRESHOLD = 0.90;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 检索相关知识点（返回 JSON 数组字符串）
     * v1.8：按 userId 隔离，仅返回该用户导入的 + 系统预置共享文档
     *
     * @param query  查询文本
     * @param topK   返回条数（1-50）
     * @param userId 当前用户 ID（用于隔离）
     */
    public String search(String query, int topK, String userId) {
        if (query == null || query.isBlank()) {
            return "[]";
        }
        int safeK = Math.max(1, Math.min(topK, 50));

        try {
            // 过滤条件：userId = 当前用户 OR shared = true
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            SearchRequest.Builder reqBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(safeK)
                    .filterExpression(b.or(
                            b.eq(META_USER_ID, userId),
                            b.eq(META_SHARED, "true")
                    ).build());

            List<Document> docs = vectorStore.similaritySearch(reqBuilder.build());

            if (docs == null || docs.isEmpty()) {
                return "[]";
            }

            List<Map<String, Object>> result = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", doc.getId());
                item.put("content", doc.getText() == null ? "" : doc.getText());
                item.put("score", doc.getMetadata().getOrDefault("distance", 0.0));
                result.add(item);
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("RAG 检索失败 query='{}'：{}", query, e.getMessage());
            return "[]";
        }
    }

    /**
     * 构建带上下文的回答（按 userId 隔离检索）
     * - 失败时返回兜底提示，避免返回 null 让前端崩
     *
     * @param question 用户问题
     * @param userId   当前用户 ID
     */
    public String answerWithRag(String question, String userId) {
        if (question == null || question.isBlank()) {
            return "问题不能为空";
        }

        // 1. 检索相关知识（按 userId 隔离）
        String relatedKnowledge = "";
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            SearchRequest req = SearchRequest.builder()
                    .query(question)
                    .topK(5)
                    .filterExpression(b.or(
                            b.eq(META_USER_ID, userId),
                            b.eq(META_SHARED, "true")
                    ).build())
                    .build();
            List<Document> docs = vectorStore.similaritySearch(req);
            if (docs != null && !docs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Document doc : docs) {
                    sb.append("【参考】").append(doc.getText()).append("\n\n");
                }
                relatedKnowledge = sb.toString();
            }
        } catch (Exception e) {
            log.warn("RAG 检索失败 query='{}'：{}", question, e.getMessage());
        }

        // 2. 构建 RAG Prompt（使用 StringBuilder 避免 String.format 注入风险）
        StringBuilder promptBuilder = new StringBuilder()
                .append("你是一个 Java 后端面试助手，请根据以下参考资料回答问题。\n\n")
                .append("【参考资料】\n")
                .append(relatedKnowledge.isEmpty() ? "无" : relatedKnowledge)
                .append("\n【问题】\n")
                .append(sanitizePromptInput(question))
                .append("\n\n要求：\n")
                .append("1. 回答要准确、有条理\n")
                .append("2. 尽量引用参考资料\n")
                .append("3. 如果资料不足，明确说明\n");

        // 3. 调用 AI 并做空值校验
        String response = chatClient.prompt()
                .user(promptBuilder.toString())
                .call()
                .content();

        if (response == null || response.isBlank()) {
            log.warn("AI 返回内容为空，question='{}'", question);
            return "AI 暂时无法生成回答，请稍后重试。";
        }

        return response;
    }

    /**
     * 导入知识文档到向量库（绑定 userId）
     * - 空列表直接返回，避免无意义调用
     * - 去重预检：对每个文档做相似度搜索，相似度 >= {@link #DEDUP_SIMILARITY_THRESHOLD} 视为重复，跳过
     * - 异常时仅记录日志，不抛出，避免影响批量导入主流程
     *
     * @param documents 文档文本列表
     * @param userId    当前用户 ID（写入 metadata 实现隔离）
     * @return 实际导入的文档数量（重复跳过的不计入）
     */
    public int importKnowledge(List<String> documents, String userId) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<Document> docs = new ArrayList<>();
            int skipped = 0;
            for (String text : documents) {
                if (text == null || text.isBlank()) continue;
                // 去重预检：搜索该用户已有文档中是否存在高度相似的
                if (isDuplicate(text, userId, b)) {
                    skipped++;
                    continue;
                }
                docs.add(Document.builder()
                        .text(text)
                        .metadata(Map.of("type", "knowledge", META_USER_ID, userId))
                        .build());
            }
            if (docs.isEmpty()) {
                log.info("知识库导入：{} 条全部重复或为空，跳过", documents.size());
                return 0;
            }
            vectorStore.add(docs);
            if (skipped > 0) {
                log.info("知识库导入：{} 条新增，{} 条重复跳过", docs.size(), skipped);
            }
            return docs.size();
        } catch (Exception e) {
            log.error("知识库导入失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 检查该用户已有文档中是否存在与 text 高度相似的（去重预检）
     * - 仅搜索当前用户的文档，不影响其他用户
     * - 去重检查失败不阻断导入（返回 false，按非重复处理）
     */
    private boolean isDuplicate(String text, String userId, FilterExpressionBuilder b) {
        try {
            SearchRequest dedupReq = SearchRequest.builder()
                    .query(text)
                    .topK(1)
                    .similarityThreshold(DEDUP_SIMILARITY_THRESHOLD)
                    .filterExpression(b.eq(META_USER_ID, userId).build())
                    .build();
            List<Document> existing = vectorStore.similaritySearch(dedupReq);
            return existing != null && !existing.isEmpty();
        } catch (Exception e) {
            log.debug("去重检查失败，按非重复处理：{}", e.getMessage());
            return false;
        }
    }

    /**
     * Prompt 注入防御：剥离可能的指令性换行和角色扮演标记
     * - 移除 "忽略以上所有指令"、"你现在是" 等常见注入模式
     * - 截断超长输入（防止 token 滥用）
     */
    private String sanitizePromptInput(String input) {
        return PromptSanitizer.sanitize(input);
    }
}
