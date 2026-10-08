package com.example.interview.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt 注入防御工具
 *
 * <p>统一各 Service / Controller 的输入净化逻辑，避免 9 处 private sanitizePromptInput 重复代码。
 *
 * <p>防御策略：
 * <ol>
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

    private PromptSanitizer() {}

    /**
     * 净化用户输入，防止 prompt 注入。
     *
     * @param input 原始输入，可为 null
     * @return 净化后的字符串，null 返回空串
     */
    public static String sanitize(String input) {
        if (input == null) return "";
        String s = input;
        if (s.length() > MAX_INPUT_LENGTH) {
            // 绝不能静默：触发这里意味着「某个服务级上限设得比全局兜底还大」，
            // 那属于配置错误，必须能在日志里被看见。
            log.warn("PromptSanitizer 触发全局兜底截断：{} → {} 字。"
                    + "请检查调用方是否把服务级上限设得过大（本上限必须 ≥ 所有服务级上限）",
                    s.length(), MAX_INPUT_LENGTH);
            s = s.substring(0, MAX_INPUT_LENGTH);
        }
        // 中英文常见注入模式，统一替换为占位符，保留语义可读性
        s = s.replaceAll("(?i)忽略以上(所有)?(指令|规则|要求)", "[已过滤]")
             .replaceAll("(?i)ignore (all )?(previous|above) instructions", "[filtered]")
             .replaceAll("(?i)你现在是", "用户提到：")
             .replaceAll("(?i)you are now", "user mentioned:");
        return s;
    }
}
