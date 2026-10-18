package com.example.interview.service.career;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>v1.74.0 补两道闸门：
 * <ol>
 *   <li><b>结构校验 + 重试</b>：此前只过 {@code JsonRepairUtil.repairOrFallback}，
 *       模型返回结构不对的 JSON（缺 rewrittenBullets、条目不是对象）也算「成功」，
 *       前端拿到脏数据渲染成空面板，用户以为「AI 没生成」而反复点击。现对齐
 *       AI 调用五件套第 ④ 条（范式见 {@code ResumeAnalysisService.validateOrRetryResume}）：
 *       要么合法，要么明确失败；</li>
 *   <li><b>数字溯源</b>：模型会把「性能优化」顺手改写成「性能优化，响应时间下降 40%」。
 *       这条会被用户直接粘进简历——那是编造，是产品红线「不伪造简历」最严重的形态。
 *       故输出前逐条核对数字来源，原文没有的数字连同整条改写一起剔除，并把剔除原因
 *       回传给前端，让用户知道「AI 想编，被拦下了」而不是静默少一条。</li>
 * </ol>
 */
@Service
public class TailoredResumeService {

    private static final Logger log = LoggerFactory.getLogger(TailoredResumeService.class);

    /** 简历文本最大长度 */
    private static final int MAX_RESUME_LEN = 2500;

    /** 岗位描述最大长度 */
    private static final int MAX_JOB_LEN = 1500;

    /**
     * 兜底 JSON（结构等价，保证前端字段可读）。
     *
     * <p>v1.74.0 起它**不再**被 {@link #tailor} 当作「成功结果」返回——空响应或结构非法
     * 一律走 {@link #validateOrRetryTailor} 的重试/抛错。保留该常量是为了给前端与测试
     * 提供一份「空结果长什么样」的结构参照，字段必须与正常输出同构（含 v1.74.0 新增的 findings）。
     */
    static final String TAILOR_FALLBACK_JSON = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
            + "\"rewrittenBullets\":[],\"summary\":\"\",\"suggestions\":[],\"findings\":[]}";

    /** 定制简历 JSON 校验/改写用（结构固定，独立实例足够，避免与全局 ObjectMapper 配置耦合） */
    private static final ObjectMapper TAILOR_MAPPER = new ObjectMapper();

    /**
     * 改写文本里的数字 token：阿拉伯数字 + 可选小数 + 可选百分号。
     *
     * <p>不匹配中文数字（「三个模块」）：中文数字是原文本来就有的写法，
     * 不是模型新造的事实，无需溯源。
     */
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%?");

    @Autowired
    private ChatClient chatClient;

    /**
     * 生成针对某岗位的定制简历要点
     *
     * @param resumeText  用户原始简历文本或经历要点
     * @param jobTitle    目标岗位名称
     * @param jobDetail   目标岗位 JD/要求（可为空）
     * @return 结构化 JSON 字符串（已修复为合法 JSON，且已剔除含无来源数字的改写条目）
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
                .append("6. suggestions：3-5 条针对该岗位的简历优化建议（如调整模块顺序、补充哪类项目）\n")
                .append("7. 数字必须可溯源：改写条目里出现的任何数字、百分比、规模（如 40%、3 个、QPS 8000）")
                .append("必须与原始简历中已有的数字完全一致，禁止换算、禁止放大、禁止估算；")
                .append("原文没有的数字一律不要写。\n\n")
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

        String raw = callModel(prompt);
        // 重试也要过并发闸门：callModel 内部已包 AiConcurrencyGuard.call()，不能因为「是重试」就绕过
        String validated = validateOrRetryTailor(raw, () -> callModel(prompt));
        // 第二个参数必须是**原始未截断**的 resumeText。模型只看到截断后的 2500 字，正常情况下
        // 不会写出截断尾部的内容；但一旦它写出的数字恰好是用户真实简历里存在、只是落在截断尾部，
        // 用截断版做基准就会把这条**真实**条目判成「无来源」删掉——判据应当是「用户是否真的写过」，
        // 而不是「模型是否看得到」。用全文只会更宽松，不会更严
        return stripUnsourcedNumbers(validated, resumeText);
    }

    /**
     * 发起一次模型调用（统一走并发闸门，避免重试路径漏掉限流保护）。
     */
    private String callModel(String prompt) {
        return AiConcurrencyGuard.call(() -> chatClient.prompt().user(prompt).call().content());
    }

