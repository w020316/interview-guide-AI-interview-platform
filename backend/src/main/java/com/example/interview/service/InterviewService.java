package com.example.interview.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 模拟面试服务
 * - 根据简历 + 岗位生成个性化面试题
 * - 评估用户回答质量
 */
@Service
public class InterviewService {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String QUESTION_CACHE = "interview:question:";

    /**
     * 生成面试题
     */
    public String generateQuestions(String resumeText, String jobDescription, int count) {
        // 1. 缓存命中
        String cacheKey = QUESTION_CACHE + Math.abs((resumeText + jobDescription + count).hashCode());
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.toString();
        }

        // 2. RAG 检索相关知识
        String relatedKnowledge = "";
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(jobDescription + " " + resumeText)
                            .topK(5)
                            .build()
            );
            if (docs != null && !docs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Document doc : docs) {
                    sb.append("- ").append(doc.getText()).append("\n");
                }
                relatedKnowledge = sb.toString();
            }
        } catch (Exception e) {
            System.err.println("RAG 检索失败：" + e.getMessage());
        }

        // 3. 构建 Prompt
        String prompt = String.format("""
                你是一位资深 Java 后端面试官，请为以下候选人生成 %d 道面试题。
                
                【岗位要求】
                %s
                
                【简历亮点】
                %s
                
                【参考知识点】
                %s
                
                要求：
                1. 题目难度适中，覆盖 Java 基础、框架、数据库、中间件
                2. 每道题标注考察点和参考答案要点
                3. 结合简历项目经历出题
                
                输出格式（严格 JSON 数组，不要 Markdown 代码块）：
                [
                  {
                    "question": "请介绍你的项目架构",
                    "category": "项目深挖",
                    "difficulty": "MEDIUM",
                    "keyPoints": ["考察点1", "考察点2"],
                    "referenceAnswer": "参考答案要点"
                  }
                ]
                """, count, jobDescription, resumeText, relatedKnowledge);

        // 4. 调用 AI
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // 5. 清理 Markdown
        String cleaned = response.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        // 6. 写入缓存（1 小时）
        redisTemplate.opsForValue().set(cacheKey, cleaned, 1, TimeUnit.HOURS);

        return cleaned;
    }

    /**
     * 评估用户回答
     */
    public String evaluateAnswer(String question, String userAnswer, String referenceAnswer) {
        String prompt = String.format("""
                你是一位面试官，请评估以下回答：
                
                【面试题】
                %s
                
                【参考答案】
                %s
                
                【用户回答】
                %s
                
                请从完整性（30%%）、准确性（40%%）、表达能力（30%%）三个维度评分，
                并给出改进建议。
                
                输出格式（严格 JSON，不要 Markdown 代码块）：
                {
                  "overallScore": 75,
                  "completeness": 70,
                  "accuracy": 80,
                  "expression": 75,
                  "strengths": ["..."],
                  "weaknesses": ["..."],
                  "improvements": ["..."]
                }
                """, question, referenceAnswer, userAnswer);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return response.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
    }
}
