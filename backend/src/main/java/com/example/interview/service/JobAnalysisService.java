package com.example.interview.service;

import com.example.interview.util.JsonRepairUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 岗位分析服务（参考 Easy-Job-Tutor 项目设计）
 * - JD 岗位分析：拆解职责、硬技能、软技能、隐性条件、关键词
 * - 差距诊断：简历 vs JD 逐条对比（强证据 / 弱证据 / 缺口）
 * - 求职信生成：Cover Letter / 申请邮件 / 内推私信
 */
@Service
public class JobAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JobAnalysisService.class);

    @Autowired
    private ChatClient chatClient;

    /** AI 并发控制（与其他 Service 共享限流理念） */
    private static final java.util.concurrent.Semaphore AI_SEMAPHORE = new java.util.concurrent.Semaphore(5);

    /** 简历/JD 文本最大长度（截断后送 AI，避免 prompt 过长拖慢推理） */
    private static final int MAX_TEXT_LEN = 1200;

    /**
     * JD 岗位分析：拆解岗位要求
     *
     * @param jobDescription 岗位描述（JD）全文
     * @return 分析结果 JSON 字符串
     */
    public String analyzeJobDescription(String jobDescription) {
        String truncatedJd = truncate(jobDescription, MAX_TEXT_LEN);
        String safeJd = sanitizePromptInput(truncatedJd);
        String prompt = new StringBuilder()
                .append("你是一位资深 HR 和招聘专家，请深度分析以下岗位描述（JD）。\n\n")
                .append("【岗位描述】\n").append(safeJd).append("\n\n")
                .append("请从以下维度拆解这个岗位：\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符；如需换行请用分号分隔\n")
                .append("4. 不要输出任何注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\n")
                .append("  \"jobTitle\": \"岗位名称\",\n")
                .append("  \"summary\": \"一句话概括这个岗位做什么\",\n")
                .append("  \"responsibilities\": [\"职责1\", \"职责2\"],\n")
                .append("  \"hardSkills\": [\"硬技能1\", \"硬技能2\"],\n")
                .append("  \"softSkills\": [\"软技能1\", \"软技能2\"],\n")
                .append("  \"hiddenRequirements\": [\"JD没明说但HR会看重的隐性条件1\"],\n")
                .append("  \"keywords\": [\"ATS关键词1\", \"关键词2\"],\n")
                .append("  \"seniorityLevel\": \"初级/中级/高级\",\n")
                .append("  \"salaryRange\": \"薪资范围预估（如无法判断写未知）\",\n")
                .append("  \"matchTips\": [\"求职者应在简历中突出什么1\", \"建议2\"]\n")
                .append("}")
                .toString();

        return callAi(prompt, "jd-analyze");
    }

    /**
     * 差距诊断：简历 vs JD 逐条对比
     */
    public String diagnoseGap(String resumeText, String jobDescription) {
        String truncatedResume = truncate(resumeText, MAX_TEXT_LEN);
        String truncatedJd = truncate(jobDescription, MAX_TEXT_LEN);
        String safeJd = sanitizePromptInput(truncatedJd);
        String safeResume = sanitizePromptInput(truncatedResume);
        String prompt = new StringBuilder()
                .append("你是一位资深招聘面试官，请逐条对比以下简历和岗位要求，进行差距诊断。\n\n")
                .append("【目标岗位描述】\n").append(safeJd).append("\n\n")
                .append("【候选人简历】\n").append(safeResume).append("\n\n")
                .append("请从岗位的每个核心要求出发，逐条判断候选人的匹配情况：\n")
                .append("- ✅ 强证据（strong）：简历中有明确的经历/技能直接对应此要求\n")
                .append("- ⚠️ 弱证据（weak）：沾边但写得不够出彩或不够直接\n")
                .append("- ❌ 缺口（gap）：这个要求简历中完全没有素材，需要补充\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON，不要任何 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 所有字符串必须使用 ASCII 双引号 \"，禁止使用单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出任何注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\n")
                .append("  \"overallMatchScore\": 75,\n")
                .append("  \"summary\": \"一句话总结匹配情况\",\n")
                .append("  \"items\": [\n")
                .append("    {\n")
                .append("      \"requirement\": \"岗位要求1\",\n")
                .append("      \"status\": \"strong\",\n")
                .append("      \"evidence\": \"简历中的对应证据\",\n")
                .append("      \"suggestion\": \"如何更好地展示或补充\"\n")
                .append("    }\n")
                .append("  ],\n")
                .append("  \"strengths\": [\"候选人的核心优势1\"],\n")
                .append("  \"gaps\": [\"需要补充的缺口1\"],\n")
                .append("  \"actionItems\": [\"下一步行动建议1\"]\n")
                .append("}")
                .toString();

        return callAi(prompt, "gap-diagnosis");
    }

    /**
     * 求职信/申请邮件/内推私信生成
     */
    public String generateLetter(String resumeText, String jobDescription, String type) {
        String truncatedResume = truncate(resumeText, MAX_TEXT_LEN);
        String truncatedJd = truncate(jobDescription, 600);

        String typeDesc = switch (type) {
            case "email" -> "一封专业的申请邮件（投递简历时发送给 HR），邮件需包含：邮件主题、简短问候、正文（说明应聘岗位、核心优势、求职意向）、结尾礼仪语。语气专业但不生硬。";
            case "referral" -> "一段内推私信（发给你认识的人请求内推），语气真诚自然，像朋友间的对话。包含：简短问候、说明想应聘的岗位、为什么觉得自己合适、请求帮忙内推、感谢语。控制在 200 字以内。";
            default -> "一封正式的求职信（Cover Letter），结构包含：开场（说明应聘岗位和渠道）、核心匹配（2-3 段，每段对应一个岗位要求与简历证据的匹配）、结尾（表达热情和面试期望）。语气专业自信。";
        };

        String safeJd = sanitizePromptInput(truncatedJd);
        String safeResume = sanitizePromptInput(truncatedResume);
        String prompt = new StringBuilder()
                .append("你是一位求职辅导专家，请基于以下简历和岗位信息，生成").append(typeDesc).append("\n\n")
                .append("【目标岗位】\n").append(safeJd).append("\n\n")
                .append("【候选人简历】\n").append(safeResume).append("\n\n")
                .append("【输出要求】\n")
                .append("1. 直接输出正文内容，不要任何前后缀解释\n")
                .append("2. 基于简历中的真实经历，不要编造任何信息\n")
                .append("3. 突出与岗位的匹配度，用量化数据说话\n")
                .append("4. 语气自然得体，避免模板化套话")
                .toString();

        String response = callAiRaw(prompt, "letter-" + type);
        // 剥离可能的 Markdown 代码块包裹
        if (response.startsWith("```")) {
            response = response.replaceAll("^```(?:markdown|md)?\\s*\\n", "").replaceAll("\\n```\\s*$", "");
        }
        return response.trim();
    }

    /**
     * Prompt 注入防御：剥离指令性模式 + 截断超长输入
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

    // ─────────────────────────── 内部工具方法 ───────────────────────────

    /** 调用 AI 并返回修复后的 JSON 字符串 */
    private String callAi(String prompt, String logTag) {
        String response = callAiRaw(prompt, logTag);
        String cleaned = JsonRepairUtil.repairAndLog(response, logTag);
        if (!JsonRepairUtil.isValid(cleaned)) {
            log.warn("[{}] AI 返回修复后仍非法，原始返回前 200 字符：{}", logTag,
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            cleaned = "{\"error\":\"AI 返回内容无法解析，请稍后重试\"}";
        }
        return cleaned;
    }

    /** 调用 AI 返回原始文本（带并发控制） */
    private String callAiRaw(String prompt, String logTag) {
        try {
            AI_SEMAPHORE.acquire();
            try {
                String response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("AI 返回内容为空，请稍后重试");
                }
                return response;
            } finally {
                AI_SEMAPHORE.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断", e);
        }
    }

    /** 截断文本，避免 prompt 过长拖慢推理 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
