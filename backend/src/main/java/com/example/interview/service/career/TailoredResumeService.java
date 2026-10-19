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

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p><b>v1.74.1 修正 v1.74.0 的过严校验（线上回归）</b>：v1.74.0 的校验要求四个次要字段
 * （matchedKeywords / missingKeywords / suggestions / summary）全部存在且类型正确，
 * 而模型偶发漏掉其中一个。线上实测 5 次请求 2 次 503——用户本可以拿到完好的改写条目，
 * 却因为一个装饰性字段缺失被判「格式异常」并重试，重试大概率同样漏，最终整份失败。
 *
 * <p>现在的原则是<b>只拒绝「不可用」的，其余尽量修正</b>：
 * <ul>
 *   <li>主叙事 {@code rewrittenBullets}：缺失、非数组、或剔除坏条目后为空 → 判不可用（重试/抛错）；</li>
 *   <li>三个次要字符串数组：缺失或类型不对 → 补空数组 + {@code log.warn}，不判失败；</li>
 *   <li>{@code summary}：缺失或非字符串 → 补空串；</li>
 *   <li>单条 bullet 缺 {@code original}/{@code rewritten} → 只丢这一条，其余保留。</li>
 * </ul>
 * 前端拿到的字段类型永远正确（不下发脏数据），但可用结果不会被判死。
 *
 * <p><b>v1.74.2 收紧数字溯源的匹配口径</b>：v1.74.0 用子串包含判断来源，
 * 「2023 年毕业」会给改写里的 {@code 3} 背书、「QPS 3000」会给 {@code 30} 背书——
 * 几乎每份简历都有年份，等于这批数字的闸门形同虚设。现改为数字边界匹配，
 * 并把小数尾零归一化（{@code 40.0} → {@code 40}）以免误杀等价写法。
 *
 * <p><b>v1.75.0 把「字符串比对」换成「数值集合比对」</b>：边界匹配仍然只认得阿拉伯数字，
 * 于是「负责三个模块 → 负责四十个模块」这种纯中文改写完全绕过闸门，
 * 「3,000」还会被拆成 3 和 000 而误杀。现在两侧都先归一化成 {@link NumKey}
 * （数值 + 是否百分比）再比集合：中文数字、千分位、全角数字、{@code 万/亿}、
 * {@code 百分之X} 都落在同一套数值语义上，{@code 40} 与 {@code 40.0} 相等，
 * 而 {@code 40} 与 {@code 40%} 不相等（把计数说成比率是另一件事）。
 *
 * <p><b>已知残留（有意不处理，别当成 bug 修）</b>：
 * <ol>
 *   <li>不比较量词：{@code 3 个模块 → 3 个团队} 会放行——量词不改变数值事实，
 *       强行比较会把「3 个模块 → 3 项模块」这类正常润色误杀；</li>
 *   <li>序数整类跳过：{@code 第一作者} 里的「一」不产生 1，反向也不校验
 *       {@code 第 1 作者}（两侧都跳过，故不会误杀，但也拦不住把「第一」改写成「第 1」）；</li>
 *   <li>{@code 一月}（月份）会被当成「1 个月」，可能给改写里的 {@code 1 个} 背书——
 *       中文里月份与数量同形，区分需要上下文语义，代价远高于收益；</li>
 *   <li>不支持的形态（{@code 半年}、{@code 三五天}、{@code 廿}、{@code 俩}）直接不入集合，
 *       源侧因此拿不出数值，对应的改写会被剔除——这是「宁可少放一条，也不放过编造」的取舍。</li>
 * </ol>
 *
 * <p><b>v1.75.0 同时给重试加耗时预算</b>：首次输出非法会再打一次模型，而单次链路实测可超 130s、
 * 前端 AI 超时只有 180s。若首次已耗时 90s 以上，重试几乎必然把前端拖到超时——用户看到的是
 * 网络错误而不是可理解的失败。故超预算直接按首次的失败类型抛错，不再打第二次。
 */
@Service
public class TailoredResumeService {

    private static final Logger log = LoggerFactory.getLogger(TailoredResumeService.class);

    /** 简历文本最大长度 */
    private static final int MAX_RESUME_LEN = 2500;

    /** 岗位描述最大长度 */
    private static final int MAX_JOB_LEN = 1500;

    /** 定制简历 JSON 校验/改写用（结构固定，独立实例足够，避免与全局 ObjectMapper 配置耦合） */
    private static final ObjectMapper TAILOR_MAPPER = new ObjectMapper();

    /**
     * 链路故障文案：AI 调用没拿到内容（超时、空响应、调用抛异常）。
     *
     * <p>与 {@link #MSG_FORMAT_INVALID} 分开是有意的：这两种故障的处置方式完全不同——
     * 前者是「稍后再试就好」，后者要去看模型输出结构。v1.74.0 把它们合并成一句
     * 「格式异常」，线上排查时无法区分「模型没回」和「模型回错了」。
     */
    static final String MSG_AI_UNAVAILABLE = "AI 服务暂时不可用，请稍后重试";

