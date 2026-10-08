package com.example.interview.service;

import com.example.interview.util.HashUtil;
import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /** P1-04：简历向量统一经 RAG 服务入库，受 maxDocuments 容量计数保护 */
    @Autowired
    private RagSearchService ragSearchService;

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

    /** 简历分析缓存存活时长（毫秒）：与 Redis 侧 30 分钟保持一致（v1.34.1 P2-5） */
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * Redis 不可用时的进程内兜底缓存（v1.34.1 P2-5）。
     *
     * <p>无 Redis 环境（本地/低配单机）下此前缓存整体旁路——真机实测 {@code cache.hit.count = 0}、
     * 重复请求耗时 7.12s（与首次 9.24s 同量级），即每次都全额调用 LLM。
     * 实例级而非静态，避免单测之间通过静态状态互相污染。
     */
    private final com.example.interview.util.LocalPromptCache redisFallbackCache =
            new com.example.interview.util.LocalPromptCache(500);

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
            String cacheKey = CACHE_PREFIX + userId + ":" + HashUtil.sha256Short(resumeText + "\u0001" + targetJob);
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    cacheHit = true;
                    cacheHitCounter.increment();
                    // 缓存命中也走一次修复，兼容历史脏数据
                    return JsonRepairUtil.repairAndLog(cached.toString(), "resume-cache");
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级进程内缓存/直连 AI：{}", e.getMessage());
                // v1.34.1（P2-5）：Redis 不可用时改用进程内兜底缓存。
                // 仅在「读取抛异常」时启用（而非 Redis 返回 null 的正常未命中），
                // 确保 Redis 正常时行为与改造前完全一致。
                String fallback = redisFallbackCache.get(cacheKey);
                if (fallback != null) {
                    cacheHit = true;
                    cacheHitCounter.increment();
                    return JsonRepairUtil.repairAndLog(fallback, "resume-cache-local");
                }
            }
            if (!cacheHit) {
                cacheMissCounter.increment();
            }

            // 2. 构建 Prompt（用 StringBuilder 替代 String.format，用户输入经 PromptSanitizer 消毒）
            String safeTargetJob = PromptSanitizer.sanitize(targetJob);
            // v1.34.1 修复（P2-3）：简历文本此前只 sanitize 不截断，与本类 generateOptimizedResume
            // 的 800 字截断策略不一致——上传接口允许 10MB，长简历会直灌 prompt，
            // 使 token 成本与延迟不可控。现两处统一走 MAX_RESUME_LEN。
            String safeResume = PromptSanitizer.sanitize(TextUtil.truncate(resumeText, MAX_RESUME_LEN));
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

            // 3. 调用 AI（v1.23.1：纳入全局 AI 并发闸门）
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content());

            // 4. AI 响应空值校验
            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }

            // 5. 清理 Markdown + 修复非标准 JSON，并做**契约校验**：失败重试一次，仍失败抛 BusinessException。
            //
            // v1.47.0（第六轮 P1-01）：此前修复后仍非法就返回兜底 JSON，且该兜底串自带
            // "overallScore":0 → 被 saveResume 当正常结果落库 → 用户看到「0 分」，
            // 与真实 0 分无法区分（实测原始输出结构性损坏率 ≈46%，绝非罕见边界）。
            // 现对齐 InterviewService.validateOrRetry()：校验必需字段与类型，失败重试一次，
            // 仍失败抛 BusinessException（GlobalExceptionHandler 映射为 503），**不再返回兜底串**。
            String cleaned = validateOrRetryResume(response, () ->
                    com.example.interview.ai.AiConcurrencyGuard.call(() ->
                            chatClient.prompt()
                                    .user(prompt)
                                    .call()
                                    .content()));

            // 6. 写入缓存（30 分钟，Redis 不可用时静默跳过）
            //    校验已保证 cleaned 为可用结果，因此直接缓存；兜底脏数据不可能再进入缓存。
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                // v1.34.1（P2-5）：Redis 写入失败时落进程内兜底缓存，
                // 使无 Redis 环境下的重复简历分析也能命中缓存（否则每次都全额调用 LLM）
                log.warn("Redis 缓存写入失败，改用进程内缓存：{}", e.getMessage());
                redisFallbackCache.put(cacheKey, cleaned, CACHE_TTL_MILLIS);
            }

            // 7. 简历文本向量化存入向量库
            storeResumeEmbedding(cacheKey, userId, resumeText);

            return cleaned;
        } finally {
            resumeCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 简历分析 JSON 校验用（字段少、结构固定，独立实例足够，避免与全局 ObjectMapper 配置耦合） */
    private static final com.fasterxml.jackson.databind.ObjectMapper RESUME_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 校验简历分析结果是否为「可用的」JSON —— 既要能解析，也要满足业务契约：
     * <ul>
     *   <li>{@code overallScore} 为**数值**且在 0~100 之间（不能是 null/字符串/越界）；</li>
     *   <li>{@code dimensions} 为**非空数组**，且每项的 {@code score} 为数值。</li>
     * </ul>
     *
     * <p>为什么必须有这层校验（v1.47.0，第六轮 P1-01）：简历分析 JSON 是**嵌套结构**，
     * 实测 Agnes 直连复刻提示词 13 次有 6 次（≈46%）原始输出结构性损坏，而 {@code JsonRepairUtil}
     * 对失败样本 3/3 修复失败 → 此前会落到自带 {@code "overallScore":0} 的兜底串，
     * 被 {@code saveResume} 当正常结果落库（静默失败）。面试评分链路早有等价的
     * {@code InterviewService.validateOrRetry()}，本方法补齐简历链路这处漏网点。
     */
    static boolean isValidResumeAnalysis(String json) {
        if (!JsonRepairUtil.isValid(json)) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = RESUME_MAPPER.readTree(json);
            com.fasterxml.jackson.databind.JsonNode score = node.get("overallScore");
            if (score == null || !score.isNumber()) {
                return false;
            }
            double v = score.asDouble();
            if (v < 0 || v > 100) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode dims = node.get("dimensions");
            if (dims == null || !dims.isArray() || dims.isEmpty()) {
                return false;
            }
            for (com.fasterxml.jackson.databind.JsonNode d : dims) {
                com.fasterxml.jackson.databind.JsonNode s = d.get("score");
                if (s == null || !s.isNumber()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 输出前校验：破损 JSON 先重试一次，仍不合格则抛出明确错误。
     *
     * <p>范式对齐 {@code InterviewService.validateOrRetry()}——「要么合法，要么明确失败」，
     * **不再把兜底串当成功结果返回**。重试会多消耗一次 AI 调用，但只在解析/契约失败时发生，
     * 相比「把脏数据当成功下发、前端渲染出 0 分或空面板」，这个代价是值得的。
     *
     * @param retryCall 重试时重新发起 AI 调用的动作（需自行包裹并发闸门）
     */
    private String validateOrRetryResume(String raw, java.util.function.Supplier<String> retryCall) {
        String repaired = JsonRepairUtil.repairAndLog(raw, "resume-analyze");
        if (isValidResumeAnalysis(repaired)) {
            return repaired;
        }
        log.warn("简历分析输出非法 JSON（已尝试修复），重试一次。原文前 200 字：{}", TextUtil.truncate(raw, 200));

        String retryRaw;
        try {
            retryRaw = retryCall.get();
        } catch (Exception e) {
            log.warn("简历分析重试调用失败：{}", e.getMessage());
            throw new com.example.interview.common.BusinessException("简历分析结果格式异常，请稍后重试");
        }
        if (retryRaw == null || retryRaw.isBlank()) {
            throw new com.example.interview.common.BusinessException("简历分析结果格式异常，请稍后重试");
        }
        String retryRepaired = JsonRepairUtil.repairAndLog(retryRaw, "resume-analyze-retry");
        if (isValidResumeAnalysis(retryRepaired)) {
            return retryRepaired;
        }
        log.error("简历分析重试后仍非法，放弃本次分析。原文前 200 字：{}", TextUtil.truncate(retryRaw, 200));
        throw new com.example.interview.common.BusinessException("简历分析结果格式异常，请稍后重试");
    }

    /**
     * 将简历存入向量库，用于后续面试题生成（异步执行，避免 embedding 不可用时阻塞主请求）
     * v1.23.1：metadata 补 userId 便于隔离
     * v1.33.0（P1-04）：id 改为由 resumeId 派生的确定性 id（非法字符替换为 '-'），
     * 重复分析同一简历时先删后加（upsert 覆盖），不再每次生成新 UUID 无界累积；
     * 入库统一走 RagSearchService.addToVectorStore 受容量计数保护。
     * v1.34.1（P2-6）：删除改走 {@link RagSearchService#removeFromVectorStore}，
     * 与 addToVectorStore 配对递减容量计数——此前直接 vectorStore.delete 绕过计数，
     * 计数只增不减，反复分析同一简历会使计数虚高并最终误拒知识导入。
     */
    private void storeResumeEmbedding(String resumeId, String userId, String resumeText) {
        // 用虚拟线程异步执行，避免 embedding 不可用时阻塞主请求
        Thread.startVirtualThread(() -> {
            try {
                String docId = deriveDocId(resumeId);
                // 覆盖旧版本文档：同 id 先删后加（不存在时 delete 为无害空操作）
                try {
                    ragSearchService.removeFromVectorStore(List.of(docId));
                } catch (Exception ignored) {
                }
                Document doc = Document.builder()
                        .id(docId)
                        .text(resumeText)
                        .metadata(Map.of("type", "resume", "resumeId", resumeId,
                                "userId", userId))
                        .build();
                ragSearchService.addToVectorStore(List.of(doc));
            } catch (Exception e) {
                // 向量化失败不影响主流程
                log.warn("简历向量化失败：{}", e.getMessage());
            }
        });
    }

    /**
     * 由 resumeId 派生向量库文档 ID。
     *
     * <p><b>为什么必须是标准 UUID（P1-03，2026-09-23）</b>：pgvector 的 {@code id} 列类型是
     * {@code uuid}，长度上限 36。此前直接把 Redis 缓存键当 ID 用：
     * {@code "resume-" + cacheKey}，而
     * {@code cacheKey = "resume:analysis:"(16) + userId + ":"(1) + sha256Short(32)} ≈ 51 字符，
     * 加前缀后约 <b>58 字符</b> → 写入即报
     * {@code 向量化入库失败：UUID string too large}，
     * 简历永远进不了向量库（RAG 检索因此少了简历这一路召回），
     * 而异常在虚拟线程里被 catch 成一条 WARN，用户与运维都只看到
     * 「知识库暂时不可用」，完全定位不到原因。
     *
     * <p>改用 {@link UUID#nameUUIDFromBytes} 派生：结果恒为 36 字符的合法 UUID，
     * 且同一 resumeId 始终得到同一 ID，保留「重复分析同一简历时先删后加、覆盖而非累积」的原语义。
     * 这与 {@code AutoKnowledgeService.deterministicId}、{@code KnowledgeSeedInitializer} 的既有做法一致。
     */
    static String deriveDocId(String resumeId) {
        return UUID.nameUUIDFromBytes(("resume|" + resumeId).getBytes(StandardCharsets.UTF_8)).toString();
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
            String cacheKey = "resume:optimize:" + userId + ":" + HashUtil.sha256Short(resumeText + "\u0001" + targetJob + "\u0001" + (analysis == null ? "" : analysis));
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级直连 AI：{}", e.getMessage());
            }

            // 2. 简历文本截断（避免 prompt 过长）
            String truncatedResume = TextUtil.truncate(resumeText, MAX_RESUME_LEN);
            String truncatedAnalysis = analysis == null
                    ? "无分析数据"
                    : TextUtil.truncate(analysis, 800);

            // 3. 构建 Prompt（用 StringBuilder 替代 String.format，用户输入经 PromptSanitizer 消毒）
            String safeTargetJob = PromptSanitizer.sanitize(targetJob);
            String safeResume = PromptSanitizer.sanitize(truncatedResume);
            String safeAnalysis = PromptSanitizer.sanitize(truncatedAnalysis);
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

            // 4. 调用 AI（纳入全局并发闸门，与其它 AI Service 统一共享 5 许可，v1.31.4）
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content());

            // 5. 空值校验
            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }

            String cleaned = JsonRepairUtil.stripMarkdownFence(response).trim();

            // 6. 写入缓存（30 分钟）
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis 缓存写入失败，跳过缓存：{}", e.getMessage());
            }

            return cleaned;
        } finally {
            resumeCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 简历文本最大长度。
     *
     * <p><b>v1.64.0 由 800 提到 3000 —— 这是预防性改动，不是修复。</b>
     * 与 JD 侧不同，我**没有**取到「简历正在被截断」的证据：
     * 线上抽样 7 份简历长度 71~392 字，全部远低于 800。所以这一条是**推断**：
     * 真实用户简历（教育经历 + 项目经验 + 技能）通常 1000~2000 字，
     * 而 800 的上限会静默截掉后半段，用户不会收到任何提示。
     *
     * <p>之所以仍然改：**下行风险有界**（prompt 略长、延迟略增），
     * 而收益是避免「静默丢输入」——那种失败用户看不见，只会觉得分析结果不准。
     * 若日后取到真实简历长度分布且远小于 800，可以再调回去。
     */
    private static final int MAX_RESUME_LEN = 3000;
}

