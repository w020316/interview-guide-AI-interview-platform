package com.example.interview.service.resume;

import com.example.interview.ai.AiConcurrencyGuard;
import com.example.interview.common.BusinessException;
import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从已有简历文本自动填充结构化简历（v1.78.0）
 *
 * ── 解决什么问题 ────────────────────────────────────────────────────
 * 模板编辑器上线后，用户要手工把上传的简历内容再敲一遍——这是整条链路里
 * 唯一「机器明明读得懂、却要人重做」的一步。本服务把 {@code resume.content}
 * （上传时已解析出的纯文本）抽取成 6 段结构化数据，直接灌进编辑器。
 *
 * ── 遵守项目的 AI 调用五条约定（新 AI 功能必守，见 AGENTS 约定）────────
 * ① 输入 {@link PromptSanitizer#sanitize} + {@link TextUtil#truncate}；
 * ② 调用包 {@link AiConcurrencyGuard#call}（重试路径同样经过，不绕过闸门）；
 * ③ 输出 {@link JsonRepairUtil#repairAndLog} 修 JSON；
 * ④ **结构校验 + 失败换提示词重试一次**，仍失败抛 {@link BusinessException}——
 *    绝不把「解析失败但 HTTP 200」的脏数据下发（前端会渲染出一个全空却看似正常的简历）；
 * ⑤ 无总分字段，不涉及服务端复算。
 *
 * ── 不编造原则 ──────────────────────────────────────────────────────
 * 提示词明确要求「只抽取原文中已有的信息，原文没有的字段留空字符串 / 空数组」。
 * 抽取不是创作：用户简历里没写 GPA，就不能因为「应届生一般有 GPA」而填一个。
 * 这条与本项目 {@code missingSkills} 的处理立场一致——不知道就是不知道。
 */
@Service
public class ResumeAutofillService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAutofillService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 喂给模型的简历文本上限。
     * 比 {@code TailoredResumeService} 的 2500 更宽：那边只需要「挑几条重写」，
     * 这边要**完整抽取全部段落**，截得太狠会把教育背景、技能段整段丢掉。
     */
    static final int MAX_RESUME_LEN = 6000;

    /** 抽取结果里至少要有一段实质内容，否则视为「模型没读出来」 */
    private static final String[] CONTENT_SECTIONS = {"education", "experience", "projects"};

    private static final String SYSTEM_PROMPT = """
            你是简历结构化助手。请把用户提供的简历纯文本，抽取成给定的 JSON 结构。

            【最高优先级：严禁编造】
            1. 只抽取原文中**确实出现**的信息，逐字忠实，不要改写措辞、不要润色、不要补全。
            2. 原文没有的字段一律填空字符串 "" 或空数组 []，**不要猜测、不要按常识补全**。
               例如原文没写 GPA 就不要填；没写公司所在地就不要填 location。
            3. 日期保持原文写法（如「2023.07」「2023年7月」「至今」），不要换算成别的格式。
            4. 不要把一段内容重复放到多个段落里。

            【字段说明】
            - basics：姓名 / 求职意向(title) / 手机 / 邮箱 / 所在城市 / 个人链接(links，含 label 与 url)
            - summary：原文中的自我评价或求职意向段落；没有就留空
            - education：学校 / 学历 / 专业 / 起止时间 / GPA / highlights(主修课程、荣誉等，逐条)
            - experience：公司 / 职位 / 起止时间 / 城市 / bullets(工作内容，**每条独立一行**)
            - projects：项目名 / 角色 / 起止时间 / tech(技术栈关键词数组) / bullets(逐条)
            - skills：category(如「后端」「语言」) / items(该分类下的技能词数组)
            - extras：证书、奖项等 / title / date / desc

            【输出要求（务必严格遵守）】
            1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记
            2. 字符串必须用 ASCII 双引号 "，禁止单引号或中文引号
            3. 字符串值内禁止裸换行符、回车符、制表符
            4. 不要输出注释、解释、前后缀文字
            5. 输出格式：
            {"basics":{"name":"","title":"","phone":"","email":"","location":"","links":[{"label":"","url":""}]},
             "summary":"",
             "education":[{"school":"","degree":"","major":"","start":"","end":"","gpa":"","highlights":[""]}],
             "experience":[{"org":"","role":"","start":"","end":"","location":"","bullets":[""]}],
             "projects":[{"name":"","role":"","start":"","end":"","tech":[""],"bullets":[""]}],
             "skills":[{"category":"","items":[""]}],
             "extras":[{"title":"","date":"","desc":""}]}
            """;

    @Autowired
    private ChatClient chatClient;

    /**
     * 把简历纯文本抽取成结构化 JSON 字符串。
     *
     * @param resumeText 简历文本（通常来自 {@code resume.content}）
     * @return 已通过结构校验、并补齐 6 个顶层键的 JSON 字符串
     * @throws BusinessException 文本为空、或两次调用都拿不到可用结构
     */
    public String autofill(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            throw new BusinessException("这份简历没有可用的文本内容，无法自动填充");
        }
        String safeText = PromptSanitizer.sanitize(TextUtil.truncate(resumeText, MAX_RESUME_LEN));
        if (safeText.isBlank()) {
            throw new BusinessException("这份简历没有可用的文本内容，无法自动填充");
        }

        String basePrompt = SYSTEM_PROMPT + "\n\n【简历原文】\n" + safeText;
        String raw = callModel(basePrompt);
        return validateOrRetry(raw, hint -> callModel(basePrompt + "\n\n【上一次输出的问题】\n" + hint));
    }

    /** 统一走并发闸门；重试路径同样经过，避免漏掉限流保护 */
    private String callModel(String prompt) {
        return AiConcurrencyGuard.call(() -> chatClient.prompt().user(prompt).call().content());
    }

    /**
     * 结构校验 + 失败换提示词重试一次。
     *
     * <p>为什么重试要**换提示词**：同一 prompt 会让模型以同样方式再错一次
     * （{@code TailoredResumeService} 的实测结论，此处沿用同一做法）。
     */
    private String validateOrRetry(String raw, java.util.function.Function<String, String> retryCall) {
        Validation first = validate(raw);
        if (first.ok) {
            return first.json;
        }
        log.info("简历自动填充首次输出不可用（{}），换提示词重试一次", first.reason);

        String retried;
        try {
            retried = retryCall.apply(first.retryHint);
        } catch (Exception e) {
            // 重试本身失败（限流 / 超时）：如实上报，不返回半成品
            log.warn("简历自动填充重试调用失败：{}", e.getMessage());
            throw new BusinessException("AI 服务暂时不可用，请稍后重试；也可以先手动填写");
        }

        Validation second = validate(retried);
        if (second.ok) {
            return second.json;
        }
        log.warn("简历自动填充两次都不可用，最后一次原因：{}", second.reason);
        throw new BusinessException(
                "没能从这份简历里读出结构化内容。可以换一份文本更完整的简历，或直接在右侧手动填写");
    }

    /**
     * 校验结果：ok 时带规范化后的 JSON，不 ok 时带原因与重试提示。
     *
     * <p>包级可见（而非 private）是为了让单测能直接断言「什么算不可用」——
     * 这段判定是整个自动填充的关键闸门，必须能被独立测到。
     */
    static final class Validation {
        boolean ok;
        String json;
        String reason;
        String retryHint;

        static Validation fail(String reason, String retryHint) {
            Validation v = new Validation();
            v.ok = false;
            v.reason = reason;
            v.retryHint = retryHint;
            return v;
        }
    }

    /**
     * 校验并规范化。
     *
     * <p>判定为不可用的情况：
     * <ul>
     *   <li>JSON 无法解析 / 不是对象；</li>
     *   <li>缺 basics 或 5 个数组段中的任何一个；</li>
     *   <li><b>education / experience / projects 三段全空</b>——这是「模型没读出东西」
     *       的典型表现。返回它等于让用户得到一个空简历，还不如明确报错让他手填。</li>
     * </ul>
     */
    Validation validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Validation.fail("输出为空", "上一次输出是空的。请直接输出 JSON 对象，不要任何解释文字。");
        }
        String repaired = JsonRepairUtil.repairAndLog(raw, "resume-autofill");
        JsonNode root;
        try {
            root = MAPPER.readTree(repaired);
        } catch (Exception e) {
            return Validation.fail("JSON 无法解析", "上一次输出不是合法 JSON。请只输出 JSON 对象，字符串用 ASCII 双引号。");
        }
        if (root == null || !root.isObject()) {
            return Validation.fail("顶层不是对象", "上一次输出的顶层不是 JSON 对象。请严格按给定格式输出。");
        }
        ObjectNode obj = (ObjectNode) root;

        if (!obj.has("basics") || !obj.get("basics").isObject()) {
            return Validation.fail("缺 basics", "上一次输出缺少 basics 对象。请补上 basics（姓名/联系方式等）。");
        }
        List<String> missing = new ArrayList<>();
        for (String key : new String[]{"education", "experience", "projects", "skills", "extras"}) {
            JsonNode node = obj.get(key);
            if (node == null || !node.isArray()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return Validation.fail("缺数组段 " + missing,
                    "上一次输出缺少这些数组字段：" + String.join("、", missing)
                            + "。每个字段都必须是数组，没有内容就给空数组 []。");
        }

        boolean hasContent = false;
        for (String key : CONTENT_SECTIONS) {
            ArrayNode arr = (ArrayNode) obj.get(key);
            if (!arr.isEmpty()) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) {
            return Validation.fail("三段经历全空", """
                    上一次输出的 education / experience / projects 全是空数组，但简历原文里是有内容的。
                    请重新逐段抽取：把学校、公司、项目分别放进对应数组，工作/项目内容按「每条一行」拆进 bullets。""");
        }

        // 交给 ResumeDocumentService 做最终规范化：补齐 6 个顶层键、保证 links 是数组、
        // 并施加体积上限。这样前端拿到的结构一定完整，不必到处判空。
        try {
            String normalized = ResumeDocumentService.normalizeDocData(obj.toString(), false);
            Validation v = new Validation();
            v.ok = true;
            v.json = normalized;
            return v;
        } catch (BusinessException e) {
            return Validation.fail("规范化失败：" + e.getMessage(),
                    "上一次输出体积过大或格式异常。请精简内容后重新输出。");
        }
    }
}
