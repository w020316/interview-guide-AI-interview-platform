package com.example.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

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
        // 1. 缓存命中（Redis 不可用时降级跳过缓存）
        // 使用 SHA-256 避免 hashCode 碰撞导致缓存串
        String cacheKey = QUESTION_CACHE + sha256(resumeText + jobDescription + count);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached.toString();
            }
        } catch (Exception e) {
            log.warn("Redis 缓存读取失败，降级直连 AI：{}", e.getMessage());
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
            log.warn("RAG 检索失败：{}", e.getMessage());
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

        // 5. AI 响应空值校验
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 返回内容为空，请稍后重试");
        }

        // 6. 清理 Markdown
        String cleaned = response.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        // 7. 写入缓存（1 小时，Redis 不可用时静默跳过）
        try {
            redisTemplate.opsForValue().set(cacheKey, cleaned, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败，跳过缓存：{}", e.getMessage());
        }

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

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 返回内容为空，请稍后重试");
        }

        return response.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
    }

    /** SHA-256 哈希，用于生成无碰撞的缓存键 */
    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 32); // 取前 32 位足够防碰撞
        } catch (Exception e) {
            // 降级用 hashCode
            return String.valueOf(input.hashCode());
        }
    }
}