    /** 输出结构故障文案：拿到了内容，但主叙事（rewrittenBullets）不可用 */
    static final String MSG_FORMAT_INVALID = "定制简历结果格式异常，请稍后重试";

    /**
     * 重试提示：上一次输出里没有可用的改写条目。
     *
     * <p><b>为什么重试必须换提示词</b>：v1.75.0 之前重试用的是**同一个 prompt**，
     * 模型会以完全相同的方式再错一次——这不是「再给一次机会」，而是把同一个错误复述一遍。
     * 线上实测：对只有一条经历的输入，约 1/3 的请求两次都返回不可用的 rewrittenBullets，
     * 根因很可能是提示词要求「挑出 3-6 条」，而原文只有一条，模型干脆返回空数组。
     * 所以重试要告诉模型「上一次错在哪、这次怎么改」。
     */
    static final String RETRY_HINT_NO_BULLETS =
            "你上一次的输出里没有可用的 rewrittenBullets（为空或缺少该字段）。"
                    + "这次请务必至少输出 1 条改写条目：即使原始简历要点只有一条，也只改写这一条，"
                    + "绝对不要返回空数组。";

    /** 重试提示：上一次没有返回任何内容 */
    static final String RETRY_HINT_EMPTY =
            "你上一次没有返回任何内容。这次请直接输出一个 JSON 对象，不要解释、不要 Markdown 代码块。";

    /** 重试提示：上一次输出不是合法 JSON */
    static final String RETRY_HINT_BAD_JSON =
            "你上一次的输出不是合法 JSON。这次只输出一个 JSON 对象：不要 Markdown 代码块、"
                    + "不要前后缀解释、字符串用 ASCII 双引号、字符串值内不要裸换行。";

    /**
     * 重试耗时预算（毫秒）：从 {@link #tailor} 入口算起。
     *
     * <p>为什么是 90s：前端 AI 路径超时 180s（{@code AI_TIMEOUT}），线上实测出现过单次
     * 链路 >130s 的慢请求。首次已经花掉 90s 还再打一次，两次串起来几乎必然越过前端超时，
     * 用户只会看到一个网络错误，连「结果格式异常」这句可理解的失败都拿不到。
     */
    static final long RETRY_BUDGET_MS = 90_000;

    /**
     * 实际生效的重试预算。
     *
     * <p>非 final 只为可测：单测把它调成 0 才能覆盖「预算耗尽 → 不重试」这条分支，
     * 否则要么真等 90 秒，要么把常量改成可注入的 Clock——为一个内部方法引入时钟抽象过重。
     * 生产环境没有代码写这个字段，行为等同常量。
     */
    long retryBudgetMs = RETRY_BUDGET_MS;

    /** 带千分位的阿拉伯数字（可带 %/万/亿 后缀）。{@code 3,000} 必须先于普通数字匹配，见 {@link #scanNumbers}。 */
    private static final Pattern THOUSANDS_NUMBER =
            Pattern.compile("(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?)(?:\\s*(%|[万亿]))?");

    /**
     * 普通阿拉伯数字（可带 %/万/亿 后缀）。
     *
     * <p>空格只允许出现在数字与后缀之间：写成 {@code \s*} 再让后缀可选，会在「3 个模块」里
     * 把空格吃进匹配、findings 里出现 {@code "3 "}，用户按提示搜不到。
     */
    private static final Pattern ARABIC_NUMBER =
            Pattern.compile("(\\d+(?:\\.\\d+)?)(?:\\s*(%|[万亿]))?");

    /** 「百分之四十」：必须先于中文 run 摘走，否则「四十」会被记成 (40,false) 而非 (40,true) */
    private static final Pattern CN_PERCENT =
            Pattern.compile("百分之\\s*([零〇一二两三四五六七八九十百千万亿]+)");

    /** 「四成」= 40%：同理先于中文 run 摘走 */
    private static final Pattern CN_CHENG =
            Pattern.compile("([零〇一二两三四五六七八九十百千]+)成");

    /** 中文数字 run（连续的中文数字字符） */
    private static final Pattern CN_RUN =
            Pattern.compile("[零〇一二两三四五六七八九十百千万亿]+");

    /**
     * 中文数字 run 的左邻排除字符：这些字后面的数字是构词成分（序数、虚词），不是数量断言。
     *
     * <p>{@code 第}：{@code 第一次}、{@code 第一作者} 是序数，给它记 1 就等于给改写里的
     * {@code 1 个} 背书——而「第一」与「一个」根本不是同一个事实。
     * {@code 统/唯/同/专/单/逐/另/某/每}：{@code 统一管理}、{@code 唯一}、{@code 单一} 同理。
     */
    private static final String CN_LEFT_EXCLUDED = "第统唯同专单逐另某每";

