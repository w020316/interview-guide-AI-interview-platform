package com.example.interview.util;

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
 * <p>注意：这是基础防御层，不能替代系统提示词（system prompt）中的边界声明。
 * 对于高安全场景，应叠加输出校验与 RBAC。
 */
public final class PromptSanitizer {

    /** 单次输入最大长度，超过则截断 */
    public static final int MAX_INPUT_LENGTH = 2000;

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
