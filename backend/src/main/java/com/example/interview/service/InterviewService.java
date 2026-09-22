package com.example.interview.service;

import com.example.interview.util.HashUtil;
import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.net.URI;
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

    /** 简历文本最大长度（截断后送 AI，避免 prompt 过长拖慢推理） */
    private static final int MAX_RESUME_LEN = 800;

    /** 岗位描述最大长度（v1.34.1 P2-4：与简历同等截断，此前 JD 未截断） */
    private static final int MAX_JD_LEN = 800;

    /** 出题缓存存活时长（毫秒）：与 Redis 侧 1 小时保持一致（v1.34.1 P2-5） */
    private static final long CACHE_TTL_MILLIS = 60 * 60 * 1000L;

    /**
     * Redis 不可用时的进程内兜底缓存（v1.34.1 P2-5）。
     *
     * <p>无 Redis 环境（本地/低配单机）下此前缓存整体旁路，重复出题每次都全额调用 LLM。
     * 实例级而非静态，避免单测之间通过静态状态互相污染。
     */
    private final com.example.interview.util.LocalPromptCache redisFallbackCache =
            new com.example.interview.util.LocalPromptCache(500);

    /**
     * 生成面试题
     * @param userId 用户 ID（用于缓存隔离，防跨用户串扰）
     * @param difficulty 难度偏置：EASY/MEDIUM/HARD/空。为空时按默认 3:5:2 分布；否则向该难度倾斜（跨场自适应）
     * @param focusCategories 需要重点考察的薄弱分类（逗号分隔）。为空时不额外聚焦（跨场自适应）
     */
    public String generateQuestions(String userId, String resumeText, String jobDescription, int count,
                                    String difficulty, String focusCategories) {
        long start = System.nanoTime();
        boolean cacheHit = false;
        try {
            // 1. 缓存命中（key 包含 userId 防跨用户串扰；难度/聚焦项纳入 key，保证不同自适应产出不同题目）
            String cacheKey = QUESTION_CACHE + userId + ":" + HashUtil.sha256Short(
                    resumeText + jobDescription + count + "|d=" + difficulty + "|f=" + focusCategories);
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    cacheHit = true;
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败，降级进程内缓存/直连 AI：{}", e.getMessage());
                // v1.34.1（P2-5）：Redis 不可用时改用进程内兜底缓存。
                // 仅在「读取抛异常」时启用（而非 Redis 返回 null 的正常未命中），
                // 确保 Redis 正常时行为与改造前完全一致，不引入双写不一致。
                String fallback = redisFallbackCache.get(cacheKey);
                if (fallback != null) {
                    cacheHit = true;
                    return fallback;
                }
            }

            // 2. RAG 检索（降 topK=2 + 短路：向量库不可用时直接跳过，避免无谓超时）
            //
            // v1.34.1 修复（P2-2）：此前用「实现类名是否含 SimpleVectorStore」判断可用性，
            // 本意是排除「生产 pgvector 被排除」的场景，但 local profile 使用的
            // PersistentSimpleVectorStore **名字里正含该子串**，被误判为不可用 ——
            // 导致本地环境 AI 出题静默跳过 RAG，与 v1.34「让本地 RAG 可用」的目标矛盾；
            // 且 AOP 代理包装或实现类改名都会让该判定失效（脆弱判定）。
            //
            // 改为「非空即可用」：真正不可用时下文的 try/catch 会捕获并跳过，
            // 由具体调用失败决定降级，而不是靠猜测类名。
            String relatedKnowledge = "";
            try {
                if (vectorStore != null) {
                    List<Document> docs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(jobDescription)
                                    .topK(2)
                                    // P2-14：限定当前用户文档——此前无过滤条件，一旦切回 pgvector，
                                    // 会把任意用户的简历/知识文档拼入当前用户 prompt 造成跨用户串扰
                                    .filterExpression(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                                            .eq("userId", userId).build())
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

            // 3. 精简 Prompt（用 StringBuilder 替代 String.format，避免 % 注入；用户输入经 PromptSanitizer 消毒）
            String truncatedResume = TextUtil.truncate(resumeText, MAX_RESUME_LEN);
            // v1.34.1 修复（P2-4）：JD 与简历同为用户输入，此前只截简历不截 JD。
            // 岗位描述上传入上限为请求体 1MB，未截断会直灌 prompt，导致 token 成本与延迟不可控。
            String safeJobDesc = PromptSanitizer.sanitize(TextUtil.truncate(jobDescription, MAX_JD_LEN));
            String safeResume = PromptSanitizer.sanitize(truncatedResume);
            String safeFocus = PromptSanitizer.sanitize(focusCategories == null || focusCategories.isBlank() ? "" : focusCategories);
            String safeDiff = PromptSanitizer.sanitize(difficulty == null ? "" : difficulty.trim().toUpperCase());
            String prompt = new StringBuilder()
                    .append("你是一位资深的").append(safeJobDesc).append("面试官，请为候选人生成 ").append(count).append(" 道面试题。\n\n")
                    .append("【简历亮点】\n").append(safeResume).append("\n\n")
                    .append("【参考知识点】\n").append(relatedKnowledge.isEmpty() ? "无" : relatedKnowledge).append("\n\n")
                    .append("【出题原则】\n")
                    .append("1. 题目与岗位高度相关，覆盖核心技能、项目经验、行为与场景\n")
                    .append("1.1 题目必须贴合该岗位的真实职能，避免“万金油”通用题；至少 1 道贴近真实工作场景的开放场景题\n")
                    .append("1.2 题目应聚焦考察生成答案的关键能力，不要出与岗位无关、敷衍凑数的题目\n")
                    .append(buildDifficultyRule(safeDiff))
                    .append(buildFocusRule(safeFocus))
                    .append("3. 每道题须标注唯一的 difficulty（EASY/MEDIUM/HARD）与 category，并给出贴合岗位的关键考察点 keyPoints\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON 数组，不要 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                    .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                    .append("4. 不要输出注释、解释、前后缀文字\n")
                    .append("5. 输出格式：\n")
                    .append("[{\"question\":\"请介绍你的项目架构\",\"category\":\"项目深挖\",\"difficulty\":\"MEDIUM\",\"keyPoints\":[\"考察点1\"],\"referenceAnswer\":\"参考答案要点\"}]")
                    .toString();

            // 4. 调用 AI（纳入全局并发闸门，与其它 AI Service 统一共享 5 许可，v1.31.4）
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content());

            // 5. AI 响应空值校验
            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }

            // 6. 清理 Markdown + 修复非标准 JSON
            String cleaned = JsonRepairUtil.repairAndLog(response, "interview-questions");

            // 7. 写入缓存（1 小时）
            try {
                redisTemplate.opsForValue().set(cacheKey, cleaned, 1, TimeUnit.HOURS);
            } catch (Exception e) {
                // v1.34.1（P2-5）：Redis 写入失败时落进程内兜底缓存，
                // 使无 Redis 环境下的重复出题也能命中缓存（否则每次都全额调用 LLM）
                log.warn("Redis 缓存写入失败，改用进程内缓存：{}", e.getMessage());
                redisFallbackCache.put(cacheKey, cleaned, CACHE_TTL_MILLIS);
            }

            return cleaned;
        } finally {
            questionCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            if (cacheHit) {
                log.debug("面试题缓存命中");
            }
        }
    }

    /**
     * 难度自适应规则：给定目标难度，输出对应的难度分布约束行
     */
    private String buildDifficultyRule(String diff) {
        return switch (diff) {
            case "EASY" -> "2. 难度分布：简单 60%、中等 30%、困难 10%（候选基础较薄弱，以打牢基础为主）\n";
            case "HARD" -> "2. 难度分布：简单 10%、中等 30%、困难 60%（候选基础扎实，提高挑战性）\n";
            case "MEDIUM" -> "2. 难度分布：简单 15%、中等 60%、困难 25%（候选中等水平，适度深化）\n";
            default -> "2. 难度分布：简单 30%、中等 50%、困难 20%\n";
        };
    }

    /**
     * 弱项聚焦规则：给定重点考察分类，产出一行约束
     */
    private String buildFocusRule(String focus) {
        if (focus == null || focus.isBlank()) return "";
        // v1.31.4 B-14：改为难度条目"2."的子项"2.1"，与末尾"3."形成 1,1.1,1.2,2,2.1,3 连续编号，消除重复"2."
        return "2.1 在遵循上述难度分布的前提下，优先考察以下薄弱分类（至少覆盖其中 2/3）：" + focus + "\n";
    }

    /**
     * 评估用户回答
     */
    public String evaluateAnswer(String question, String userAnswer, String referenceAnswer) {
        long start = System.nanoTime();
        try {
            // 用户输入经 PromptSanitizer 消毒，避免 prompt 注入
            String prompt = new StringBuilder()
                    .append("你是一位面试官，请评估以下回答。\n\n")
                    .append("【面试题】\n").append(PromptSanitizer.sanitize(question)).append("\n\n")
                    .append("【参考答案】\n").append(PromptSanitizer.sanitize(referenceAnswer == null ? "" : referenceAnswer)).append("\n\n")
                    .append("【用户回答】\n").append(PromptSanitizer.sanitize(userAnswer)).append("\n\n")
                    .append("请从完整性（30%）、准确性（40%）、表达能力（30%）三个维度评分，并给出改进建议。\n\n")
                    .append("【评分注意事项】\n")
                    .append("1. 先判断回答是否贴合问题：若答非所问或与题目无关，准确性应显著低分并如实说明\n")
                    .append("2. 评分须结合【参考答案】要点逐项核对，杜绝凭印象给分；分数与评语必须一致\n")
                    .append("3. strengths/weaknesses/improvements 必须对应具体内容，避免空泛套话；improvements 给出可落地的改进动作\n")
                    .append("4. 表达能力维度重点考察「经得起追问」：回答里有没有空话废话（如堆砌「负责/参与」类空动词、\n")
                    .append("   与结论无关的铺垫）、有没有贬低他人/夸大编造等风险表达，有则如实扣分并指出原句\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号 ' 或中文引号\n")
                    .append("3. 不要在字符串值中使用引号，如需引用请用书名号《》\n")
                    .append("4. 不要输出任何注释、解释、前后缀文字\n")
                    .append("5. 输出格式：\n")
                    .append("{\"overallScore\":75,\"completeness\":70,\"accuracy\":80,\"expression\":75,\"strengths\":[\"优点1\"],\"weaknesses\":[\"不足1\"],\"improvements\":[\"建议1\"]}")
                    .toString();

            // v1.23.1：纳入全局 AI 并发闸门（此前 evaluateAnswer 未受保护）
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content());

            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }

            return JsonRepairUtil.repairAndLog(response, "interview-evaluate");
        } finally {
            evaluateCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 考虑图片输入的回答评估（v1.30.0：多模态）：
     * 用户附带一张图片（如代码截图/白板草图/证书），AI 结合图片评估回答。
     * imageUrl 为空时退化为纯文本评估（等价 evaluateAnswer）。
     */
    public String evaluateAnswerWithImage(String question, String userAnswer, String referenceAnswer, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return evaluateAnswer(question, userAnswer, referenceAnswer);
        }
        long start = System.nanoTime();
        try {
            String prompt = new StringBuilder()
                    .append("你是一位面试官，请结合用户随回答附带的图片评估以下回答。\n\n")
                    .append("【面试题】\n").append(PromptSanitizer.sanitize(question)).append("\n\n")
                    .append("【参考答案】\n").append(PromptSanitizer.sanitize(referenceAnswer == null ? "" : referenceAnswer)).append("\n\n")
                    .append("【用户回答】\n").append(PromptSanitizer.sanitize(userAnswer)).append("\n\n")
                    .append("【图片说明】\n图片为用户作答时的附图（如代码截图/草图/证书），请结合图片内容与文字作答综合评估。\n\n")
                    .append("请从完整性（30%）、准确性（40%）、表达能力（30%）三个维度评分，并给出改进建议。\n\n")
                    .append("【评分注意事项】\n")
                    .append("1. 若图片与回答无关或无法解读，需在 improvements 中如实指出\n")
                    .append("2. 评分须结合【参考答案】要点逐项核对，杜绝凭印象给分；分数与评语必须一致\n")
                    .append("3. 若附图是代码，请检查代码正确性并针对性点评\n\n")
                    .append("【输出要求（务必严格遵守）】\n")
                    .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                    .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号 ' 或中文引号\n")
                    .append("3. 不要在字符串值中使用引号，如需引用请用书名号《》\n")
                    .append("4. 不要输出任何注释、解释、前后缀文字\n")
                    .append("5. 输出格式：\n")
                    .append("{\"overallScore\":75,\"completeness\":70,\"accuracy\":80,\"expression\":75,\"strengths\":[\"优点1\"],\"weaknesses\":[\"不足1\"],\"improvements\":[\"建议1\"]}")
                    .toString();

            // 组装图片 Media：AGNES-2.5-flash 支持 image_url 视觉理解
            Media media = new Media(detectMimeType(imageUrl), URI.create(imageUrl));
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user((userSpec) -> userSpec.text(prompt).media(media))
                            .call()
                            .content());

            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }

            return JsonRepairUtil.repairAndLog(response, "interview-evaluate-image");
        } finally {
            evaluateCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 依据图片 URL 后缀推断 MIME 类型，未知默认 image/png */
    private MimeType detectMimeType(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) {
            return MimeType.valueOf("image/jpeg");
        }
        if (lower.endsWith(".gif")) {
            return MimeType.valueOf("image/gif");
        }
        if (lower.endsWith(".webp")) {
            return MimeType.valueOf("image/webp");
        }
        return MimeType.valueOf("image/png");
    }

    /**
     * 针对性追问（v1.28.0：追问链加深）：基于候选人对原题的回答 + 简历要点，
     * 生成一道下钻细节/定位盲区的追问。返回纯文本问题（非 JSON）。
     */
    public String generateFollowUp(String question, String userAnswer, String resumeText) {
        long start = System.nanoTime();
        try {
            String prompt = new StringBuilder()
                    .append("你是一位专业面试官，正在深挖一位候选人的技术积累。请根据其简历与刚才的回答，追问一道能深挖细节的问题。\n\n")
                    .append("【原题】\n").append(PromptSanitizer.sanitize(question)).append("\n\n")
                    .append("【候选人的回答】\n").append(PromptSanitizer.sanitize(userAnswer == null ? "" : userAnswer)).append("\n\n")
                    .append("【候选人的简历要点】\n").append(PromptSanitizer.sanitize(resumeText == null ? "" : resumeText)).append("\n\n")
                    .append("【追问原则】\n")
                    .append("1. 针对回答中最薄弱、最含糊或最有价值的一点下钻，绝不重复原题\n")
                    .append("2. 若回答较空泛，优先围绕简历中的具体项目/技术栈/量化结果展开\n")
                    .append("3. 只输出一道追问的一句话本身：不加编号、不加解释、不加引号")
                    .toString();

            // 纳入全局 AI 并发闸门，与 evaluateAnswer 一致
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content());

            if (response == null || response.isBlank()) {
                throw new com.example.interview.common.BusinessException("AI 返回内容为空，请稍后重试");
            }
            return response.trim();
        } finally {
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}