    /**
     * 校验定制简历结果是否为「可用的」JSON —— 既要能解析，也要满足业务契约：
     * <ul>
     *   <li>{@code rewrittenBullets} 为**非空数组**，每项是对象且 {@code original}/{@code rewritten}
     *       均为非空白字符串（前端直接渲染这两个字段，缺一个就是空白面板）；</li>
     *   <li>{@code matchedKeywords} / {@code missingKeywords} / {@code suggestions} 存在且为字符串数组；</li>
     *   <li>{@code summary} 存在且为字符串（允许空串：模型偶尔确实无话可说，不算脏数据）。</li>
     * </ul>
     *
     * <p>为什么必须有这层校验：定制简历是**嵌套结构**，与简历分析同源（同一模型、同一类提示词），
     * 而 {@code JsonRepairUtil} 只能修「语法坏」不能修「结构缺字段」。此前缺字段的 JSON 会被
     * 原样落库并下发，前端 {@code v-if} 全部落空，用户看到的是空面板——既不知道失败也不知道为什么。
     */
    static boolean isValidTailor(String json) {
        if (!JsonRepairUtil.isValid(json)) {
            return false;
        }
        try {
            JsonNode node = TAILOR_MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return false;
            }
            JsonNode bullets = node.get("rewrittenBullets");
            if (bullets == null || !bullets.isArray() || bullets.isEmpty()) {
                return false;
            }
            for (JsonNode b : bullets) {
                if (b == null || !b.isObject()) {
                    return false;
                }
                if (!isNonBlankText(b.get("original")) || !isNonBlankText(b.get("rewritten"))) {
                    return false;
                }
            }
            return isStringArray(node.get("matchedKeywords"))
                    && isStringArray(node.get("missingKeywords"))
                    && isStringArray(node.get("suggestions"))
                    && node.get("summary") != null
                    && node.get("summary").isTextual();
        } catch (Exception e) {
            return false;
        }
    }

    /** 字段是否为非空白字符串 */
    private static boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    /** 字段是否为「元素全为字符串」的数组（空数组合法） */
    private static boolean isStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode e : node) {
            if (e == null || !e.isTextual()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 输出前校验：破损 JSON 先重试一次，仍不合格则抛出明确错误。
     *
     * <p>范式逐行对齐 {@code ResumeAnalysisService.validateOrRetryResume}——「要么合法，要么明确失败」，
     * **不再把兜底串当成功结果返回**。重试会多消耗一次 AI 调用，但只在解析/契约失败时发生，
     * 相比「把脏数据当成功下发、前端渲染出空面板」，这个代价是值得的。
     *
     * @param retryCall 重试时重新发起 AI 调用的动作（需自行包裹并发闸门）
     */
    private String validateOrRetryTailor(String raw, java.util.function.Supplier<String> retryCall) {
        String repaired = JsonRepairUtil.repairAndLog(raw, "career-tailor-resume");
        if (isValidTailor(repaired)) {
            return repaired;
        }
        log.warn("定制简历输出非法 JSON（已尝试修复），重试一次。原文前 200 字：{}", TextUtil.truncate(raw, 200));

        String retryRaw;
        try {
            retryRaw = retryCall.get();
        } catch (Exception e) {
            log.warn("定制简历重试调用失败：{}", e.getMessage());
            throw new BusinessException("定制简历结果格式异常，请稍后重试");
        }
        if (retryRaw == null || retryRaw.isBlank()) {
            throw new BusinessException("定制简历结果格式异常，请稍后重试");
        }
        String retryRepaired = JsonRepairUtil.repairAndLog(retryRaw, "career-tailor-resume-retry");
        if (isValidTailor(retryRepaired)) {
            return retryRepaired;
        }
        log.error("定制简历重试后仍非法，放弃本次生成。原文前 200 字：{}", TextUtil.truncate(retryRaw, 200));
        throw new BusinessException("定制简历结果格式异常，请稍后重试");
    }

    /**
     * 剔除「含原文未出现数字」的改写条目，并把剔除原因写进 {@code findings}。
     *
     * <p><b>为什么整条剔除而不是只删数字</b>：删掉「40%」后剩下的「响应时间下降」依然是
     * 模型凭空补的结论（原文只说「性能优化」），保留它等于默许编造。整条剔除 + 明确回报，
     * 用户才知道「这条 AI 想加戏，被拦下了」，而不是拿到一条被悄悄改过的句子。
     *
     * <p><b>溯源基准只能是入参 sourceText（用户真实材料），不能含模型自写的 original</b>：
     * 模型完全可以输出 {@code original="…响应时间下降 40%"}、{@code rewritten="…响应时间下降 40%"}，
     * 而用户原文只有「性能优化」——若把 original 并进基准，等于让被检查的模型自己给数字背书，
     * 闸门立刻失效。original 里的合法数字本来就出自原文，排除它不损失任何真实来源。
     *
     * <p>sourceText 必须传**原始未截断**版本：判据是「用户是否真的写过这个数字」，而不是
     * 「模型是否看得到」——模型写出的数字若恰好落在截断尾部，用截断版做基准会把真实条目误删。
     *
     * <p><b>中文数字兼容</b>：中文简历里「负责三个核心模块」被改写成「负责 3 个模块」是合法改写，
     * 不能因为基准里只有「三」没有「3」就删条目。故整数 token 额外比对中文数字形式（0-99，
     * 含「两」）；带小数或百分号的 token 只做阿拉伯数字比对（中文没有「四十点五%」这种写法）。
     *
     * <p><b>本方法绝不抛异常</b>：溯源是安全网，安全网自己挂掉不能把整个请求带崩。
     * 解析失败时原样返回入参（此时也无法保证 findings 存在——非法 JSON 本就无法承载字段）。
     *
     * @param json       已通过结构校验的定制简历 JSON
     * @param sourceText 用户原始简历全文（可为 null）
     * @return 剔除后的 JSON；结构问题导致无法处理时原样返回入参
     */
    static String stripUnsourcedNumbers(String json, String sourceText) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = TAILOR_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return json;
            }
            ObjectNode obj = (ObjectNode) root;
            JsonNode existingFindings = obj.get("findings");
            ArrayNode findings = (existingFindings != null && existingFindings.isArray())
                    ? (ArrayNode) existingFindings
                    : obj.putArray("findings");

            JsonNode bullets = obj.get("rewrittenBullets");
            if (bullets == null || !bullets.isArray()) {
                // 没有条目可核对，但 findings 字段必须存在（前端按存在性判断是否渲染提示块）
                return TAILOR_MAPPER.writeValueAsString(obj);
            }

            String source = sourceText == null ? "" : sourceText;
            ArrayNode kept = TAILOR_MAPPER.createArrayNode();
            for (JsonNode bullet : bullets) {
                String rewritten = bullet.path("rewritten").asText("");
                if (rewritten.isBlank()) {
                    // 结构校验已保证非空白；这里只是防御性兜底，不因单条异常影响其余条目
                    kept.add(bullet);
                    continue;
                }
                // 溯源基准只能是「用户真实提供的材料」。绝不能把模型自写的 original 并进来——
                // 否则模型只要在 original 里复述一遍它编的数字，就能给 rewritten 背书，
                // 闸门形同虚设（守卫被它要守的对象自己背书）。original 里的合法数字本来就出自
                // 原文，去掉它不损失任何真实来源。
                String baseline = source;
                List<String> unsourced = new ArrayList<>();
                Matcher m = NUMBER_TOKEN.matcher(rewritten);
                while (m.find()) {
                    String token = m.group().replaceAll("\\s+", "");
                    if (!isNumberSourced(token, baseline) && !unsourced.contains(token)) {
                        unsourced.add(token);
                    }
                }
                if (unsourced.isEmpty()) {
                    kept.add(bullet);
                    continue;
                }
                ObjectNode finding = TAILOR_MAPPER.createObjectNode();
                finding.put("type", "unsourced_number");
                ArrayNode tokens = finding.putArray("tokens");
                unsourced.forEach(tokens::add);
                finding.put("rewritten", rewritten);
                findings.add(finding);
            }
            // 允许剔除后为空数组：空数组是「全部条目都被拦下」的真实结果，
            // 前端会展示 findings 说明原因，比强行留一条编造的条目诚实
            obj.set("rewrittenBullets", kept);
            return TAILOR_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("定制简历数字溯源失败，跳过本次剔除（不影响主流程）：{}", e.getMessage());
            return json;
        }
    }

    /**
     * 单个数字 token 是否能在基准文本里找到来源。
     *
     * <p>判定刻意宽松（子串包含，而非词边界匹配）：这里的代价是不对称的——误杀会删掉用户
     * 本来正确的条目，漏放只是让一个可能编造的数字过关。产品上宁可漏放也要避免误杀，
     * 因为用户看到「我的真实数据被删了」会直接失去对功能的信任。
     *
     * @param token    已去除空白的数字 token（如 {@code 40%} / {@code 3} / {@code 3.5}）
     * @param baseline 用户真实材料（原始简历全文），**不含**模型自写的 original
     */
    private static boolean isNumberSourced(String token, String baseline) {
        boolean percent = token.endsWith("%");
        String numeric = percent ? token.substring(0, token.length() - 1) : token;
        if (baseline.contains(numeric)) {
            return true;
        }
        // 小数 / 百分比不做中文数字比对：中文里没有「三点五%」这种简历写法
        if (percent || numeric.indexOf('.') >= 0) {
            return false;
        }
        int value;
        try {
            value = Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return false;
        }
        // 只覆盖 0-99：三位数以上（QPS 8000、2000 万）几乎不会以中文数字书写，
        // 比对中文反而会引入「八百」这类噪声匹配
        if (value < 0 || value > 99) {
            return false;
        }
        if (baseline.contains(chineseNumeral(value))) {
            return true;
        }
        // 「两」是「二」的口语形式：「负责两个模块」→「负责 2 个模块」
        return value == 2 && baseline.contains("两");
    }

    /**
     * 阿拉伯整数 → 中文数字（0-99）。
     *
     * <p>只覆盖 0-99 是有意的：简历里需要中文数字表达的量级（三个模块、十二个接口、二十人团队）
     * 都在这个区间；更大数值用中文写反而罕见，且「一百二十三」的拼接规则复杂、出错成本高于收益。
     *
     * @param n 0-99 的整数；越界返回空串（调用方 contains("") 恒为 true，故先判越界再调用）
     */
    static String chineseNumeral(int n) {
        if (n < 0 || n > 99) {
            return "";
        }
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) {
            return digits[n];
        }
        int tens = n / 10;
        int ones = n % 10;
        // 「十」「十二」不写「一十」「一十二」
        String tensPart = (tens == 1) ? "十" : digits[tens] + "十";
        return (ones == 0) ? tensPart : tensPart + digits[ones];
    }
}