    /**
     * 量词表（只做「这个 run 是不是在说数量」的门禁，不参与两侧比较）。
     *
     * <p>刻意<b>不含</b> {@code 分}（十分熟悉）、{@code 步}（进一步）、{@code 度}（一度）、
     * {@code 成/折}（另按比例处理）、{@code 方/起/样/直/定/般/些/致}（虚词）——
     * 这些字前面的「一/十」几乎都是构词，放进来会制造大量假来源。
     */
    private static final String CN_QUANTIFIERS =
            "个条次人家项款台页篇份种类层笔道题年月天周秒倍位名套张块段轮届只件本间场门单";

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
        // 预算从入口算起：包含提示词拼装与首次调用，因为它们同样吃前端那 180s 的预算
        long startNanos = System.nanoTime();
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
                .append("2. rewrittenBullets：").append(bulletCountInstruction(resumeText))
                .append("，保持事实不变的前提下\n")
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
        // 重试**换提示词**而不是原样再问一次：同一 prompt 会让模型以同样方式再错一次。
        // 提示词由 validateOrRetryTailor 根据「上一次错在哪」给出，见 RETRY_HINT_*。
        // 并发闸门仍由 callModel 内部保证，重试路径不绕过。
        String validated = validateOrRetryTailor(raw,
                hint -> callModel(prompt + "\n\n【上一次的问题】\n" + hint), startNanos);
        // 第二个参数必须是**原始未截断**的 resumeText：溯源要回答的是「用户到底写没写过这个数」，
        // 而不是「模型当时看不看得到」——所以基准用用户提供的全文，而不是喂给模型的截断版本。
        // （模型只看到前 2500 字，本就不会写出截断点之后的内容，故此举只放宽、不漏杀。）
        return stripUnsourcedNumbers(validated, resumeText);
    }

    /**
     * 发起一次模型调用（统一走并发闸门，避免重试路径漏掉限流保护）。
     */
    private String callModel(String prompt) {
        return AiConcurrencyGuard.call(() -> chatClient.prompt().user(prompt).call().content());
    }

    /**
     * 按「原文有多少条经历」决定提示词里要求输出几条改写。
     *
     * <p><b>为什么必须自适应</b>：提示词此前硬编码「挑出 3-6 条」。用户只粘一条经历时，
     * 这是个不可能完成的任务——模型有时干脆返回空数组，于是重试也救不回来，最后报
     * 「格式异常」。线上实测：单条输入的失败率约 1/3（加针对性重试后降到 1/5），
     * 而成功的那些**都只返回了 1 条**，正是原文条数的真实上限。
     *
     * <p>只做粗判（按换行与分句符切分），不求精确：它只影响「要求几条」，判错也无害。
     *
     * @param resumeText 用户粘贴的简历要点（原始文本）
     */
    static String bulletCountInstruction(String resumeText) {
        int items = roughItemCount(resumeText);
        if (items <= 2) {
            return "挑出全部可改写的原始条目（原文只有 " + items + " 条左右，请按实际条数输出，"
                    + "至少 1 条；不要因为凑不满 3 条就返回空数组）";
        }
        return "挑出 3-6 条最值得改写的原始条目";
    }

    /**
     * 粗估原文有几条经历：按换行与常见分句符切分后数非空片段。
     *
     * <p>刻意不用更精细的 NLP 判断——这里只需要区分「只有一两条」和「有很多条」，
     * 精确度提升换不来任何产品价值，反而多一处可能出错的逻辑。
     */
    private static int roughItemCount(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String part : resumeText.split("[\\n；;。]+")) {
            if (!part.isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 把模型输出规范化成「前端可直接渲染」的结构，不可用则返回 {@code null}。
     *
     * <p><b>判据只有一条：主叙事能不能用。</b>{@code rewrittenBullets} 是用户真正要拿走的东西，
     * 它缺失 / 不是数组 / 剔除坏条目后为空，才算本次生成失败；其余字段都只是装饰，
     * 缺失时补齐即可——v1.74.0 把它们也当成硬性契约，导致线上 40% 的请求被判 503。
     *
     * <p>逐项规则：
     * <ul>
     *   <li>{@code rewrittenBullets}：非数组 → null；单条缺 {@code original}/{@code rewritten}
     *       （非字符串或空白）→ 丢弃该条；全部被丢弃 → null；</li>
     *   <li>{@code matchedKeywords} / {@code missingKeywords} / {@code suggestions}：缺失或非数组
     *       → 补空数组；数组内混入非字符串元素 → 只丢坏元素；</li>
     *   <li>{@code summary}：缺失或非字符串 → 补空串；</li>
     *   <li>{@code findings}：保证存在（前端按存在性判断是否渲染提示块）。</li>
     * </ul>
     *
     * <p>每处修正都 {@code log.warn} 并带上字段名：线上要能回答「是模型漏了什么」，
     * 而不是只看到一句「结果非法」。这里**不做**数字溯源，那是
     * {@link #stripUnsourcedNumbers} 的职责（两件事混在一起会让失败原因无法归因）。
     *
     * @param json 已过 {@code JsonRepairUtil} 的 JSON 文本
     * @return 规范化后的 JSON；主叙事不可用或无法解析时返回 {@code null}
     */
    static String normalizeTailor(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = TAILOR_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                log.warn("定制简历结果不是 JSON 对象，判为不可用");
                return null;
            }
            ObjectNode obj = (ObjectNode) root;

            // 主叙事：唯一的硬性契约
            JsonNode bullets = obj.get("rewrittenBullets");
            if (bullets == null || !bullets.isArray()) {
                log.warn("定制简历缺少 rewrittenBullets 或类型不是数组（模型输出结构异常），判为不可用");
                return null;
            }
            ArrayNode keptBullets = TAILOR_MAPPER.createArrayNode();
            int dropped = 0;
            for (JsonNode bullet : bullets) {
                if (bullet == null || !bullet.isObject()
                        || !isNonBlankText(bullet.get("original"))
                        || !isNonBlankText(bullet.get("rewritten"))) {
                    dropped++;
                    continue;
                }
                keptBullets.add(bullet);
            }
            if (keptBullets.isEmpty()) {
                log.warn("定制简历 rewrittenBullets 剔除坏条目后为空（原 {} 条，丢弃 {} 条），判为不可用",
                        bullets.size(), dropped);
                return null;
            }
            if (dropped > 0) {
                // 只丢坏的那几条：其余改写条目对用户仍然可用，不该整份判失败
                log.warn("定制简历 rewrittenBullets 有 {} 条缺少 original/rewritten，已丢弃，保留 {} 条",
                        dropped, keptBullets.size());
            }
            obj.set("rewrittenBullets", keptBullets);

            // 次要字段：缺失就补，不判失败
            normalizeStringArray(obj, "matchedKeywords");
            normalizeStringArray(obj, "missingKeywords");
            normalizeStringArray(obj, "suggestions");

            JsonNode summary = obj.get("summary");
            if (summary == null || !summary.isTextual()) {
                log.warn("定制简历缺少 summary 或类型不是字符串（模型偶发），已补空串");
                obj.put("summary", "");
            }

            JsonNode findings = obj.get("findings");
            if (findings == null) {
                obj.putArray("findings");
            } else if (!findings.isArray()) {
                log.warn("定制简历 findings 类型不是数组（模型偶发），已补空数组");
                obj.putArray("findings");
            }

            return TAILOR_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("定制简历结果规范化失败（JSON 无法解析）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 规范化一个「字符串数组」次要字段：缺失/类型不对补空数组，混入非字符串元素则只丢坏元素。
     *
     * @param obj   待规范化的对象（原地修改）
     * @param field 字段名，仅用于日志定位
     */
    private static void normalizeStringArray(ObjectNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null) {
            log.warn("定制简历缺少 {}（模型偶发），已补空数组", field);
            obj.putArray(field);
            return;
        }
        if (isStringArray(node)) {
            return;
        }
        if (!node.isArray()) {
            log.warn("定制简历 {} 类型不是数组（模型偶发），已补空数组", field);
            obj.putArray(field);
            return;
        }
        ArrayNode filtered = TAILOR_MAPPER.createArrayNode();
        int dropped = 0;
        for (JsonNode element : node) {
            if (element != null && element.isTextual()) {
                filtered.add(element);
            } else {
                dropped++;
            }
        }
        log.warn("定制简历 {} 有 {} 个非字符串元素，已丢弃", field, dropped);
        obj.set(field, filtered);
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
     * 输出前规范化：结构不可用先重试一次，仍不可用则抛出明确错误。
     *
     * <p>范式对齐 {@code ResumeAnalysisService.validateOrRetryResume}——「要么可用，要么明确失败」，
     * **不把兜底串当成功结果返回**。重试会多消耗一次 AI 调用，但只在主叙事不可用时发生，
     * 相比「把脏数据当成功下发、前端渲染出空面板」，这个代价是值得的。
     *
     * <p><b>错误文案必须分叉（v1.74.1）</b>：此前两种完全不同的故障都报「结果格式异常」，
     * 用户和运维都无法区分「AI 没返回内容」与「AI 返回了但结构不对」：
     * <ul>
     *   <li>调用没拿到内容（null/空白，或重试调用抛异常）→ {@value #MSG_AI_UNAVAILABLE}，属于链路问题；</li>
     *   <li>拿到了内容但主叙事不可用 → {@value #MSG_FORMAT_INVALID}，属于模型输出结构问题。</li>
     * </ul>
     *
     * @param retryCall  重试时重新发起 AI 调用的动作（需自行包裹并发闸门）
     * @param startNanos {@link #tailor} 入口的 {@link System#nanoTime()}，用于判断是否还有重试预算
     */
    private String validateOrRetryTailor(String raw, java.util.function.Function<String, String> retryCall,
                                         long startNanos) {
        String repaired = JsonRepairUtil.repairAndLog(raw, "career-tailor-resume");
        String normalized = normalizeTailor(repaired);
        if (normalized != null) {
            return normalized;
        }
        if (raw == null || raw.isBlank()) {
            log.warn("定制简历首次调用未返回内容（链路无响应），重试一次");
        } else {
            log.warn("定制简历输出不可用（已尝试修复），重试一次。原文前 200 字：{}", TextUtil.truncate(raw, 200));
        }

        // 预算判断放在重试调用之前：重试的意义是「再给模型一次机会」，若首次已经吃掉
        // 大半前端超时预算，第二次只会把用户拖到网络错误，不如立刻给出可理解的失败。
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs >= retryBudgetMs) {
            // 单独一条日志：运维要能区分「重试后仍失败」与「预算放弃」，
            // 两者的处置完全不同（前者查模型输出，后者查链路耗时）
            log.warn("定制简历跳过重试：已耗时 {}ms 超出预算", elapsedMs);
            // 错误分类不受预算影响（v1.74.1 修过的归因混淆不能倒退）：
            // 没拿到内容仍是链路故障，拿到内容但结构不可用仍是模型输出问题。
            if (raw == null || raw.isBlank()) {
                log.warn("定制简历首次调用未返回内容（链路无响应），且预算已耗尽，放弃本次生成");
                throw new BusinessException(MSG_AI_UNAVAILABLE);
            }
            log.error("定制简历首次输出不可用且预算已耗尽，放弃本次生成。原文前 200 字：{}",
                    TextUtil.truncate(raw, 200));
            throw new BusinessException(MSG_FORMAT_INVALID);
        }

        String retryRaw;
        try {
            retryRaw = retryCall.apply(retryHint(raw, repaired));
        } catch (Exception e) {
            // 调用本身抛异常是链路故障，不是「模型输出格式不对」——文案不能混为一谈
            log.warn("定制简历重试调用失败（链路异常）：{}", e.getMessage());
            throw new BusinessException(MSG_AI_UNAVAILABLE);
        }
        if (retryRaw == null || retryRaw.isBlank()) {
            log.warn("定制简历重试仍未返回内容（链路无响应），放弃本次生成");
            throw new BusinessException(MSG_AI_UNAVAILABLE);
        }
        String retryNormalized = normalizeTailor(JsonRepairUtil.repairAndLog(retryRaw, "career-tailor-resume-retry"));
        if (retryNormalized != null) {
            return retryNormalized;
        }
        log.error("定制简历重试后仍不可用，放弃本次生成。原文前 200 字：{}", TextUtil.truncate(retryRaw, 200));
        throw new BusinessException(MSG_FORMAT_INVALID);
    }

    /**
     * 根据「上一次到底错在哪」挑一条重试提示，附在重试请求的 prompt 末尾。
     *
     * <p>分类只有三种，都对应线上真实见过的失败形态：
     * <ul>
     *   <li>没返回内容 → 让模型直接出 JSON；</li>
     *   <li>返回了但 {@code rewrittenBullets} 缺失/为空 → 明确要求「至少 1 条」，
     *       这是最关键的一条：提示词要 3-6 条，而用户只粘了一条经历时模型容易返回空数组；</li>
     *   <li>其它（结构坏到解析不了）→ 只强调输出格式。</li>
     * </ul>
     *
     * <p>解析失败一律归到「格式」提示：分类本身不值得再抛异常。
     *
     * @param raw      首次原始响应（判「有没有返回内容」）
     * @param repaired 首次响应经 {@link JsonRepairUtil} 修复后的文本（判「有没有可用条目」）
     */
    private static String retryHint(String raw, String repaired) {
        if (raw == null || raw.isBlank()) {
            return RETRY_HINT_EMPTY;
        }
        try {
            JsonNode node = TAILOR_MAPPER.readTree(repaired);
            JsonNode bullets = (node == null) ? null : node.get("rewrittenBullets");
            if (bullets == null || !bullets.isArray() || bullets.isEmpty()) {
                return RETRY_HINT_NO_BULLETS;
            }
        } catch (Exception e) {
            // 解析不了就是格式问题，落到下面那条提示
            log.debug("定制简历重试提示分类：解析失败，按格式问题处理：{}", e.getMessage());
        }
        return RETRY_HINT_BAD_JSON;
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
     * <p><b>中文数字兼容（v1.75.0 换成数值集合）</b>：中文简历里「负责三个核心模块」被改写成
     * 「负责 3 个模块」是合法改写，不能因为基准里只有「三」没有「3」就删条目。现在两侧都过
     * {@link #extractNumbers}，落到同一个 {@link NumKey} 上比较；中文数字的识别门禁见
     * {@link #isQuantityRun}，数值解析见 {@link #parseChineseNumeral}。
     *
     * <p><b>findings.tokens 放改写里的原样写法</b>（{@code 40%}、{@code 百分之四十}、{@code 3,000}），
     * 不是归一化后的 {@code 40}——用户要拿这个 token 回被剔除的那句话里搜，搜得到才有意义。
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

            // 源集合整份只算一次：解析要跑正则 + NFKC，逐条 bullet 重算纯属浪费
            Set<NumKey> sourceNumbers = extractNumbers(sourceText == null ? "" : sourceText);
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
                List<String> unsourced = findUnsourced(rewritten, sourceNumbers);
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
     * 数字的归一化键：数值 + 是否百分比。同值不同单位不算同一事实。
     *
     * <p>{@code 40} 与 {@code 40.0} 相等（剥尾零后 {@code BigDecimal} 的 scale 一致），
     * 但 {@code 40} 与 {@code 40%} 不相等——原文「40 人」不能给改写「40%」背书，
     * 那等于把计数偷换成比率。这正是本类不采用「只比数值」的原因。
     */
    record NumKey(BigDecimal value, boolean percent) {
        NumKey {
            // Set 用的是 BigDecimal.equals（比 scale），不剥尾零的话 40 与 40.0
            // 会落成两个键，等价写法反而被误判成编造
            value = value.stripTrailingZeros();
            if (value.signum() == 0) {
                // 0.00 剥尾零后的 scale 在不同 JDK 上曾有差异，统一成 ZERO 消除平台噪声
                value = BigDecimal.ZERO;
            }
        }
    }

    /** 一次数字匹配：改写里的原样写法 + 归一化键 + 起始下标（用于按原文顺序输出 token） */
    private record NumberOccurrence(String raw, NumKey key, int start) {
    }

    /**
     * 把一段文本解析成「数值集合」。
     *
     * <p>原文与改写走<b>同一套</b>规则，这是 v1.75.0 的核心：只有两侧落到同一个
     * {@link NumKey} 空间里，「三个 ↔ 3」「3,000 ↔ 3000」「十万 ↔ 100000」才谈得上相等。
     *
     * @param text 原文或改写；null 视为空
     * @return 数值集合（同值不同写法自动去重）
     */
    static Set<NumKey> extractNumbers(String text) {
        Set<NumKey> keys = new HashSet<>();
        for (NumberOccurrence occurrence : scanNumbers(text)) {
            keys.add(occurrence.key());
        }
        return keys;
    }

    /**
     * 找出改写里「原文没有来源」的数字，返回它们的<b>原样写法</b>。
     *
     * <p>返回原样写法而不是归一化后的 {@code 40}：findings 是给用户看的，用户要拿这个 token
     * 回到被剔除的那句话里搜索，{@code 40} 搜不到 {@code 百分之四十}。
     *
     * <p>按数值去重、保留首次出现的写法：同一句里「40% 和 40.0%」只需要提示一次。
     */
    static List<String> findUnsourced(String rewritten, Set<NumKey> sourceNumbers) {
        List<NumberOccurrence> occurrences = scanNumbers(rewritten);
        // 各阶段的匹配顺序不等于文本顺序（先摘百分比、再摘千分位），排序后 token 列表才读得顺
        occurrences.sort(Comparator.comparingInt(NumberOccurrence::start));
        List<String> unsourced = new ArrayList<>();
        Set<NumKey> reported = new HashSet<>();
        for (NumberOccurrence occurrence : occurrences) {
            if (sourceNumbers.contains(occurrence.key())) {
                continue;
            }
            if (reported.add(occurrence.key())) {
                unsourced.add(occurrence.raw());
            }
        }
        return unsourced;
    }

    /**
     * 扫描一段文本里的全部数字，产出「原样写法 + 归一化键」。
     *
     * <p>五个阶段有严格的先后：百分比形态 → 千分位 → 普通阿拉伯数字 → 中文 run。
     * 每个阶段命中的片段都会从缓冲区里抹掉（mask），防止后面的阶段把同一段再匹配一次。
     */
    private static List<NumberOccurrence> scanNumbers(String text) {
        List<NumberOccurrence> occurrences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return occurrences;
        }
        // NFKC：全角 ０-９ / ％ / ． / ，折成半角。不折的话「４０ 次压测」与「40 次压测」
        // 是两个互不相识的字符串，同一个事实会被判成编造。
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        char[] masked = normalized.toCharArray();

        // ① 中文百分比「百分之四十」：必须最先摘走。否则后面的中文 run 会把「四十」
        //    记成普通数量 (40,false)，改写里的 40% 就找不到来源——而这恰恰是模型
        //    最常编的一类数字（原文「性能提升」→ 改写「性能提升百分之四十」）。
        Matcher cnPercent = CN_PERCENT.matcher(normalized);
        while (cnPercent.find()) {
            BigDecimal value = parseChineseNumeral(cnPercent.group(1));
            if (value != null) {
                occurrences.add(new NumberOccurrence(
                        cnPercent.group(), new NumKey(value, true), cnPercent.start()));
            }
            mask(masked, cnPercent.start(), cnPercent.end());
        }

        // ② 「四成」= 40%：同理先摘走，避免「四」被单独当成 4
        Matcher cnCheng = CN_CHENG.matcher(normalized);
        while (cnCheng.find()) {
            BigDecimal value = parseChineseNumeral(cnCheng.group(1));
            if (value != null) {
                occurrences.add(new NumberOccurrence(cnCheng.group(),
                        new NumKey(value.multiply(BigDecimal.TEN), true), cnCheng.start()));
            }
            mask(masked, cnCheng.start(), cnCheng.end());
        }

        // ③ 千分位必须早于普通数字：否则 3,000 会被拆成 3 和 000 两个数，
        //    原文「3,000 QPS」就认不出改写里的「3000 QPS」——这是最容易误杀的一条。
        Matcher thousands = THOUSANDS_NUMBER.matcher(new String(masked));
        while (thousands.find()) {
            BigDecimal value = new BigDecimal(thousands.group(1).replace(",", ""));
            occurrences.add(new NumberOccurrence(thousands.group(),
                    arabicKey(value, thousands.group(2)), thousands.start()));
            mask(masked, thousands.start(), thousands.end());
        }

        // ④ 普通阿拉伯数字
        Matcher arabic = ARABIC_NUMBER.matcher(new String(masked));
        while (arabic.find()) {
            // 序数整类跳过：`第 1 作者` 里的 1 不是数量断言，和「第一作者」一样不该被核对。
            // 源侧的中文 run 同样因左邻「第」被跳过，两侧对称，故不会误杀。
            if (isOrdinalPrefix(normalized, arabic.start())) {
                mask(masked, arabic.start(), arabic.end());
                continue;
            }
            BigDecimal value = new BigDecimal(arabic.group(1));
            occurrences.add(new NumberOccurrence(arabic.group(),
                    arabicKey(value, arabic.group(2)), arabic.start()));
            mask(masked, arabic.start(), arabic.end());
        }

        // ⑤ 中文数字 run：先过「是不是在说数量」的门禁，再解析数值
        Matcher cn = CN_RUN.matcher(new String(masked));
        while (cn.find()) {
            if (!isQuantityRun(normalized, cn.start(), cn.end())) {
                continue;
            }
            BigDecimal value = parseChineseNumeral(cn.group());
            if (value == null) {
                continue;
            }
            occurrences.add(new NumberOccurrence(cn.group(), new NumKey(value, false), cn.start()));
        }
        return occurrences;
    }

    /** 阿拉伯数字（含千分位）的后缀语义：% 是比率，万/亿 是量级 */
    private static NumKey arabicKey(BigDecimal value, String suffix) {
        if ("%".equals(suffix)) {
            return new NumKey(value, true);
        }
        if ("万".equals(suffix)) {
            return new NumKey(value.multiply(BigDecimal.valueOf(10_000L)), false);
        }
        if ("亿".equals(suffix)) {
            return new NumKey(value.multiply(BigDecimal.valueOf(100_000_000L)), false);
        }
        return new NumKey(value, false);
    }

    /** 把已匹配的片段抹成空格，避免后续阶段重复匹配（长度不变，下标仍然有效） */
    private static void mask(char[] buf, int start, int end) {
        Arrays.fill(buf, start, end, ' ');
    }

    /** 数字前一个非空白字符是否为「第」（序数标记） */
    private static boolean isOrdinalPrefix(String text, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i >= 0 && text.charAt(i) == '第';
    }

    /**
     * 中文数字 run 是否在表达「数量」。
     *
     * <p>这是整套解析里最容易误判的地方：中文数字与构词成分同形，必须逐条设门禁。
     * 三条同时满足才放行：
     * <ol>
     *   <li>左邻不是序数/虚词标记（{@link #CN_LEFT_EXCLUDED}）——{@code 第一次} 不是「1 次」；</li>
     *   <li>右邻能证明它是数量：量词 / 约数标记 / 已到文本末尾 / run 自身以 万/亿 收尾；</li>
     *   <li>能解析出合法数值（见 {@link #parseChineseNumeral}）。</li>
     * </ol>
     *
     * <p>量词只用于门禁，不参与两侧比较：{@code 3 个模块 → 3 个团队} 会放行，
     * 这是有意的（见类注释「已知残留」）。
     */
    private static boolean isQuantityRun(String text, int start, int end) {
        // 条件①：`第一次`、`统一管理`、`单一方案` 里的数字是构词成分，不是数量断言
        if (start > 0 && CN_LEFT_EXCLUDED.indexOf(text.charAt(start - 1)) >= 0) {
            return false;
        }
        // 条件②：到文本末尾（`三千五`、`一万二` 这类独立写法）
        if (end >= text.length()) {
            return true;
        }
        char next = text.charAt(end);
        if (CN_QUANTIFIERS.indexOf(next) >= 0) {
            return true;
        }
        // `十余`、`二十多`、`三十左右`、`五十以上`
        if (text.startsWith("余", end) || text.startsWith("多", end)
                || text.startsWith("左右", end) || text.startsWith("以上", end)
                || text.startsWith("以下", end)) {
            return true;
        }
        // `十万用户`：「用户」不是量词，但「十万」自带量级，本身就是数量断言
        char last = text.charAt(end - 1);
        return last == '万' || last == '亿';
    }

    /**
     * 中文数字 run → 数值；不支持的形态返回 {@code null}（调用方整段跳过，不入集合）。
     *
     * <p>支持 0–9999 与单层 万/亿。刻意<b>不猜</b>的形态：
     * {@code 三五天}（连写约数）、{@code 廿/卅}（不在字符集内）、{@code 半}（半年）、
     * {@code 俩}（不在字符集内）。这些写法拿不出可信数值，硬猜会同时制造误杀
     * （把「三五天」读成 35）与漏放（读成 5 就给改写里的 5 背书）。
     *
     * <p>口语省略式靠 {@code lastUnit} 还原：{@code 三千五} 的「五」在「千」之后读作 500；
     * {@code 一百零五} 的「五」紧邻「零」，则读作 5。
     *
     * @param run 只含 {@code 零〇一二两三四五六七八九十百千万亿} 的字符串
     * @return 数值；非法形态返回 {@code null}
     */
    static BigDecimal parseChineseNumeral(String run) {
        if (run == null || run.isEmpty()) {
            return null;
        }
        // 单个「万/亿」不构成合法 run：`万一` 是「如果」，不是 10000；
        // 单独的「亿」也不代表一亿。真正的「十万」「三亿」都以数字或十百千开头。
        char first = run.charAt(0);
        if (first == '万' || first == '亿') {
            return null;
        }

        long total = 0;              // 已结算的「万/亿」段
        long section = 0;            // 当前段（万以下）
        int pending = 0;             // 待结算的个位数字
        boolean pendingAfterZero = false; // 该数字是否紧跟在「零」后面
        int lastUnit = 0;            // 最近一个单位（含 万/亿），用于还原口语省略
        int lastSmallUnit = 0;       // 当前段内已用的最小单位，用于拒绝乱序
        boolean prevWasDigit = false;
        boolean magnitudeSeen = false;

        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (isZeroChar(c)) {
                pending = 0;
                pendingAfterZero = true;
                prevWasDigit = true;
                continue;
            }
            int digit = digitValue(c);
            if (digit > 0) {
                // 连续两个数字字符（`三五天`）是约数连写，不是可解析的数值
                if (prevWasDigit && !isZeroChar(run.charAt(i - 1))) {
                    return null;
                }
                pending = digit;
                pendingAfterZero = prevWasDigit;
                prevWasDigit = true;
                continue;
            }
            prevWasDigit = false;
            if (c == '十' || c == '百' || c == '千') {
                int unit = (c == '十') ? 10 : (c == '百') ? 100 : 1000;
                // 同一段内单位必须递减：`十百`、`百百` 不是合法写法
                if (lastSmallUnit != 0 && unit >= lastSmallUnit) {
                    return null;
                }
                // 「十」单独出现读作 10（省略了「一」）；「零十」这种写法不合法，
                // 但 pendingAfterZero 为真时按 0 计，不会凭空补 1
                int n = (pending == 0 && !pendingAfterZero) ? 1 : pending;
                section += (long) n * unit;
                lastUnit = unit;
                lastSmallUnit = unit;
                pending = 0;
                pendingAfterZero = false;
                continue;
            }
            if (c == '万' || c == '亿') {
                // 只支持单层 万/亿：`一亿二万` 这类嵌套不在简历常见写法里，不猜
                if (magnitudeSeen) {
                    return null;
                }
                magnitudeSeen = true;
                section += pending;
                if (section == 0) {
                    section = 1;
                }
                long multiplier = (c == '万') ? 10_000L : 100_000_000L;
                total = (total + section) * multiplier;
                section = 0;
                lastUnit = (int) multiplier;
                lastSmallUnit = 0;
                pending = 0;
                pendingAfterZero = false;
                continue;
            }
            // 字符集由正则保证；走到这里说明调用方传了非法字符
            return null;
        }

        long result = total + section;
        if (pending != 0 || pendingAfterZero) {
            // 紧邻「零」的尾数是完整个位（一百零五 → 105）；否则按最近单位降一级
            // 还原口语省略（三千五 → 3500、一万二 → 12000、十二 → 12）
            long factor = (pendingAfterZero || lastUnit < 10) ? 1L : lastUnit / 10L;
            result += (long) pending * factor;
        }
        return BigDecimal.valueOf(result);
    }

    /** 中文数字字符 → 数值；非 1-9 返回 -1（零单独处理） */
    private static int digitValue(char c) {
        if (c == '两') {
            return 2;
        }
        int index = "一二三四五六七八九".indexOf(c);
        return index >= 0 ? index + 1 : -1;
    }

    private static boolean isZeroChar(char c) {
        return c == '零' || c == '〇';
    }
}
