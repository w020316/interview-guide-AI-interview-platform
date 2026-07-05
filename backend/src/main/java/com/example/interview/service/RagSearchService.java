package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库服务
 * - 向量检索面试八股文
 * - 构建带上下文的回答
 * - 通过 ObjectMapper 输出 JSON，避免手工拼接导致的安全/转义问题
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 检索相关知识点（返回 JSON 数组字符串）
     * 异常时返回 "[]"，避免上层解析失败
     */
    public String search(String query, int topK) {
        // 入参防御
        if (query == null || query.isBlank()) {
            return "[]";
        }
        int safeK = Math.max(1, Math.min(topK, 50)); // 限制 topK 上限，防止恶意大值

        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(safeK)
                            .build()
            );

            if (docs == null || docs.isEmpty()) {
                return "[]";
            }

            // 使用 ObjectMapper 序列化，自动处理转义，避免手工拼接 JSON 字符串的安全问题
            List<Map<String, Object>> result = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                result.add(Map.of(
                        "id", doc.getId(),
                        "content", doc.getText() == null ? "" : doc.getText(),
                        "score", doc.getMetadata().getOrDefault("distance", 0.0)
                ));
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("RAG 检索失败 query='{}'：{}", query, e.getMessage());
            return "[]";
        }
    }

    /**
     * 构建带上下文的回答
     * - 失败时返回兜底提示，避免返回 null 让前端崩
     */
    public String answerWithRag(String question) {
        if (question == null || question.isBlank()) {
            return "问题不能为空";
        }

        // 1. 检索相关知识
        String relatedKnowledge = "";
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(5)
                            .build()
            );
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

        // 2. 构建 RAG Prompt
        String prompt = String.format("""
                你是一个 Java 后端面试助手，请根据以下参考资料回答问题。

                【参考资料】
                %s

                【问题】
                %s

                要求：
                1. 回答要准确、有条理
                2. 尽量引用参考资料
                3. 如果资料不足，明确说明
                """, relatedKnowledge, question);

        // 3. 调用 AI 并做空值校验
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        if (response == null || response.isBlank()) {
            log.warn("AI 返回内容为空，question='{}'", question);
            return "AI 暂时无法生成回答，请稍后重试。";
        }

        return response;
    }

    /**
     * 导入知识文档到向量库
     * - 空列表直接返回，避免无意义调用
     * - 异常时仅记录日志，不抛出，避免影响批量导入主流程
     */
    public int importKnowledge(List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        try {
            List<Document> docs = documents.stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(text -> Document.builder()
                            .text(text)
                            .metadata(Map.of("type", "knowledge"))
                            .build())
                    .toList();
            if (docs.isEmpty()) {
                return 0;
            }
            vectorStore.add(docs);
            return docs.size();
        } catch (Exception e) {
            log.error("知识库导入失败：{}", e.getMessage(), e);
            return 0;
        }
    }
}
