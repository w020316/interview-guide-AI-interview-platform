package com.example.interview.service.career;

import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.example.interview.util.TextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 谈薪助手（v1.85.0）—— 期望报价 / offer 评估 / 还价话术。
 *
 * <h2>为什么要做这个能力</h2>
 *
 * <p>求职链路的最后一公里是谈薪，但它恰好是信息最不对称的一段：候选人不知道该怎么报期望、
 * 拿到 offer 不知道该核对什么、被压价时不知道该怎么回。市面上的谈薪工具最大的坑是
 * <b>编造行情</b>——给出一个看起来很专业的「同城同岗位薪资区间」，实际是模型编的。
 *
 * <h2>本服务的两条硬边界</h2>
 *
 * <ol>
 *   <li><b>绝不编造市场数据</b>：不输出薪资区间、分位、公司薪酬带宽、同行报价；
 *       需要行情时只提示用户自己去招聘平台查。提示词里写死，且有单测锁定措辞。</li>
 *   <li><b>所有金额计算在服务端做</b>：涨幅、年薪折算、与期望的差额都由
 *       {@link #computeNumbers} 用用户自己填的数字算出来；缺数据就返回 {@code null}，
 *       <b>绝不补 0</b>（0 会被前端渲染成「涨幅 0%」，那是假信息）。</li>
 * </ol>
 *
 * <p>工程约定与 {@link CareerProfileService} 一致：输入截断 + {@link PromptSanitizer} 消毒；
 * 调用纳入全局并发闸门；输出走 {@link JsonRepairUtil}，解析失败回退到结构等价的空报告。
 */
@Service
public class SalaryCoachService {

    private static final Logger log = LoggerFactory.getLogger(SalaryCoachService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_JOB_LEN = 1200;
    private static final int MAX_CITY_LEN = 50;
    private static final int MAX_COMPETING_LEN = 300;
    private static final int MAX_SITUATION_LEN = 1000;

    /** 兜底结构：字段齐全但为空，前端无需判空即可渲染 */
    static final String SALARY_FALLBACK_JSON = "{\"headline\":\"这次没有生成有效建议，请稍后重试\","
            + "\"analysis\":[],\"scripts\":[],\"qa\":[],\"warnings\":[],\"actions\":[]}";

    /** 三种谈薪场景 */
    public enum Mode {
        /** 还没拿到 offer：被问期望薪资时怎么报 */
        EXPECTATION,
        /** 已拿到 offer：值不值得接、怎么回 */
        OFFER,
        /** 正在还价：怎么谈、怎么应对僵局 */
        NEGOTIATION;

        /**
         * 解析前端传入的 mode：缺省为 {@link #EXPECTATION}，未知值抛
         * {@link IllegalArgumentException}（由控制器映射成 400，而不是静默降级）。
         */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return EXPECTATION;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("mode 只能是 EXPECTATION / OFFER / NEGOTIATION");
            }
        }

        /** 提示词里的场景侧重（三种模式的输出结构相同，重点不同） */
        String sceneGuide() {
            return switch (this) {
                case EXPECTATION -> "用户还没拿到 offer，重点是「HR 问期望薪资时怎么报」：先把薪资结构问清楚"
                        + "（月薪×薪数、绩效比例、试用期折扣），再给区间而不是单点，并说明报价依据来自用户填写的信息。";
                case OFFER -> "用户已拿到 offer，重点是「这个 offer 值不值得接、怎么回」：逐项核对结构"
                        + "（月薪、薪数、绩效比例、签字费、试用期折扣、涨薪周期），列出必须向 HR 确认的空白项。";
                case NEGOTIATION -> "用户正在还价，重点是「怎么给出还价理由、怎么处理僵局」：给出可执行的还价依据"
                        + "与让步边界，并示范如何应对「这已经是上限」「需要内部审批」这类施压。";
            };
        }
    }

    /**
     * 谈薪请求。
     *
     * <p>所有薪资字段单位均为「元/月（税前）」，{@code offerMonths} 为年薪月数（如 15 薪）。
     * 未填写的字段一律为 {@code null}，由服务端决定「无法计算」，绝不假设默认值。
     */
    public record SalaryRequest(Mode mode, String targetJob, String city, Integer years,
                                Integer currentMonthly, Integer expectedMonthly,
                                Integer offerMonthly, Integer offerMonths,
                                String competing, String situation) {}

    @Autowired
    private ChatClient chatClient;

    /**
     * 生成谈薪建议。
     *
     * @return 统一结构：headline（一句话结论）/ analysis / scripts / qa / warnings / actions
     *         + numbers（服务端算出的金额，缺数据为 null）
     */
    public Map<String, Object> coach(String userId, SalaryRequest req) {
        String prompt = buildPrompt(req);
        String raw = callAi(prompt);
        String repaired = JsonRepairUtil.repairOrFallback(raw, "career-salary", SALARY_FALLBACK_JSON);
        Map<String, Object> result = normalize(parseMap(repaired));
        result.put("numbers", computeNumbers(req.currentMonthly(), req.expectedMonthly(),
                req.offerMonthly(), req.offerMonths()));
        return result;
    }

    /** 组装提示词：把用户填的每一项都写明「未填写」，避免模型自行假设 */
    private static String buildPrompt(SalaryRequest req) {
        String safeJob = PromptSanitizer.sanitize(TextUtil.truncate(req.targetJob(), MAX_JOB_LEN));
        String safeCity = PromptSanitizer.sanitize(TextUtil.truncate(
                req.city() == null ? "" : req.city(), MAX_CITY_LEN));
        String safeCompeting = PromptSanitizer.sanitize(TextUtil.truncate(
                req.competing() == null ? "" : req.competing(), MAX_COMPETING_LEN));
        String safeSituation = PromptSanitizer.sanitize(TextUtil.truncate(
                req.situation() == null ? "" : req.situation(), MAX_SITUATION_LEN));

        return new StringBuilder()
                .append("你是资深薪酬谈判顾问。请基于求职者填写的真实信息，给出可以直接照着执行的谈薪建议。\n\n")
                .append("【目标岗位】\n").append(safeJob.isBlank() ? "未填写" : safeJob).append("\n\n")
                .append("【城市（可为空，为空时不得假设城市）】\n")
                .append(safeCity.isBlank() ? "未填写" : safeCity).append("\n\n")
                .append("【工作年限】\n").append(req.years() == null ? "未填写" : req.years() + " 年").append("\n\n")
                .append("【当前月薪（税前）】\n").append(money(req.currentMonthly())).append("\n\n")
                .append("【期望月薪（税前）】\n").append(money(req.expectedMonthly())).append("\n\n")
                .append("【已收到的 offer 月薪】\n").append(money(req.offerMonthly())).append("\n\n")
                .append("【offer 年薪月数（如 15 表示 15 薪）】\n")
                .append(req.offerMonths() == null ? "未填写" : req.offerMonths() + " 薪").append("\n\n")
                .append("【其他 offer / 竞争情况（用户自述）】\n")
                .append(safeCompeting.isBlank() ? "未填写" : safeCompeting).append("\n\n")
                .append("【补充情况（用户自述）】\n")
                .append(safeSituation.isBlank() ? "未填写" : safeSituation).append("\n\n")
                .append("【硬性约束（违反任何一条都算错误）】\n")
                .append("1. 禁止输出任何市场薪资数据：不给薪资区间、分位、公司薪酬带宽、同行报价、涨薪幅度参考——")
                .append("你无法获取实时行情，编出来的数字会直接误导用户。需要行情时只提示用户去招聘平台")
                .append("自查同城同岗位的真实薪资，不要替他填数字\n")
                .append("2. 禁止编造用户未提供的事实：不虚构竞争者、其他 offer、HR 的承诺、公司福利\n")
                .append("3. 可以引用并换算用户已填写的金额（差额、涨幅、年薪），但不得引入新的金额数字\n")
                .append("4. 不替用户做「接/不接」的决定，只把权衡条件讲清楚；不承诺任何谈判结果\n")
                .append("5. 看到风险要如实写进 warnings：只谈月薪不谈薪数、口头承诺不写进 offer、")
                .append("试用期打折、绩效比例过高、涨薪周期不明确、要求当场答复等\n\n")
                .append("【场景侧重】\n").append(req.mode().sceneGuide()).append("\n\n")
                .append("【内容要求】\n")
                .append("- headline：一句话结论（20-40 字），让用户一眼知道该怎么做\n")
                .append("- analysis：3-5 条，每条 point（你的判断）+ basis（依据：来自用户填写的哪一项，或通用谈判常识）\n")
                .append("- scripts：3-5 条可以直接照着说的话术，每条 scene（什么时候说）+ say（原话）\n")
                .append("- qa：3-4 组，q（对方可能怎么说）+ a（你可以怎么回）\n")
                .append("- warnings：0-4 条风险提示，没有就返回空数组\n")
                .append("- actions：3-5 条下一步动作，每条都要能在今天或本周做完\n\n")
                .append("【输出要求（务必严格遵守）】\n")
                .append("1. 直接输出 JSON 对象，不要 Markdown 代码块、不要 ```json 标记\n")
                .append("2. 字符串必须用 ASCII 双引号 \"，禁止单引号或中文引号\n")
                .append("3. 字符串值内禁止裸换行符、回车符、制表符\n")
                .append("4. 不要输出注释、解释、前后缀文字\n")
                .append("5. 输出格式：\n")
                .append("{\"headline\":\"一句话结论\",")
                .append("\"analysis\":[{\"point\":\"判断\",\"basis\":\"依据\"}],")
                .append("\"scripts\":[{\"scene\":\"场景\",\"say\":\"照着说\"}],")
                .append("\"qa\":[{\"q\":\"对方可能说\",\"a\":\"你回\"}],")
                .append("\"warnings\":[\"风险提示\"],\"actions\":[\"下一步动作\"]}")
                .toString();
    }

    private static String money(Integer value) {
        return value == null ? "未填写" : value + " 元";
    }

    /**
     * 服务端金额计算（确定性）。
     *
     * <p>缺任何一项都不计算，对应字段保留 {@code null}——前端遇到 null 显示「—」，
     * 而不是把缺失当成 0 展示成「涨幅 0%」。
     */
    static Map<String, Object> computeNumbers(Integer currentMonthly, Integer expectedMonthly,
                                              Integer offerMonthly, Integer offerMonths) {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("raiseAmount",
                currentMonthly != null && offerMonthly != null ? offerMonthly - currentMonthly : null);
        numbers.put("raisePct",
                currentMonthly != null && currentMonthly > 0 && offerMonthly != null
                        ? round1((offerMonthly - currentMonthly) * 100.0 / currentMonthly) : null);
        numbers.put("offerAnnual",
                offerMonthly != null && offerMonths != null ? offerMonthly * offerMonths : null);
        numbers.put("expectedGapMonthly",
                offerMonthly != null && expectedMonthly != null ? offerMonthly - expectedMonthly : null);
        numbers.put("expectedGapPct",
                offerMonthly != null && expectedMonthly != null && expectedMonthly > 0
                        ? round1((offerMonthly - expectedMonthly) * 100.0 / expectedMonthly) : null);
        return numbers;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** 统一字段形状：缺失或类型不符的字段回退为空值，避免前端渲染时报错 */
    static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headline", raw.get("headline") instanceof String h ? h : "");
        out.put("analysis", listOf(raw.get("analysis")));
        out.put("scripts", listOf(raw.get("scripts")));
        out.put("qa", listOf(raw.get("qa")));
        out.put("warnings", listOf(raw.get("warnings")));
        out.put("actions", listOf(raw.get("actions")));
        return out;
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("谈薪建议结果解析失败：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 统一 AI 调用：空值校验 + 全局并发闸门（与项目其它 AI 服务一致） */
    private String callAi(String prompt) {
        String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                chatClient.prompt().user(prompt).call().content());
        if (response == null || response.isBlank()) {
            log.warn("谈薪建议模型返回为空，使用兜底结构");
            return "";
        }
        return response;
    }
}
