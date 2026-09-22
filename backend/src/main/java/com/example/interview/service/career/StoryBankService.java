package com.example.interview.service.career;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.StoryBankEntity;
import com.example.interview.repository.StoryBankRepository;
import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试故事库服务（v1.36.0）
 *
 * <p>设计来源：抖音「VibeCoding大赏｜求职Skill」（作者：GK同学）图文中的
 * 「面试不是背答案，是经得起追问」与产物 {@code interview-story-bank.md} / {@code interview-practice.md}：
 * <ul>
 *   <li><b>提炼</b> {@link #extract}：从口语化自述/简历要点中提炼 2-4 条 STAR 故事候选
 *       （严格基于自述内容，禁止编造），由用户确认后入库；</li>
 *   <li><b>质检</b> {@link #check}：对用户的模拟回答跑「六项质检」——结构是否清楚 /
 *       证据是否具体 / 是否贴合岗位 / 有没有废话 / 有没有风险表达 / 能否被追问住；</li>
 *   <li><b>追问</b> {@link #followUp}：「先回答，再追问，再复盘」——针对回答生成追问链。</li>
 * </ul>
 *
 * <p>工程约定（与 {@link CareerProfileService} 一致）：输入经 {@link PromptSanitizer} 消毒 + 截断；
 * 调用纳入 {@code AiConcurrencyGuard}；输出走 {@link JsonRepairUtil#repairOrFallback} 兜底。
 */
@Service
public class StoryBankService {

    private static final Logger log = LoggerFactory.getLogger(StoryBankService.class);

    /** 用户自述/简历要点最大长度 */
    private static final int MAX_NARRATIVE_LEN = 3000;

    /** 目标岗位/赛道最大长度 */
    private static final int MAX_TRACK_LEN = 200;

    /** 用户模拟回答最大长度 */
    private static final int MAX_ANSWER_LEN = 3000;

    /** 单用户故事库容量上限（免费层防御性限制） */
    private static final int MAX_STORIES_PER_USER = 50;

    /** 六项质检项（顺序固定，与前端约定一致） */
    public static final List<String> CHECK_ITEMS = List.of(
            "结构是否清楚", "证据是否具体", "是否贴合岗位", "有没有废话", "有没有风险表达", "能否被追问住");

    static final String EXTRACT_FALLBACK_JSON =
            "{\"stories\":[]}";
    static final String CHECK_FALLBACK_JSON =
            "{\"items\":[],\"overallScore\":0,\"suggestion\":\"质检结果解析失败，请重试\"}";
    static final String FOLLOWUP_FALLBACK_JSON =
            "{\"followups\":[]}";

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private StoryBankRepository repository;

    // ---------- 故事 CRUD ----------

    /** 查询用户全部故事（最近更新倒序） */
    public List<StoryBankEntity> list(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** 归属校验后取单条故事，非本人返回 null（防 IDOR） */
    public StoryBankEntity findOwned(String userId, Long id) {
        if (id == null) {
            return null;
        }
        return repository.findByIdAndUserId(id, userId).orElse(null);
    }

    /**
     * 保存故事（由用户从提炼候选中确认，或手动创建）
     *
     * @return 已保存实体（含生成 ID）
     */
    public StoryBankEntity save(String userId, StoryBankEntity story) {
        if (repository.countByUserId(userId) >= MAX_STORIES_PER_USER) {
            throw new BusinessException("故事库已满（上限 " + MAX_STORIES_PER_USER + " 条），请先删除不再需要的故事");
        }
        story.setId(null);
        story.setUserId(userId);
        if (story.getTitle() == null || story.getTitle().isBlank()) {
            throw new BusinessException("故事标题不能为空");
        }
        return repository.save(story);
    }

    /**
     * 更新故事内容（仅允许本人，保留创建时间与已有质检结果）。
     *
     * <p>采用<b>部分更新语义</b>：patch 中为 null 的字段不覆盖原值，
     * 避免前端只传部分字段时把 STAR 其余层误清空。
     */
    public StoryBankEntity update(String userId, Long id, StoryBankEntity patch) {
        StoryBankEntity existing = findOwned(userId, id);
        if (existing == null) {
            throw new BusinessException("故事不存在或无权操作");
        }
        if (patch.getTitle() != null && !patch.getTitle().isBlank()) {
            existing.setTitle(patch.getTitle().trim());
        }
        if (patch.getSituation() != null) {
            existing.setSituation(patch.getSituation());
        }
        if (patch.getTask() != null) {
            existing.setTask(patch.getTask());
        }
        if (patch.getAction() != null) {
            existing.setAction(patch.getAction());
        }
        if (patch.getResult() != null) {
            existing.setResult(patch.getResult());
        }
        if (patch.getEvidence() != null) {
            existing.setEvidence(patch.getEvidence());
        }
        if (patch.getCapabilityTags() != null) {
            existing.setCapabilityTags(patch.getCapabilityTags());
        }
        if (patch.getTargetTrack() != null) {
            existing.setTargetTrack(patch.getTargetTrack());
        }
        return repository.save(existing);
    }

    /** 保存最近一次质检结果快照（仅允许本人；失败由调用方兜底） */
    public void updateCheckResult(String userId, Long id, String checkResultJson) {
        StoryBankEntity existing = findOwned(userId, id);
        if (existing == null) {
            return;
        }
        existing.setCheckResult(checkResultJson);
        repository.save(existing);
    }

    /** 删除故事（仅允许本人） */
    public void delete(String userId, Long id) {
        StoryBankEntity existing = findOwned(userId, id);
        if (existing == null) {
            throw new BusinessException("故事不存在或无权操作");
        }
        repository.delete(existing);
    }

    // ---------- AI 能力 ----------

    /**
     * 从口语化自述/简历要点中提炼 STAR 故事候选（不入库，由用户确认后保存）
     *
     * @param userId      用户 ID（仅日志）
     * @param narrative   口语化经历自述或简历要点
     * @param targetTrack 可选，期望赛道/岗位方向
     * @return 结构化 JSON 字符串：{"stories":[{"title","situation","task","action","result","evidence","capabilities":[]}]}
     */
    public String extract(String userId, String narrative, String targetTrack) {
        String safeNarrative = PromptSanitizer.sanitize(TextUtil.truncate(narrative, MAX_NARRATIVE_LEN));
        String safeTrack = PromptSanitizer.sanitize(
                TextUtil.truncate(targetTrack == null || targetTrack.isBlank() ? "" : targetTrack, MAX_TRACK_LEN));

        String prompt = new StringBuilder()
                .append("你是资深面试教练。面试不是背答案，是经得起追问；")
                .append("请把求职者的真实经历整理成 STAR 结构的面试故事候选。\n\n")
                .append("【求职者的经历自述 / 简历要点】\n").append(safeNarrative).append("\n\n")
                .append("【期望方向（可为空）】\n").append(safeTrack.isBlank() ? "未指定" : safeTrack).append("\n\n")
                .append("【提炼要求】\n")
                .append("1. 严格基于自述内容，禁止编造未提及的经历、数字、公司或奖项；自述含糊处按最保守理解\n")
                .append("2. 提炼 2-4 条最有面试价值的故事；每条对应一段独立经历或同一经历的不同侧面，不要重复\n")
                .append("3. 每条包含：title（一句话标题）、situation（背景）、task（你的目标与职责）、\n")
                .append("   action（具体动作与取舍，禁止「参与/协助/负责」这类空动词）、result（结果，尽量量化）、\n")
                .append("   evidence（证据链摘要：可验证的客观事实）、capabilities（2-4 个能力标签）\n")
                .append("4. 自述信息不足以支撑 STAR 某一层时，该字段如实写「待补充：……」，不要虚构\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"stories\":[{\"title\":\"标题\",\"situation\":\"情境\",\"task\":\"任务\",\"action\":\"行动\",")
                .append("\"result\":\"结果\",\"evidence\":\"证据\",\"capabilities\":[\"能力标签\"]}]}")
                .toString();

        String raw = callAi(prompt);
        return JsonRepairUtil.repairOrFallback(raw, "story-extract", EXTRACT_FALLBACK_JSON);
    }

    /**
     * 六项质检：对用户的模拟回答跑「结构/证据/贴合岗位/废话/风险表达/经得起追问」检查
     *
     * @param story  故事实体（质检对象）
     * @param answer 用户对该故事的模拟口述回答
     * @return 结构化 JSON：{"items":[{"name","pass","comment"}],"overallScore","suggestion"}
     */
    public String check(StoryBankEntity story, String answer) {
        String safeAnswer = PromptSanitizer.sanitize(TextUtil.truncate(answer, MAX_ANSWER_LEN));
        String safeStory = PromptSanitizer.sanitize(TextUtil.truncate(renderStory(story), MAX_NARRATIVE_LEN));
        String safeTrack = PromptSanitizer.sanitize(
                TextUtil.truncate(story.getTargetTrack() == null ? "" : story.getTargetTrack(), MAX_TRACK_LEN));

        String prompt = new StringBuilder()
                .append("你是严格但善意的面试官。候选人即将用这段故事回答面试题，请对其口述回答做六项质检。\n\n")
                .append("【故事底稿（STAR）】\n").append(safeStory).append("\n\n")
                .append("【目标岗位/方向（可为空）】\n").append(safeTrack.isBlank() ? "未指定" : safeTrack).append("\n\n")
                .append("【候选人的口述回答】\n").append(safeAnswer).append("\n\n")
                .append("【质检项（固定六项，逐项给结论）】\n")
                .append("1. 结构是否清楚：情境/任务/行动/结果是否讲得有条理\n")
                .append("2. 证据是否具体：有没有可验证的事实、数字、结果，还是全是自我评价\n")
                .append("3. 是否贴合岗位：内容与目标岗位的考察点是否相关\n")
                .append("4. 有没有废话：有没有空话套话、与结论无关的铺垫\n")
                .append("5. 有没有风险表达：有没有贬低他人/泄密/夸大编造等面试减分表达\n")
                .append("6. 能否被追问住：讲了细节后是否经得起面试官继续下钻\n\n")
                .append("【评分要求】\n")
                .append("1. 每项 pass 为布尔值；comment 用一句话指出具体问题（引用回答原文的关键词）\n")
                .append("2. overallScore 为 0-100 整数：六项全过为 85+，每有一项不过明显减分\n")
                .append("3. suggestion 给出 1-3 句可落地的修改建议，具体到「把哪句改成什么样」\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"items\":[{\"name\":\"结构是否清楚\",\"pass\":true,\"comment\":\"一句话点评\"}],")
                .append("\"overallScore\":75,\"suggestion\":\"修改建议\"}")
                .toString();

        String raw = callAi(prompt);
        return JsonRepairUtil.repairOrFallback(raw, "story-check", CHECK_FALLBACK_JSON);
    }

    /**
     * 追问链：「先回答，再追问，再复盘」——针对候选人的回答生成 3 条追问
     *
     * @return 结构化 JSON：{"followups":["追问1","追问2","追问3"]}
     */
    public String followUp(StoryBankEntity story, String answer) {
        String safeAnswer = PromptSanitizer.sanitize(TextUtil.truncate(answer, MAX_ANSWER_LEN));
        String safeStory = PromptSanitizer.sanitize(TextUtil.truncate(renderStory(story), MAX_NARRATIVE_LEN));

        String prompt = new StringBuilder()
                .append("你是专业面试官。候选人用一段真实经历回答了面试题，请生成追问来检验他是否真的做过、真的想清楚过。\n\n")
                .append("【故事底稿（STAR）】\n").append(safeStory).append("\n\n")
                .append("【候选人的口述回答】\n").append(safeAnswer).append("\n\n")
                .append("【追问原则】\n")
                .append("1. 恰好 3 条，由浅入深：第 1 条问细节还原（具体怎么做的），第 2 条问取舍与替代方案（为什么这么做），第 3 条问反思与延伸（重来会怎么做/如何规模化）\n")
                .append("2. 优先围绕回答中最含糊、最容易被背稿糊弄过去的点\n")
                .append("3. 每条只输出问题本身的一句话，不加编号、不加解释\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：{\"followups\":[\"追问1\",\"追问2\",\"追问3\"]}")
                .toString();

        String raw = callAi(prompt);
        return JsonRepairUtil.repairOrFallback(raw, "story-followup", FOLLOWUP_FALLBACK_JSON);
    }

    /** 把故事渲染为可读的 STAR 底稿（供 prompt 使用） */
    private String renderStory(StoryBankEntity s) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(s.getTitle() == null ? "" : s.getTitle()).append("\n");
        sb.append("情境：").append(nullToBlank(s.getSituation())).append("\n");
        sb.append("任务：").append(nullToBlank(s.getTask())).append("\n");
        sb.append("行动：").append(nullToBlank(s.getAction())).append("\n");
        sb.append("结果：").append(nullToBlank(s.getResult())).append("\n");
        sb.append("证据：").append(nullToBlank(s.getEvidence()));
        return sb.toString();
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    /** 统一 AI 调用：空值校验 + 全局并发闸门（与其它 AI 服务一致） */
    private String callAi(String prompt) {
        String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                chatClient.prompt().user(prompt).call().content());
        if (response == null || response.isBlank()) {
            log.warn("面试故事库模型返回为空，使用兜底结构");
            throw new BusinessException("AI 返回内容为空，请稍后重试");
        }
        return response;
    }
}
