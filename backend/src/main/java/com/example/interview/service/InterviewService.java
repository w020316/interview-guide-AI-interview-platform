package com.example.interview.service;

import com.example.interview.util.JsonRepairUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Autowired
    @Qualifier("aiCallQuestionCounter")
    private Counter questionCounter;

    @Autowired
    @Qualifier("aiCallEvaluateCounter")
    private Counter evaluateCounter;

    @Autowired
    private Timer aiCallTimer;

    private static final String QUESTION_CACHE = "interview:question:";

    /** AI 并发控制：避免免费层 API 限流导致批量失败 */
    private static final java.util.concurrent.Semaphore AI_SEMAPHORE = new java.util.concurrent.Semaphore(5);

    /** 简历文本最大长度（截断后送 AI，避免 prompt 过长拖慢推理） */
    private static final int MAX_RESUME_LEN = 800;

    /**
     * 生成面试题
     * @param userId 用户 ID（用于缓存隔离，防跨用户串扰）
     */
    public String generateQuestions(String userId, String resumeText, String jobDescription, int count) {
        long start = System.nanoTime();
        boolean cacheHit = false;
        try {
            // 1. 缓存命中（key 包含 userId 防跨用户串扰）
            String cacheKey = QUESTION_CACHE + userId + ":" + sha256(resumeText + jobDescription + count);
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    cacheHit = true;
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级直连 AI：{}", e.getMessage());
            }

            // 2. RAG 检索（降 topK=2 + 短路：生产环境 pgvector 被排除时直接跳过，避免 5s 超时浪费）
            String relatedKnowledge = "";
            try {
                if (isVectorStoreAvailable()) {
                    List<Document> docs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(jobDescription)
                                    .topK(2)
                                    .build()
                    );
                    if (docs != null && !docs.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (Document doc : docs) {
                            String t = doc.getText();
                            if (t != null && !t.isBlank()) {
                                sb.append("- ").append(t.length() > 100 ? t.substring(0, 100) : t).append("\n");
                            }
                        }
                        relatedKnowledge = sb.toString();
                    }
                }
            } catch (Exception e) {
                log.warn("RAG 检索失败，跳过：{}", e.getMessage());
            }

            // 3. 精简 Prompt（用 StringBuilder 替代 String.format，避免 % 注入；用户输入经 sanitizePromptInput 消毒）
            String truncatedResume = resumeText.length() > MAX_RESUME_LEN
                    ? resumeText.substring(0, MAX_RESUME_LEN) + "..." : resumeText;
            String safeJobDesc = sanitizePromptInput(jobDescription);
            String safeResume = sanitizePromptInput(truncatedResume);
            String prompt = new StringBuilder()
                    .append("你是一位资深的").append(safeJobDesc).append("面试官，请为候选人生成 ").append(count).append(" 道面试题。\n\n")
                    .append("【简历亮点】\n").append(safeResume).append("\n\n")
                    .append("【参考知识点】\n").append(relatedKnowledge.isEmpty() ? "无" : relatedKnowledge).append("\n\n")
                    .append("【出题原则】\n")
                    .append("1. 题目与岗位高度相关，覆盖核心技能与项目经验\n")
                    .append("2. 难度分布：简单 30%、中等 50%、困难 20%\n")
                    .append("3. 分类覆盖：技术基础、项目深挖、场景设计、行为面试（按岗位调整）\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON 数组，不要 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                    .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                    .append("4. 不要输出注释、解释、前后缀文字\n")
                    .append("5. 输出格式：\n")
                    .append("[{\"question\":\"请介绍你的项目架构\",\"category\":\"项目深挖\",\"difficulty\":\"MEDIUM\",\"keyPoints\":[\"考察点1\"],\"referenceAnswer\":\"参考答案要点\"}]")
                    .toString();

            // 4. 调用 AI（并发控制 + 信号量保护）
            String response;
            AI_SEMAPHORE.acquire();
            try {
                response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
            } finally {
                AI_SEMAPHORE.release();
            }

            // 5. AI 响应空值校验
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空，请稍后重试");
            }

            // 6. 清理 Markdown + 修复非标准 JSON
            String cleaned = JsonRepairUtil.repairAndLog(response, "interview-questions");

            // 7. 写入缓存（1 小时）
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("Redis 缓存写入失败，跳过缓存：{}", e.getMessage());
            }

            return cleaned;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断", e);
        } finally {
            questionCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            if (cacheHit) {
                log.debug("面试题缓存命中");
            }
        }
    }

    /**
     * 检测 VectorStore 是否真正可用（生产环境 pgvector 被排除时返回 false）
     */
    private boolean isVectorStoreAvailable() {
        try {
            return vectorStore != null
                    && !vectorStore.getClass().getName().contains("SimpleVectorStore");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 评估用户回答
     */
    public String evaluateAnswer(String question, String userAnswer, String referenceAnswer) {
        long start = System.nanoTime();
        try {
            // 用户输入经 sanitizePromptInput 消毒，避免 prompt 注入
            String prompt = new StringBuilder()
                    .append("你是一位面试官，请评估以下回答。\n\n")
                    .append("【面试题】\n").append(sanitizePromptInput(question)).append("\n\n")
                    .append("【参考答案】\n").append(sanitizePromptInput(referenceAnswer == null ? "" : referenceAnswer)).append("\n\n")
                    .append("【用户回答】\n").append(sanitizePromptInput(userAnswer)).append("\n\n")
                    .append("请从完整性（30%）、准确性（40%）、表达能力（30%）三个维度评分，并给出改进建议。\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号 ' 或中文引号\n")
                    .append("3. 不要在字符串值中使用引号，如需引用请用书名号《》\n")
                    .append("4. 不要输出任何注释、解释、前后缀文字\n")
                    .append("5. 输出格式：\n")
                    .append("{\"overallScore\":75,\"completeness\":70,\"accuracy\":80,\"expression\":75,\"strengths\":[\"优点1\"],\"weaknesses\":[\"不足1\"],\"improvements\":[\"建议1\"]}")
                    .toString();

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空，请稍后重试");
            }

            return JsonRepairUtil.repairAndLog(response, "interview-evaluate");
        } finally {
            evaluateCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Prompt 注入防御：剥离指令性模式 + 截断超长输入
     * - 移除 "忽略以上所有指令"、"你现在是" 等常见注入模式
     * - 截断至 2000 字符，防止 token 滥用
     */
    private String sanitizePromptInput(String input) {
        if (input == null) return "";
        String s = input;
        if (s.length() > 2000) {
            s = s.substring(0, 2000);
        }
        s = s.replaceAll("(?i)忽略以上(所有)?(指令|规则|要求)", "[已过滤]")
             .replaceAll("(?i)ignore (all )?(previous|above) instructions", "[filtered]")
             .replaceAll("(?i)你现在是", "用户提到：")
             .replaceAll("(?i)you are now", "user mentioned:");
        return s;
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

