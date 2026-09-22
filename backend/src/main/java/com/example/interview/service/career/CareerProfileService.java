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
 * 求职 Skill —— 职业资产挖掘与求职节奏规划（v1.35.0）
 *
 * <p>设计来源：抖音「VibeCoding大赏｜求职Skill」（作者：GK同学）所述方法论。其核心洞察是——
 * <b>求职的第一步不是写简历，而是挖掘自己的长处与特质</b>；很多人并非没有能力，
 * 而是说不清自己的能力。该 Skill 采用「证据 → 行为 → 能力 → 可投岗位信号」四层结构，
 * 把口语化的真实经历逐层抽象为可迁移能力与可投岗位方向；第二个环节则是
 * 「有节奏地找岗位并投递」：为什么适合、差距在哪、30 天补什么 case、适合什么赛道的公司。
 *
 * <p>本类把上述方法论实现为两个 AI 能力：
 * <ol>
 *   <li>{@link #mine(String, String, String)}：职业资产四层挖掘（长处/特质 → 能力标签 → 岗位信号）；</li>
 *   <li>{@link #plan(String, String, String, String)}：岗位节奏计划（why-fit / gap / 30 天 case / 赛道）。</li>
 * </ol>
 *
 * <p>工程约定（与项目其它 AI 服务一致）：输入经 {@link PromptSanitizer} 消毒 + 长度截断；
 * 调用纳入 {@code AiConcurrencyGuard} 全局并发闸门；输出统一走 {@link JsonRepairUtil} 修复，
 * 修复失败时回退到结构等价的兜底 JSON，保证前端永不因解析失败白屏。
 */
@Service
public class CareerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CareerProfileService.class);

    /** 用户自述经历最大长度（截断后送 AI，避免 prompt 过长拖慢推理） */
    private static final int MAX_NARRATIVE_LEN = 3000;

    /** 简历文本最大长度 */
    private static final int MAX_RESUME_LEN = 2000;

    /** 目标岗位/JD 最大长度 */
    private static final int MAX_JOB_LEN = 1200;

    /** 挖掘结果兜底 JSON（结构等价，保证前端字段可读） */
    static final String MINE_FALLBACK_JSON = "{\"positioning\":\"\",\"assets\":[],"
            + "\"strengthTags\":[],\"blindSpots\":[],\"nextSteps\":[]}";

    /** 节奏计划兜底 JSON（v1.36.0 增加 applyAdvice 岗位假设卡） */
    static final String PLAN_FALLBACK_JSON = "{\"whyFit\":\"\",\"gaps\":[],"
            + "\"thirtyDayPlan\":[],\"tracks\":[],\"cadence\":[],"
            + "\"applyAdvice\":{\"score\":0,\"matchedEvidence\":[],\"verdict\":\"OBSERVE\",\"reason\":\"\"}}";

    @Autowired
    private ChatClient chatClient;

    /**
     * 职业资产四层挖掘：证据 → 行为 → 能力 → 可投岗位信号
     *
     * @param userId       用户 ID（仅用于日志与限流隔离，不进入 prompt）
     * @param narrative    用户口语化的真实经历自述（可多段）
     * @param targetTrack  可选，期望的赛道/岗位方向（如「Java 后端」「数据分析」）
     * @return 结构化 JSON 字符串（已修复为合法 JSON）
     */
    public String mine(String userId, String narrative, String targetTrack) {
        String safeNarrative = PromptSanitizer.sanitize(TextUtil.truncate(narrative, MAX_NARRATIVE_LEN));
        String safeTrack = PromptSanitizer.sanitize(
                TextUtil.truncate(targetTrack == null ? "" : targetTrack, 100));

        String prompt = new StringBuilder()
                .append("你是资深职业发展教练，擅长用「证据→行为→能力→可投岗位信号」四层结构，")
                .append("帮求职者从真实经历中挖出可迁移能力。很多人的问题不是没能力，而是说不清自己的能力。\n\n")
                .append("【求职者自述的真实经历】\n").append(safeNarrative).append("\n\n")
                .append("【期望方向（可为空，为空则由你推断）】\n")
                .append(safeTrack.isBlank() ? "未指定" : safeTrack).append("\n\n")
                .append("【分析要求】\n")
                .append("1. 严格基于自述内容，禁止编造未提及的经历、数字、公司或奖项；自述含糊处按最保守理解\n")
                .append("2. 四层结构：\n")
                .append("   - evidence 证据：可验证的客观事实（做了什么、结果/数字/规模、被谁认可）\n")
                .append("   - behavior 行为：面对什么问题、采取了哪些具体动作、如何取舍（STAR 化）\n")
                .append("   - capability 能力：从行为中抽象出的可迁移能力（用「XX能力」表述，不写空话）\n")
                .append("   - jobSignals 可投岗位信号：该能力直接指向的岗位/岗位族关键词\n")
                .append("3. 至少挖掘 3 条资产；每条四层都要具体，禁止「沟通能力强」这类无证据的套话\n")
                .append("4. blindSpots 只写「自述中缺失、但目标岗位通常会考察」的信息缺口，用于提示用户补充\n")
                .append("5. nextSteps 给出 3-5 条立刻可执行的下一步（如「补一段量化结果」「确认目标城市」）\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"positioning\":\"一句话职业定位\",")
                .append("\"assets\":[{\"evidence\":\"证据\",\"behavior\":\"行为\",\"capability\":\"能力\",\"jobSignals\":[\"岗位信号\"]}],")
                .append("\"strengthTags\":[\"优势标签\"],\"blindSpots\":[\"信息缺口\"],\"nextSteps\":[\"下一步\"]}")
                .toString();

        String raw = callAi(prompt);
        return JsonRepairUtil.repairOrFallback(raw, "career-mine", MINE_FALLBACK_JSON);
    }

    /**
     * 岗位节奏计划：为什么适合 / 差距在哪 / 30 天补什么 case / 适合什么赛道
     *
     * @param userId     用户 ID
     * @param targetJob  目标岗位（岗位名 + 可选 JD 片段）
     * @param resumeText 用户简历要点或职业资产摘要
     * @param mineSummary 可选，{@link #mine} 的产出摘要，用于让计划与挖掘结果对齐
     * @return 结构化 JSON 字符串（已修复为合法 JSON）
     */
    public String plan(String userId, String targetJob, String resumeText, String mineSummary) {
        String safeJob = PromptSanitizer.sanitize(TextUtil.truncate(targetJob, MAX_JOB_LEN));
        String safeResume = PromptSanitizer.sanitize(TextUtil.truncate(resumeText, MAX_RESUME_LEN));
        String safeMine = PromptSanitizer.sanitize(
                TextUtil.truncate(mineSummary == null ? "" : mineSummary, MAX_RESUME_LEN));

        String prompt = new StringBuilder()
                .append("你是资深求职顾问。请基于候选人的真实材料，输出一份「有节奏地找岗位并投递」的行动计划。\n\n")
                .append("【目标岗位 / JD】\n").append(safeJob.isBlank() ? "未指定" : safeJob).append("\n\n")
                .append("【候选人简历要点】\n").append(safeResume.isBlank() ? "未提供" : safeResume).append("\n\n")
                .append("【职业资产挖掘摘要（可为空）】\n").append(safeMine.isBlank() ? "无" : safeMine).append("\n\n")
                .append("【分析要求】\n")
                .append("1. 严格基于给定材料，禁止编造候选人不具备的经历或技能；材料不足时在 gaps 中如实指出\n")
                .append("2. whyFit：用 3-5 句话说明「为什么他适合这个岗位」，每条都要挂钩材料中的具体证据\n")
                .append("3. gaps：候选人与目标岗位的差距，level 取 HIGH/MEDIUM/LOW，action 给出补齐动作\n")
                .append("4. thirtyDayPlan：恰好 4 周（第1周~第4周），每周含 focus、tasks（2-4 条）、deliverable（可验收产出）\n")
                .append("   —— deliverable 必须是能拿出来给面试官看的东西（如「一个可演示的 demo」「一份量化过的项目复盘」）\n")
                .append("5. tracks：3 个适合的赛道/公司类型，每个含 track、companies（公司类型或真实公司名，不得编造不存在的公司）、reason\n")
                .append("6. cadence：投递节奏建议（如「每周投递 8-10 家，A 类精准投递占比 60%」）\n")
                .append("7. applyAdvice 岗位假设卡：不是推荐岗位，而是生成可证伪的岗位假设——\n")
                .append("   - score：假设匹配分 0-100 整数（证据越实、缺口越小分越高，禁止拍脑袋给高分）\n")
                .append("   - matchedEvidence：2-4 条匹配证据，每条必须挂钩材料中的具体事实\n")
                .append("   - verdict：score 低于 60 必须为 OBSERVE（不建议投递，只进入观察池）；\n")
                .append("     60-74 为 OBSERVE（补齐关键差距后再投）；75 及以上为 APPLY（建议投递）\n")
                .append("   - reason：一句话说明为什么是这个结论\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"whyFit\":\"为什么适合\",")
                .append("\"gaps\":[{\"item\":\"差距\",\"level\":\"HIGH\",\"action\":\"补齐动作\"}],")
                .append("\"thirtyDayPlan\":[{\"week\":\"第1周\",\"focus\":\"重点\",\"tasks\":[\"任务\"],\"deliverable\":\"产出\"}],")
                .append("\"tracks\":[{\"track\":\"赛道\",\"companies\":[\"公司类型\"],\"reason\":\"原因\"}],")
                .append("\"cadence\":[\"节奏建议\"],")
                .append("\"applyAdvice\":{\"score\":72,\"matchedEvidence\":[\"匹配证据\"],\"verdict\":\"APPLY\",\"reason\":\"结论理由\"}}")
                .toString();

        String raw = callAi(prompt);
        return JsonRepairUtil.repairOrFallback(raw, "career-plan", PLAN_FALLBACK_JSON);
    }

    /** 统一 AI 调用：空值校验 + 全局并发闸门（与项目其它 AI 服务一致） */
    private String callAi(String prompt) {
        String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                chatClient.prompt().user(prompt).call().content());
        if (response == null || response.isBlank()) {
            log.warn("求职 Skill 模型返回为空，使用兜底结构");
            return "";
        }
        return response;
    }
}
