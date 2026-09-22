package com.example.interview.service.career;

import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 定制简历生成服务（v1.35.0）
 *
 * <p>设计来源：抖音「求职 agent 大更新」（作者：跑跑蹦蹦跳跳）介绍的 BossHunter 思路——
 * 投递不是海投，而是「针对每个岗位定制简历」。本服务把该能力做成一次 AI 调用：
 * 输入原始简历要点 + 目标岗位 JD，输出「对齐 JD 关键词后的重写要点 + 缺失关键词 + 改写理由」。
 *
 * <p>与「简历分析」的区别：分析是打分与诊断，定制是按岗位重排与重述，
 * 产出可直接粘贴进简历的条目。
 *
 * <p>合规：只做文本改写，不伪造经历、不虚构未掌握的技能——缺失的能力只能出现在
 * {@code missingKeywords} 中作为「待补」提示，绝不允许写进 rewrittenBullets。
 */
@Service
public class TailoredResumeService {

    private static final Logger log = LoggerFactory.getLogger(TailoredResumeService.class);

    /** 简历文本最大长度 */
    private static final int MAX_RESUME_LEN = 2500;

    /** 岗位描述最大长度 */
    private static final int MAX_JOB_LEN = 1500;

    /** 兜底 JSON（结构等价，保证前端字段可读） */
    static final String TAILOR_FALLBACK_JSON = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
            + "\"rewrittenBullets\":[],\"summary\":\"\",\"suggestions\":[]}";

    @Autowired
    private ChatClient chatClient;

    /**
     * 生成针对某岗位的定制简历要点
     *
     * @param resumeText  用户原始简历文本或经历要点
     * @param jobTitle    目标岗位名称
     * @param jobDetail   目标岗位 JD/要求（可为空）
     * @return 结构化 JSON 字符串（已修复为合法 JSON）
     */
    public String tailor(String resumeText, String jobTitle, String jobDetail) {
        String safeResume = PromptSanitizer.sanitize(TextUtil.truncate(resumeText, MAX_RESUME_LEN));
        String safeTitle = PromptSanitizer.sanitize(TextUtil.truncate(jobTitle, 200));
        String safeDetail = PromptSanitizer.sanitize(TextUtil.truncate(
                jobDetail == null ? "" : jobDetail, MAX_JOB_LEN));

        String prompt = new StringBuilder()
                .append("你是资深简历顾问。请针对下面这个具体岗位，把候选人的简历要点做「定制化重写」，")
                .append("提高与岗位的匹配度与可读性。\n\n")
                .append("【目标岗位】\n").append(safeTitle.isBlank() ? "未提供" : safeTitle).append("\n\n")
                .append("【岗位 JD / 要求】\n").append(safeDetail.isBlank() ? "未提供" : safeDetail).append("\n\n")
                .append("【候选人原始简历要点】\n").append(safeResume.isBlank() ? "未提供" : safeResume).append("\n\n")
                .append("【重写要求】\n")
                .append("1. 严禁编造：不得新增候选人未提及的经历、技能、公司、数字或奖项\n")
                .append("2. rewrittenBullets：挑出 3-6 条最值得改写的原始条目，保持事实不变的前提下\n")
                .append("   ① 把与 JD 相关的关键词前置 ② 用「动作 + 方法 + 结果」结构重述 ③ 让量化结果更突出；\n")
                .append("   reason 用一句话说明「为什么这样改更贴合该岗位」\n")
                .append("3. matchedKeywords：候选人已具备、且 JD 明确要求的关键词\n")
                .append("4. missingKeywords：JD 要求但候选人材料中未体现的关键词——只做「待补提示」，不得写进改写条目\n")
                .append("5. summary：用 3 句话给出这份简历针对该岗位的定位陈述（可放入简历开头的个人简介）\n")
                .append("6. suggestions：3-5 条针对该岗位的简历优化建议（如调整模块顺序、补充哪类项目）\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"matchedKeywords\":[\"关键词\"],\"missingKeywords\":[\"待补关键词\"],")
                .append("\"rewrittenBullets\":[{\"original\":\"原条目\",\"rewritten\":\"改写后\",\"reason\":\"理由\"}],")
                .append("\"summary\":\"定位陈述\",\"suggestions\":[\"建议\"]}")
                .toString();

        String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                chatClient.prompt().user(prompt).call().content());
        if (response == null || response.isBlank()) {
            log.warn("定制简历模型返回为空，使用兜底结构");
            response = "";
        }
        return JsonRepairUtil.repairOrFallback(response, "career-tailor-resume", TAILOR_FALLBACK_JSON);
    }
}
