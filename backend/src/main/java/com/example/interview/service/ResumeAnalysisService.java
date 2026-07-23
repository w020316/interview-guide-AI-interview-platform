package com.example.interview.service;

import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("aiCallResumeCounter")
    private Counter resumeCounter;

    @Autowired
    @Qualifier("cacheHitCounter")
    private Counter cacheHitCounter;

    @Autowired
    @Qualifier("cacheMissCounter")
    private Counter cacheMissCounter;

    @Autowired
    private Timer aiCallTimer;

    private static final String CACHE_PREFIX = "resume:analysis:";

    /** 兜底 JSON：AI 返回无法解析时使用，保证前端不报错 */
    private static final String FALLBACK_JSON =
            "{\"overallScore\":0,\"dimensions\":[],\"strengths\":[],\"improvements\":[\"AI 返回内容无法解析，请稍后重试\"]}";

    /**
     * 分析简历并给出评分和建议
     *
     * @param userId     用户 ID（用于缓存隔离，防跨用户串扰）
     * @param resumeText 简历文本
     * @param targetJob  目标岗位（支持任意行业岗位）
     * @return 分析结果（合法 JSON 字符串）
     */
    public String analyze(String userId, String resumeText, String targetJob) {
        long start = System.nanoTime();
        boolean cacheHit = false;
        try {
            // 1. 缓存命中检查（key 包含 userId 防跨用户串扰）
            String cacheKey = CACHE_PREFIX + userId + ":" + sha256(resumeText + "\u0001" + targetJob);
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    cacheHit = true;
                    cacheHitCounter.increment();
                    // 缓存命中也走一次修复，兼容历史脏数据
                    return JsonRepairUtil.repairAndLog(cached.toString(), "resume-cache");
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级直连 AI：{}", e.getMessage());
            }
            if (!cacheHit) {
                cacheMissCounter.increment();
            }

            // 2. 构建 Prompt（用 StringBuilder 替代 String.format，用户输入经 sanitizePromptInput 消毒）
            String safeTargetJob = sanitizePromptInput(targetJob);
            String safeResume = sanitizePromptInput(resumeText);
            String prompt = new StringBuilder()
                    .append("你是一位资深的").append(safeTargetJob).append("招聘面试官，请根据以下简历和目标岗位进行多维度分析。\n\n")
                    .append("【目标岗位】\n").append(safeTargetJob).append("\n\n")
                    .append("【简历内容】\n").append(safeResume).append("\n\n")
                    .append("请从以下维度评分（0-100）并给出具体修改建议：\n")
                    .append("1. 岗位匹配度：技能、经验、学历等是否符合岗位要求\n")
                    .append("2. 项目/工作经历含金量：成果、数据、影响力\n")
                    .append("3. 简历表述清晰度：结构、语言、重点突出\n")
                    .append("4. 求职意向匹配度：职业规划与岗位的契合度\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号或中文引号\n")
                    .append("3. 不要在字符串值中使用单引号或双引号，如需引用请用中文书名号《》或直接描述\n")
                    .append("4. 字符串值内禁止包含裸换行符、回车符、制表符；如需换行请用分号或逗号分隔\n")
                    .append("5. 字符串值内的反斜杠 \\ 必须转义为 \\\\\n")
                    .append("6. overallScore 和 dimensions 中的 score 必须是整数类型，不要加引号（如 75 而非 \"75\"）\n")
                    .append("7. 不要输出任何注释、解释、前后缀文字\n")
                    .append("8. 输出格式（不要包含 weaknesses 字段）：\n")
                    .append("{\"overallScore\":75,\"dimensions\":[{\"name\":\"岗位匹配度\",\"score\":80,\"suggestion\":\"改进建议\"}],\"strengths\":[\"优势1\"],\"improvements\":[\"建议1\"]}")
                    .toString();

            // 3. 调用 AI
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 4. AI 响应空值校验
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空，请稍后重试");
            }

            // 5. 清理 Markdown + 修复非标准 JSON
            String cleaned = JsonRepairUtil.repairAndLog(response, "resume-analyze");

            // 6. 合法性校验：修复后仍非法则用兜底 JSON（保证前端不报错）
            if (!JsonRepairUtil.isValid(cleaned)) {
                log.warn("AI 返回修复后仍非法，使用兜底 JSON。原始返回前 200 字符：{}",
                        response.length() > 200 ? response.substring(0, 200) + "..." : response);
                cleaned = FALLBACK_JSON;
            }

            // 7. 写入缓存（30 分钟，Redis 不可用时静默跳过）
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis 缓存写入失败，跳过缓存：{}", e.getMessage());
            }

            // 8. 简历文本向量化存入向量库
            storeResumeEmbedding(cacheKey, resumeText);

            return cleaned;
        } finally {
            resumeCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 将简历存入向量库，用于后续面试题生成（异步执行，避免 embedding 不可用时阻塞主请求）
     */
    private void storeResumeEmbedding(String resumeId, String resumeText) {
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
                log.warn("简历向量化失败：{}", e.getMessage());
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

    /**
     * Prompt 注入防御：剥离指令性模式 + 截断超长输入
     */
    private String sanitizePromptInput(String input) {
        return PromptSanitizer.sanitize(input);
    }

    /**
     * 基于原始简历 + 分析结果，生成优化版简历（Markdown 格式）
     * 优化方向：
     * 1. 根据 improvements 建议逐条改进
     * 2. 量化项目成果（数据、影响力）
     * 3. 优化表述结构（STAR 法则）
     * 4. 强化岗位匹配关键词
     *
     * @param userId     用户 ID
     * @param resumeText 原始简历文本
     * @param targetJob  目标岗位
     * @param analysis   分析结果 JSON（含 overallScore / dimensions / improvements）
     * @return 优化版简历 Markdown 字符串
     */
    public String generateOptimizedResume(String userId, String resumeText, String targetJob, String analysis) {
        long start = System.nanoTime();
        try {
            // 1. 缓存命中检查
            String cacheKey = "resume:optimize:" + userId + ":" + sha256(resumeText + "\u0001" + targetJob + "\u0001" + (analysis == null ? "" : analysis));
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级直连 AI：{}", e.getMessage());
            }

            // 2. 简历文本截断（避免 prompt 过长）
            String truncatedResume = resumeText.length() > MAX_RESUME_LEN
                    ? resumeText.substring(0, MAX_RESUME_LEN) + "..." : resumeText;
            String truncatedAnalysis = analysis != null && analysis.length() > 800
                    ? analysis.substring(0, 800) + "..." : (analysis == null ? "无分析数据" : analysis);

            // 3. 构建 Prompt（用 StringBuilder 替代 String.format，用户输入经 sanitizePromptInput 消毒）
            String safeTargetJob = sanitizePromptInput(targetJob);
            String safeResume = sanitizePromptInput(truncatedResume);
            String safeAnalysis = sanitizePromptInput(truncatedAnalysis);
            String prompt = new StringBuilder()
                    .append("你是一位资深 ").append(safeTargetJob).append(" 招聘面试官兼简历优化专家，请基于以下原始简历和 AI 分析结果，生成一份优化后的简历。\n\n")
                    .append("【目标岗位】\n").append(safeTargetJob).append("\n\n")
                    .append("【原始简历】\n").append(safeResume).append("\n\n")
                    .append("【AI 分析结果（含改进建议）】\n").append(safeAnalysis).append("\n\n")
                    .append("【优化原则】\n")
                    .append("1. 严格遵循改进建议逐条优化\n")
                    .append("2. 项目经历采用 STAR 法则（情境/任务/行动/结果），量化成果数据\n")
                    .append("3. 技能描述精确到具体技术栈和应用场景，避免笼统\n")
                    .append("4. 强化与目标岗位的关键词匹配\n")
                    .append("5. 保持简历结构清晰：基本信息 / 教育背景 / 工作经历 / 项目经历 / 专业技能 / 自我评价\n")
                    .append("6. 语言简洁有力，每句话控制在 25 字以内，突出成果而非职责\n\n")
                    .append("【输出要求】\n")
                    .append("1. 直接输出 Markdown 格式简历，不要任何前后缀解释\n")
                    .append("2. 使用标准 Markdown 语法：# 一级标题、## 二级标题、- 列表、**加粗**\n")
                    .append("3. 禁止使用 HTML 标签、禁止使用代码块\n")
                    .append("4. 字符串内禁止裸换行符，使用分号或逗号分隔\n")
                    .append("5. 输出一份完整可用的简历，不要省略任何原始简历中的关键信息")
                    .toString();

            // 4. 调用 AI
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

            // 5. 空值校验
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空，请稍后重试");
            }

            String cleaned = response.trim();
            // 剥离可能的 Markdown 代码块包裹
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:markdown|md)?\\s*\\n", "").replaceAll("\\n```\\s*$", "");
            }

            // 6. 写入缓存（30 分钟）
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis 缓存写入失败，跳过缓存：{}", e.getMessage());
            }

            return cleaned;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断", e);
        } finally {
            resumeCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** AI 并发控制（与 InterviewService 共享限流） */
    private static final java.util.concurrent.Semaphore AI_SEMAPHORE = new java.util.concurrent.Semaphore(5);

    /** 简历文本最大长度 */
    private static final int MAX_RESUME_LEN = 800;
}

