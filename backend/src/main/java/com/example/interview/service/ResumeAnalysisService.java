package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 简历分析服务
 * - AI 多维度评分
 * - 评分结果 Redis 缓存
 */
@Service
public class ResumeAnalysisService {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "resume:analysis:";

    /**
     * 分析简历并给出评分和建议
     *
     * @param resumeText 简历文本
     * @param targetJob  目标岗位
     * @return 分析结果（JSON 字符串）
     */
    public String analyze(String resumeText, String targetJob) {
        // 1. 缓存命中检查（Redis 不可用时降级跳过缓存）
        // 使用 SHA-256 避免 hashCode 碰撞导致缓存串
        String cacheKey = CACHE_PREFIX + sha256(resumeText + targetJob);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached.toString();
            }
        } catch (Exception e) {
            System.err.println("Redis 缓存读取失败，降级直连 AI：" + e.getMessage());
        }

        // 2. 构建 Prompt
        String prompt = String.format("""
                你是一位资深 Java 后端面试官，请根据以下简历和目标岗位进行多维度分析：
                
                【目标岗位】
                %s
                
                【简历内容】
                %s
                
                请从以下维度评分（0-100）并给出具体修改建议：
                1. 技术栈匹配度
                2. 项目经历含金量
                3. 简历表述清晰度
                4. 求职意向匹配度
                
                输出格式（严格 JSON，不要 Markdown 代码块）：
                {
                  "overallScore": 75,
                  "dimensions": [
                    {"name": "技术栈匹配度", "score": 80, "suggestion": "..."},
                    {"name": "项目经历含金量", "score": 70, "suggestion": "..."},
                    {"name": "简历表述清晰度", "score": 75, "suggestion": "..."},
                    {"name": "求职意向匹配度", "score": 78, "suggestion": "..."}
                  ],
                  "strengths": ["优势1", "优势2"],
                  "weaknesses": ["不足1", "不足2"],
                  "improvements": ["建议1", "建议2"]
                }
                """, targetJob, resumeText);

        // 3. 调用 AI
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // 4. 清理可能的 Markdown 代码块
        String cleaned = response.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        // 5. 写入缓存（30 分钟，Redis 不可用时静默跳过）
        try {
            redisTemplate.opsForValue().set(cacheKey, cleaned, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            System.err.println("Redis 缓存写入失败，跳过缓存：" + e.getMessage());
        }

        // 6. 简历文本向量化存入向量库
        storeResumeEmbedding(cacheKey, resumeText);

        return cleaned;
    }

    /**
     * 将简历存入向量库，用于后续面试题生成（异步执行，避免 embedding 不可用时阻塞主请求）
     */
    public void storeResumeEmbedding(String resumeId, String resumeText) {
        // 用虚拟线程异步执行，避免 embedding 不可用时阻塞主请求
        Thread.startVirtualThread(() -> {
            try {
                Document doc = Document.builder()
                        .id(resumeId)
                        .text(resumeText)
                        .metadata(Map.of("type", "resume", "resumeId", resumeId))
                        .build();
                vectorStore.add(List.of(doc));
            } catch (Exception e) {
                // 向量化失败不影响主流程
                System.err.println("简历向量化失败：" + e.getMessage());
            }
        });
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
            return sb.substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
