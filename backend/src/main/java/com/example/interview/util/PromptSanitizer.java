package com.example.interview.util;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt 注入防御工具
 *
 * <p>统一各 Service / Controller 的输入净化逻辑，避免 9 处 private sanitizePromptInput 重复代码。
 *
 * <p>防御策略：
 * <ol>
 *   <li>清除不可见内容：HTML 注释、零宽 / Bidi / 标签字符、变体选择符连续段（v1.86.0）</li>
 *   <li>截断：超长输入截断至 {@value #MAX_INPUT_LENGTH} 字符，防止 token 滥用</li>
 *   <li>模式剥离：移除 "忽略以上所有指令"、"ignore previous instructions"、"你现在是" 等常见注入模式</li>
 * </ol>
 *
 * <p><b>⚠️ v1.64.0 重要说明：本类的长度上限是「全局兜底」，不是「业务上限」。</b>
 * 项目里 40+ 处调用都写成 {@code sanitize(TextUtil.truncate(text, 服务级上限))}，
 * 即**服务先按业务需要截断**，再由本类兜底。若本类上限低于某个服务级上限，
 * 那个服务的上限就**形同虚设** —— 而且**没有任何日志**，排查时完全看不见。
 *
 * <p>实测教训（v1.64.0）：{@code JobAnalysisService.MAX_TEXT_LEN} 原为 1200、后提到 4000，
 * 但本类上限是 2000 —— 真正生效的一直是 2000，那次改动几乎没起作用。
 * 这类「静默生效的上限」比配置写错更难发现：代码读起来是对的，行为却不是。
 *
 * <p>因此两条纪律：**① 本类上限必须 ≥ 所有服务级上限；② 一旦发生截断必须记 WARN。**
 *
 * <p><b>⚠️ v1.86.0 为什么必须先清不可见字符、再跑注入模式。</b>
 * 岗位正文、简历这类文本来自第三方。把「忽略以上指令」写成「忽{零宽}略以上指令」，
 * 肉眼读者看到的是同一句话，而上文的注入正则**一个都匹配不到** ——
 * 净化看起来执行了，实际把原文原样交给了模型。这不是理论风险：同类手法
 * （零宽、Bidi 控制符、Unicode Tag 字符、变体选择符夹带）是公开的提示词走私套路。
 * 因此净化顺序固定为「先清不可见 → 再截断 → 再匹配模式」，
 * 且截断发生在清除之后，上限按**可见字符**计。
 *
 * <p>注意：这是基础防御层，不能替代系统提示词（system prompt）中的边界声明。
 * 对于高安全场景，应叠加输出校验与 RBAC。
 */
public final class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    /**
     * 单次输入最大长度（**全局兜底**，不是业务上限）。
     *
     * <p>v1.64.0 由 2000 提到 8000：原值会静默压掉所有高于它的服务级上限。
     * 8000 覆盖当前全部服务级上限（最大的是 JD/简历分析的 4000 与岗位正文入库的 6000）。
     */
    public static final int MAX_INPUT_LENGTH = 8000;

    /**
     * 不可见 / 方向控制字符：渲染为空，却能把关键词拆成正则认不出的形态。
     * <ul>
     *   <li>U+200B–U+200F 零宽空格 / 零宽连接符 / 方向标记</li>
     *   <li>U+202A–U+202E、U+2066–U+2069 Bidi 控制符（可让显示顺序与真实顺序相反）</li>
     *   <li>U+2060–U+2064 词连接符等</li>
     *   <li>U+FEFF BOM、U+00AD 软连字符</li>
     *   <li>U+E0000–U+E007F Unicode Tag 字符（渲染为空、对模型读作 ASCII，可夹带整段文本）</li>
     * </ul>
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\u00AD]"
                    + "|[\\x{E0000}-\\x{E007F}]");

    /**
     * 变体选择符**连续段**：单个（如 U+FE0F）是 emoji 呈现样式，属正常排版；
     * 连续 2 个以上没有排版含义，是「每个字符承载一字节」的夹带手法。
     */
    private static final Pattern VARIATION_RUN =
            Pattern.compile("[\\uFE00-\\uFE0F\\x{E0100}-\\x{E01EF}]{2,}");

    /** 隐藏指令最常见的载体：前端不渲染、模型却能读到 */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern INJECTION_CN = Pattern.compile(
            "忽略(以上|之前|以前|前面|先前|上述|上文)(所有|全部)?的?(指令|规则|要求|提示|说明)");

    private static final Pattern INJECTION_EN = Pattern.compile(
            "(?i)ignore\\s+(all\\s+)?(previous|above|prior|earlier)\\s+(instructions|prompts)");

    private static final Pattern ROLE_CN = Pattern.compile("你现在是");
    private static final Pattern ROLE_EN = Pattern.compile("(?i)you are now");

    private PromptSanitizer() {}

    /**
     * 净化用户输入，防止 prompt 注入。
     *
     * @param input 原始输入，可为 null
     * @return 净化后的字符串，null 返回空串
     */
    public static String sanitize(String input) {
        if (input == null) return "";
        // 顺序不可调换：先清不可见字符，再截断，最后匹配注入模式（见类注释 v1.86.0）
        String s = stripInvisible(input);
        if (s.length() > MAX_INPUT_LENGTH) {
            // 绝不能静默：触发这里意味着「某个服务级上限设得比全局兜底还大」，
            // 那属于配置错误，必须能在日志里被看见。
            log.warn("PromptSanitizer 触发全局兜底截断：{} → {} 字。"
                    + "请检查调用方是否把服务级上限设得过大（本上限必须 ≥ 所有服务级上限）",
                    s.length(), MAX_INPUT_LENGTH);
            s = s.substring(0, MAX_INPUT_LENGTH);
        }
        // 中英文常见注入模式，统一替换为占位符，保留语义可读性
        s = INJECTION_CN.matcher(s).replaceAll("[已过滤]");
        s = INJECTION_EN.matcher(s).replaceAll("[filtered]");
        s = ROLE_CN.matcher(s).replaceAll("用户提到：");
        s = ROLE_EN.matcher(s).replaceAll("user mentioned:");
        return s;
    }

    /**
     * 清除不可见内容。只清「渲染为空 / 无正常排版含义」的部分，正文一字不动；
     * 单字符的变体选择符保留，避免把 emoji 的呈现样式一起清掉。
     */
    private static String stripInvisible(String input) {
        String s = HTML_COMMENT.matcher(input).replaceAll(" ");
        s = INVISIBLE.matcher(s).replaceAll("");
        s = VARIATION_RUN.matcher(s).replaceAll("");
        if (!s.equals(input)) {
            // 只记数量不记内容：这段文本可能含隐私，且此处只关心「有没有被夹带」
            log.debug("PromptSanitizer 清除了不可见内容：{} → {} 字符", input.length(), s.length());
        }
        return s;
    }
}
