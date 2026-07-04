package com.example.interview.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 知识库服务
 * - 向量检索面试八股文
 * - 构建带上下文的回答
 */
@Service
public class RagSearchService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient chatClient;

    /**
     * 检索相关知识点
     */
    public String search(String query, int topK) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .build()
            );

            if (docs == null || docs.isEmpty()) {
                return "[]";
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format("""
                        {"id":"%s","content":"%s","score":%f}""",
                        doc.getId(),
                        doc.getText().replace("\"", "\\\"").replace("\n", " "),
                        doc.getMetadata().getOrDefault("distance", 0.0)));
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 构建带上下文的回答
     */
    public String answerWithRag(String question) {
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
            System.err.println("RAG 检索失败：" + e.getMessage());
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

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 导入知识文档到向量库
     */
    public void importKnowledge(List<String> documents) {
        try {
            List<Document> docs = documents.stream()
                    .map(text -> Document.builder()
                            .text(text)
                            .metadata(java.util.Map.of("type", "knowledge"))
                            .build())
                    .toList();
            vectorStore.add(docs);
        } catch (Exception e) {
            System.err.println("知识库导入失败：" + e.getMessage());
        }
    }
}
